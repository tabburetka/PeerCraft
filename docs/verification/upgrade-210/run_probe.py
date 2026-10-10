import pathlib,subprocess,os,time,signal,shutil,csv,datetime,hashlib
ROOT=pathlib.Path(__file__).resolve().parents[3]
RUN=ROOT/'build/upgrade-210'/('run-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'));RUN.mkdir()
GRADLE='/home/ikuku/.gradle/wrapper/dists/gradle-8.8-bin/dl7vupf4psengwqhwktix4v1/gradle-8.8/bin/gradle'
def ram():
 return next(int(x.split()[1])//1024 for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:'))
print('RUN_DIRECTORY',RUN,flush=True)
with (RUN/'memory.csv').open('w') as out:
 w=csv.writer(out);w.writerow(['phase','utc','available_mib'])
 for phase in (['new'] if os.environ.get('PEERCRAFT_UPGRADE_BASELINE') else ['old','new']):
  game=RUN/phase
  if phase=='new': shutil.copytree(pathlib.Path(os.environ['PEERCRAFT_UPGRADE_BASELINE']) if os.environ.get('PEERCRAFT_UPGRADE_BASELINE') else RUN/'old/saves',game/'saves')
  if ram()<4096:raise RuntimeError('Insufficient RAM before launch')
  cmd=[GRADLE,'-g',str(ROOT/'build/handoff-gradle-home'),'runClient','-I',str(ROOT/'docs/verification/upgrade-210/probe.gradle'),'--offline','--no-daemon','--max-workers=1','-Dorg.gradle.jvmargs=-Xmx384m','-Porg.gradle.java.installations.paths=/home/ikuku/.gradle/jdks/temurin-8-amd64-linux.2','-Dpeercraft.upgrade.phase='+phase,'-Dpeercraft.upgrade.gameDir='+str(game)]
  with (RUN/(phase+'.log')).open('w') as log:
   p=subprocess.Popen(cmd,cwd=ROOT/'peercraft-forge-1122',stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
   end=time.monotonic()+360
   try:
    while p.poll() is None:
     m=ram();w.writerow([phase,datetime.datetime.now(datetime.timezone.utc).isoformat(),m]);out.flush()
     if m<3072:raise RuntimeError('RAM guard')
     if time.monotonic()>end:raise RuntimeError('Native phase timed out')
     time.sleep(2)
   finally:
    if p.poll() is None:
     os.killpg(p.pid,signal.SIGTERM)
     try:p.wait(timeout=15)
     except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait()
   print('PHASE_EXIT',phase,p.returncode,flush=True)
  data=(RUN/(phase+'.log')).read_text(errors='replace')
  if p.returncode or 'UPGRADE_PROBE_DONE phase='+phase not in data:raise RuntimeError('Phase failed: '+phase+'; inspect '+str(RUN/(phase+'.log')))
  print('PHASE_PASSED',phase,flush=True)
print('UPGRADE_RUN_PASSED',flush=True)
