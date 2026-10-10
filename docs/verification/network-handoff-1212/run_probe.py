"""Run isolated native 1.21.2 handoff with owned process groups and RAM guard."""
import csv, datetime, os, pathlib, signal, subprocess, time
ROOT = pathlib.Path(__file__).resolve().parents[3]
RUN = ROOT / 'build/handoff-runs-1212' / ('run-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
RUN.mkdir(parents=True)
COORD = RUN / 'coordination'
PORT = int(os.environ.get('PEERCRAFT_PROBE_PORT', '39868'))
SCENARIO = os.environ.get('PEERCRAFT_PROBE_SCENARIO','cycle')
if SCENARIO not in ('cycle','decline','repeat','cancel'): raise ValueError('Unknown scenario')
GRADLE = pathlib.Path('/home/ikuku/.gradle/wrapper/dists/gradle-9.5.1-bin/iq79hdu3mqx29lgffhp8bfmx/gradle-9.5.1/bin/gradle')
JAVA8 = '/home/ikuku/.gradle/jdks/temurin-8-amd64-linux.2'
children = []
handles = []
def available():
    for line in pathlib.Path('/proc/meminfo').read_text().splitlines():
        if line.startswith('MemAvailable:'): return int(line.split()[1]) // 1024
def launch(name, command, cwd=ROOT):
    handle = (RUN / (name + '.log')).open('w')
    handles.append(handle)
    proc = subprocess.Popen(command, cwd=cwd, stdout=handle, stderr=subprocess.STDOUT, start_new_session=True)
    children.append((name, proc)); return proc
def game(role):
    return launch(role, [str(GRADLE), '-g', '/home/ikuku/.gradle', ':1.21.2-fabric:runClient', '-I', str(ROOT/'docs/verification/network-handoff-1212/probe.gradle'), '--offline', '--no-daemon', '--max-workers=1', '-Dorg.gradle.jvmargs=-Xmx512m', '-Porg.gradle.java.installations.paths='+JAVA8, '-Dpeercraft.probe.role='+role, '-Dpeercraft.probe.coordination='+str(COORD), '-Dpeercraft.probe.accountPort='+str(PORT), '-Dpeercraft.probe.scenario='+SCENARIO], ROOT/'peercraft')
def log(name):
    path=RUN/(name+'.log'); return path.read_text(errors='replace') if path.exists() else ''
print('RUN_DIRECTORY',RUN,flush=True)
try:
    if available() < 4096: raise RuntimeError('Less than 4 GiB available before launch')
    server=launch('server',['java','-Xmx128m','-XX:ActiveProcessorCount=2','-Dpeercraft.rendezvous.dataDir='+str(RUN/'server-data'),'-jar',str(ROOT/'rendezvous-server/build/libs/rendezvous-server-1.0.0.jar'),str(PORT)])
    host=game('host'); guest=None
    deadline=time.monotonic()+660
    failure_seen=None
    with (RUN/'memory.csv').open('w',newline='') as output:
        writer=csv.writer(output);writer.writerow(['utc','available_mib','host_exit','guest_exit'])
        while time.monotonic()<deadline:
            mem=available();writer.writerow([datetime.datetime.now(datetime.timezone.utc).isoformat(),mem,host.poll(),guest.poll() if guest else 'not_started']);output.flush()
            if mem < 3072: raise RuntimeError('RAM guard: available below 3 GiB')
            if server.poll() is not None: raise RuntimeError('Test rendezvous exited')
            if (COORD/'failed').exists():
                if failure_seen is None: failure_seen=time.monotonic()
                if time.monotonic()-failure_seen >= 6: raise RuntimeError((COORD/'failed').read_text())
            if guest is None and (COORD/'room').exists():
                if mem < 4096: raise RuntimeError('Less than 4 GiB before guest launch')
                guest=game('guest'); print('GUEST_STARTED available_mib='+str(mem),flush=True)
            if host.poll() is not None and 'NETWORK_HANDOFF_PROBE_DONE role=host' not in log('host'): raise RuntimeError('Host exited without DONE: '+((COORD/'failed').read_text() if (COORD/'failed').exists() else 'see host.log'))
            if guest is not None and guest.poll() is not None and 'NETWORK_HANDOFF_PROBE_DONE role=guest' not in log('guest'): raise RuntimeError('Guest exited without DONE: '+((COORD/'failed').read_text() if (COORD/'failed').exists() else 'see guest.log'))
            if guest is not None and host.poll() is not None and guest.poll() is not None:
                if host.returncode or guest.returncode: raise RuntimeError('Client process exit failure')
                print('RUN_PASSED',flush=True);break
            time.sleep(2)
        else: raise RuntimeError('Native run deadline exceeded')
finally:
    for name,proc in children:
        if proc.poll() is None:
            try: os.killpg(proc.pid,signal.SIGTERM)
            except ProcessLookupError: pass
    for name,proc in children:
        try: proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid,signal.SIGKILL);proc.wait(timeout=5)
        print('PROCESS_EXIT',name,proc.returncode,flush=True)
    for handle in handles: handle.close()
    print('FINAL_AVAILABLE_MIB',available(),flush=True)
