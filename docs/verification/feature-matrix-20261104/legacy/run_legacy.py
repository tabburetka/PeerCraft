#!/usr/bin/env python3
"""Legacy targets (Fabric 1.16.5, Forge 1.12.2, Forge 1.7.10), one client at a time.

For each target:
  update  LegacyUpdateProbe on a fresh game dir: first-run telemetry notice (captured,
          declined, gate must stay closed), then the title-screen update notice against a
          local update.json advertising 9.9.9.
  esc     the existing in-game menu probe (pause "PC" entry overlap check, quick menu,
          players, friends) from docs/verification/{session-ui-1165,ingame-menu-1122,session-ui-1710}.

Usage from the repository root:  python3 docs/verification/feature-matrix-20261104/legacy/run_legacy.py
"""
import datetime, http.server, json, os, pathlib, shutil, signal, socketserver, subprocess, sys, threading, time

ROOT = pathlib.Path(__file__).resolve().parents[4]
HERE = pathlib.Path(__file__).resolve().parent
RUN = ROOT / 'build/feature-matrix-20261104' / ('legacy-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
HTTP_PORT = int(os.environ.get('FM_HTTP_PORT', '51379'))
ONLY = os.environ.get('FM_LEGACY', '1.16.5,1.12.2,1.7.10').split(',')
RUN.mkdir(parents=True)
(RUN / 'http').mkdir()
(RUN / 'http/update.json').write_text(json.dumps({'stable': '9.9.9', 'beta': '9.9.9',
    'url': 'https://www.curseforge.com/minecraft/mc-mods/peercraft/files/all'}))
URL = f'http://127.0.0.1:{HTTP_PORT}/update.json'

TARGETS = {
    '1.16.5': {'cwd': ROOT / 'peercraft', 'task': ':1.16.5-fabric:runClient',
               'esc_init': ROOT / 'docs/verification/session-ui-1165/probe.gradle',
               'esc_pass': 'SESSION_UI_1165_PASSED', 'esc_fail': 'SESSION_UI_1165_FAILED',
               'esc_dir': ROOT / 'build/session-ui-1165/game'},
    '1.12.2': {'cwd': ROOT / 'peercraft-forge-1122', 'task': 'runClient',
               'esc_init': ROOT / 'docs/verification/ingame-menu-1122/probe.gradle',
               'esc_pass': 'INGAME_MENU_1122_PASSED', 'esc_fail': 'INGAME_MENU_1122_FAILED',
               'esc_dir': ROOT / 'build/ingame-menu-1122/game'},
    '1.7.10': {'cwd': ROOT / 'peercraft-forge-1710', 'task': 'runClient',
               'esc_init': ROOT / 'docs/verification/session-ui-1710/probe.gradle',
               'esc_pass': 'SESSION_UI_1710_PASSED', 'esc_fail': 'SESSION_UI_1710_FAILED',
               'esc_dir': ROOT / 'build/session-ui-1710/game'},
}


class Quiet(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=str(RUN / 'http'), **k)

    def log_message(self, fmt, *args):
        with (RUN / 'http.log').open('a') as f:
            f.write((fmt % args) + ' UA=' + str(self.headers.get('User-Agent')) + '\n')


def available_mib():
    for line in pathlib.Path('/proc/meminfo').read_text().splitlines():
        if line.startswith('MemAvailable:'):
            return int(line.split()[1]) // 1024


def run(name, cmd, cwd, done_markers, timeout=420):
    with (RUN / f'{name}.log').open('w') as log:
        proc = subprocess.Popen(cmd, cwd=cwd, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        end = time.monotonic() + timeout
        try:
            while proc.poll() is None:
                if time.monotonic() > end:
                    raise TimeoutError(name)
                if available_mib() < 1200:
                    raise MemoryError(f'RAM guard during {name}')
                time.sleep(2)
        except BaseException:
            os.killpg(proc.pid, signal.SIGTERM)
            try: proc.wait(timeout=20)
            except subprocess.TimeoutExpired: os.killpg(proc.pid, signal.SIGKILL)
            raise
    text = (RUN / f'{name}.log').read_text(errors='replace')
    lines = [l[l.index(m):].strip() for l in text.splitlines() for m in done_markers if m in l]
    return proc.returncode, lines


socketserver.TCPServer.allow_reuse_address = True
httpd = socketserver.TCPServer(('127.0.0.1', HTTP_PORT), Quiet)
threading.Thread(target=httpd.serve_forever, daemon=True).start()
results = {'run': str(RUN), 'targets': {}}
print('RUN_DIRECTORY', RUN, flush=True)
try:
    for mc in ONLY:
        t = TARGETS[mc]
        entry = results['targets'].setdefault(mc, {})
        game = RUN / f'game-{mc}'
        gradle = ['./gradlew', t['task'], '--offline', '--no-daemon', '--console=plain', '-Dorg.gradle.jvmargs=-Xmx768m']
        try:
            code, lines = run(f'update-{mc}', gradle + ['-I', str(HERE / 'legacy.gradle'), f'-Dlegacy.gameDir={game}',
                              f'-Dpeercraft.updateCheck.url={URL}'], t['cwd'],
                              ['LEGACY_TELEMETRY_DECLINED', 'LEGACY_UPDATE_NOTICE', 'LEGACY_UPDATE_PASSED', 'LEGACY_UPDATE_FAILED'])
            entry['update'] = {'exit': code, 'markers': lines,
                               'result': 'PASS' if any('LEGACY_UPDATE_PASSED' in l for l in lines) else 'FAIL'}
        except Exception as e:
            entry['update'] = {'result': 'FAIL', 'error': repr(e)}
        print('UPDATE', mc, entry['update']['result'], flush=True)
        try:
            code, lines = run(f'esc-{mc}', gradle + ['-I', str(t['esc_init'])], t['cwd'],
                              ['INGAME_MENU_PAUSE_ENTRY_PASSED', t['esc_pass'], t['esc_fail']])
            entry['esc'] = {'exit': code, 'markers': lines,
                            'result': 'PASS' if any(t['esc_pass'] in l for l in lines) else 'FAIL'}
            shots = RUN / f'esc-{mc}-screenshots'
            src = t['esc_dir'] / 'screenshots'
            if src.exists():
                shutil.copytree(src, shots, dirs_exist_ok=True)
        except Exception as e:
            entry['esc'] = {'result': 'FAIL', 'error': repr(e)}
        print('ESC', mc, entry['esc']['result'], flush=True)
finally:
    httpd.shutdown()
    results['http_requests'] = (RUN / 'http.log').read_text().splitlines() if (RUN / 'http.log').exists() else []
    (RUN / 'results.json').write_text(json.dumps(results, indent=2, ensure_ascii=False))
    print('RESULTS', RUN / 'results.json', flush=True)
