#!/usr/bin/env python3
"""Two-client feature matrix: mod sync removal, ESC menu, update check, telemetry.

Usage (from the repository root):
    python3 docs/verification/feature-matrix-20261104/run_matrix.py 26.2-fabric

Starts an isolated rendezvous server (own data dir, loopback UDP port) and a tiny local HTTP
server for update.json, then a host client and, one after another, joiner clients:

  remove  joiner A, AppleSkin in mods/, removeExtra=all, telemetry allowed, newer stable
          published -> telemetry notice, update notice, admission, confirm screen with the
          AppleSkin removal row, restart screen, quit; the helper must then delete the jar
          (backup kept, journal APPLIED)
  rejoin  joiner A again -> no removal left, joins the world, guest ESC menu
  off     joiner B, AppleSkin, removeExtra=off, telemetry declined, same version published
          -> no update notice, joins directly, jar untouched
  untick  joiner C, AppleSkin, removeExtra=all, only a newer *beta* published -> no update
          notice (stable player), confirm screen, removal unticked, joins, jar untouched

The host checks its own ESC menu with the real guest connected. Each client runs in its own
process group; a RAM guard aborts the run below 2.5 GiB available.
"""
import datetime, http.server, json, os, pathlib, shutil, signal, socketserver, subprocess, sys, threading, time

ROOT = pathlib.Path(__file__).resolve().parents[3]
HERE = pathlib.Path(__file__).resolve().parent
TARGET = sys.argv[1] if len(sys.argv) > 1 else '26.2-fabric'
MC = TARGET.split('-')[0]
STAMP = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
RUN = ROOT / 'build/feature-matrix-20261104' / f'{TARGET}-{STAMP}'
COORD = RUN / 'coordination'
PORT = int(os.environ.get('FM_PORT', '51377'))
HTTP_PORT = int(os.environ.get('FM_HTTP_PORT', '51378'))
SERVER_JAR = ROOT / 'rendezvous-server/build/libs/rendezvous-server-1.0.0.jar'
APPLESKIN = ROOT / f'local-test/appleskin/appleskin-fabric-{MC}.jar'
SCENARIOS = os.environ.get('FM_SCENARIOS', 'remove,rejoin,off,untick').split(',')
MOD_VERSION = (ROOT / 'peercraft/gradle.properties').read_text().split('mod_version=')[1].split()[0]

children, results = [], {'target': TARGET, 'run': str(RUN), 'mod_version': MOD_VERSION, 'scenarios': {}}
RUN.mkdir(parents=True)
COORD.mkdir()
(RUN / 'http').mkdir()


def available_mib():
    for line in pathlib.Path('/proc/meminfo').read_text().splitlines():
        if line.startswith('MemAvailable:'):
            return int(line.split()[1]) // 1024


def publish(stable, beta):
    (RUN / 'http/update.json').write_text(json.dumps({'stable': stable, 'beta': beta,
        'url': 'https://www.curseforge.com/minecraft/mc-mods/peercraft/files/all'}))


class Quiet(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=str(RUN / 'http'), **k)

    def log_message(self, fmt, *args):
        with (RUN / 'http.log').open('a') as f:
            f.write((fmt % args) + ' UA=' + str(self.headers.get('User-Agent')) + '\n')


def launch(name, cmd, cwd):
    log = (RUN / f'{name}.log').open('w')
    proc = subprocess.Popen(cmd, cwd=cwd, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    children.append((name, proc, log))
    return proc


def client(name, role, game_dir, scenario, **props):
    base = {
        'peercraft.fm.target': TARGET, 'peercraft.fm.role': role, 'peercraft.fm.scenario': scenario,
        'peercraft.fm.coord': str(COORD), 'peercraft.fm.port': str(PORT), 'peercraft.fm.gameDir': str(game_dir),
        'peercraft.updateCheck.url': f'http://127.0.0.1:{HTTP_PORT}/update.json',
        'peercraft.proxyPort': '25601' if role == 'host' else '25602',
    }
    base.update(props)
    cmd = ['./gradlew', f':{TARGET}:runClient', '-I', str(HERE / 'probe.gradle'), '--offline', '--no-daemon',
           '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx512m', '--console=plain']
    cmd += [f'-D{k}={v}' for k, v in base.items()]
    return launch(name, cmd, ROOT / 'peercraft')


def log_text(name):
    p = RUN / f'{name}.log'
    return p.read_text(errors='replace') if p.exists() else ''


def wait(cond, timeout, what, watch=()):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if cond():
            return
        mem = available_mib()
        if mem < 1200:
            raise RuntimeError(f'RAM guard: {mem} MiB available while waiting for {what}')
        for name, proc in watch:
            if proc.poll() is not None and not cond():
                raise RuntimeError(f'{name} exited ({proc.returncode}) before {what}; see {name}.log')
        if any(COORD.glob('failed-*')):
            raise RuntimeError('client failure: ' + '; '.join(p.read_text() for p in COORD.glob('failed-*')))
        time.sleep(1)
    raise RuntimeError(f'timeout waiting for {what}')


def markers(name, prefix='FM_'):
    return [l[l.index(prefix):].strip() for l in log_text(name).splitlines() if ': ' + prefix in l or l.startswith(prefix)]


def joiner_dir(letter):
    d = RUN / f'joiner-{letter}'
    (d / 'mods').mkdir(parents=True, exist_ok=True)
    return d


def appleskin_present(d):
    return sorted(p.name for p in (d / 'mods').glob('appleskin*.jar'))


def run_joiner(scenario, letter, name, expect_joined, **props):
    d = joiner_dir(letter)
    log = f'joiner-{scenario}'
    before = appleskin_present(d)
    proc = client(log, 'joiner', d, scenario, **{'peercraft.fm.name': name, **props})
    wait(lambda: proc.poll() is not None, 400, f'{scenario} joiner exit', [])
    m = markers(log)
    entry = {'exit': proc.returncode, 'markers': m, 'appleskin_before': before}
    failed = [x for x in m if x.startswith('FM_FAILED')]
    if failed:
        entry['result'] = 'FAIL'
        entry['error'] = failed[0]
    elif expect_joined and not any(x.startswith('FM_JOINER_DONE') for x in m):
        entry['result'] = 'FAIL'
        entry['error'] = 'joiner did not reach the world / ESC menu'
    else:
        entry['result'] = 'PASS'
    return d, entry


def wait_helper(d, timeout=90):
    ops = d / 'config/peercraft/modsync/operations'
    end = time.monotonic() + timeout
    state = None
    while time.monotonic() < end:
        plans = [p for p in ops.glob('*/plan.properties')]
        if plans:
            props = dict(l.split('=', 1) for l in plans[0].read_text().splitlines() if '=' in l and not l.startswith('#'))
            state = props.get('state')
            if state in ('APPLIED', 'ROLLED_BACK', 'RECOVERY_REQUIRED'):
                return plans[0].parent, props
        time.sleep(1)
    return None, {'state': state}


print('RUN_DIRECTORY', RUN, flush=True)
server = None
httpd = None
try:
    if not SERVER_JAR.exists():
        raise RuntimeError('build rendezvous-server first: ./gradlew jar')
    if not APPLESKIN.exists():
        raise RuntimeError(f'missing {APPLESKIN}')
    if available_mib() < 2800:
        raise RuntimeError(f'need 2.8 GiB available, have {available_mib()} MiB')
    server = launch('server', ['java', '-Xmx160m', '-Dpeercraft.rendezvous.dataDir=' + str(RUN / 'server-data'),
                               '-jar', str(SERVER_JAR), str(PORT)], ROOT)
    socketserver.TCPServer.allow_reuse_address = True
    httpd = socketserver.TCPServer(('127.0.0.1', HTTP_PORT), Quiet)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    publish(MOD_VERSION, MOD_VERSION)

    for letter in 'abc':
        shutil.copy(APPLESKIN, joiner_dir(letter) / 'mods' / APPLESKIN.name)

    host = client('host', 'host', RUN / 'host', 'host', **{'peercraft.fm.name': 'HostPlayer', 'peercraft.fm.telemetry': 'decline'})
    wait(lambda: (COORD / 'room').exists(), 300, 'host room', [('host', host)])
    print('ROOM', (COORD / 'room').read_text(), flush=True)

    if 'remove' in SCENARIOS:
        publish('9.9.9', '9.9.9')
        d, e = run_joiner('remove', 'a', 'JoinerA1', False, **{
            'peercraft.modSync.removeExtra': 'all', 'peercraft.fm.telemetry': 'allow', 'peercraft.fm.expectUpdate': 'true'})
        tx, plan = wait_helper(d)
        e['helper_state'] = plan.get('state')
        e['appleskin_after'] = appleskin_present(d)
        e['backup'] = sorted(p.name for p in (tx / 'backups').glob('*')) if tx else []
        ok = (e['result'] == 'PASS' and any('FM_MODSYNC_RESTART_SCREEN' in x for x in e['markers'])
              and plan.get('state') == 'APPLIED' and not e['appleskin_after'] and APPLESKIN.name in e['backup'])
        if not ok and e['result'] == 'PASS':
            e['error'] = 'helper did not remove the jar as expected'
        e['result'] = 'PASS' if ok else 'FAIL'
        if tx and (tx / 'helper.log').exists():
            shutil.copy(tx / 'helper.log', RUN / 'remove-helper.log')
            shutil.copy(tx / 'plan.properties', RUN / 'remove-plan.properties')
        results['scenarios']['remove'] = e
        print('SCENARIO remove', e['result'], flush=True)

    if 'rejoin' in SCENARIOS:
        d, e = run_joiner('rejoin', 'a', 'JoinerA2', True, **{
            'peercraft.modSync.removeExtra': 'all', 'peercraft.fm.expectUpdate': 'true'})
        e['appleskin_after'] = appleskin_present(d)
        results['scenarios']['rejoin'] = e
        print('SCENARIO rejoin', e['result'], flush=True)

    if 'off' in SCENARIOS:
        publish(MOD_VERSION, MOD_VERSION)
        d, e = run_joiner('off', 'b', 'JoinerB', True, **{
            'peercraft.modSync.removeExtra': 'off', 'peercraft.fm.telemetry': 'decline', 'peercraft.fm.expectUpdate': 'false'})
        e['appleskin_after'] = appleskin_present(d)
        if e['result'] == 'PASS' and not e['appleskin_after']:
            e['result'], e['error'] = 'FAIL', 'jar removed with removeExtra=off'
        results['scenarios']['off'] = e
        print('SCENARIO off', e['result'], flush=True)

    if 'untick' in SCENARIOS:
        publish(MOD_VERSION, '9.9.9-beta.1')
        d, e = run_joiner('untick', 'c', 'JoinerC', True, **{
            'peercraft.modSync.removeExtra': 'all', 'peercraft.fm.telemetry': 'decline', 'peercraft.fm.expectUpdate': 'false'})
        e['appleskin_after'] = appleskin_present(d)
        if e['result'] == 'PASS' and not e['appleskin_after']:
            e['result'], e['error'] = 'FAIL', 'unticked removal still deleted the jar'
        results['scenarios']['untick'] = e
        print('SCENARIO untick', e['result'], flush=True)

    host_ok = (COORD / 'host-menu-done').exists()
    (COORD / 'stop').write_text('yes')
    wait(lambda: host.poll() is not None, 60, 'host exit', [])
    results['host'] = {'exit': host.returncode, 'markers': markers('host'),
                       'result': 'PASS' if host_ok and not any(x.startswith('FM_FAILED') for x in markers('host')) else 'FAIL'}
finally:
    for name, proc, _ in children:
        if proc.poll() is None and name != 'server':
            try: os.killpg(proc.pid, signal.SIGTERM)
            except ProcessLookupError: pass
    if server and server.poll() is None:
        server.send_signal(signal.SIGTERM)
        try: server.wait(timeout=15)
        except subprocess.TimeoutExpired: os.killpg(server.pid, signal.SIGKILL)
    for name, proc, log in children:
        try: proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL); proc.wait(timeout=5)
        log.close()
    if httpd: httpd.shutdown()
    analytics = RUN / 'server-data/analytics/analytics.json'
    if analytics.exists():
        data = json.loads(analytics.read_text())
        results['analytics'] = data
        # Only joiner A allowed telemetry: exactly its two joins (restart_required, then success)
        # may reach the collector; the host and joiners B/C declined and must send nothing.
        tel = next(iter(data.get('days', {}).values()), {}).get('telemetry', {})
        joins = tel.get('joins', {})
        expected = {'observed': 2, 'restart_required': 1, 'success': 1}
        if not {'remove', 'rejoin', 'off', 'untick'} <= set(SCENARIOS):
            expected = None
        results['telemetry'] = {'joins': joins, 'modsync': tel.get('modsync', {}), 'expected': expected,
                                'result': 'SKIP' if expected is None else ('PASS' if joins == expected else 'FAIL')}
    shots = sorted(str(p.relative_to(RUN)) for p in RUN.glob('*/screenshots/fm-*.png'))
    results['screenshots'] = shots
    results['http_requests'] = (RUN / 'http.log').read_text().splitlines() if (RUN / 'http.log').exists() else []
    (RUN / 'results.json').write_text(json.dumps(results, indent=2, ensure_ascii=False))
    print('RESULTS', RUN / 'results.json', flush=True)
    print('FINAL_AVAILABLE_MIB', available_mib(), flush=True)
