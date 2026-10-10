# Native network handoff/return probe, Forge 1.12.2

This separate fixture is prepared for actual SafeHandoffSession transfer, successor
startup/registration, original-host reconnect, and transfer back. A complete native two-client transfer/return cycle passed on 2026-10-09.
Failure scenarios and clean disconnect remain open; compilation alone is not proof.

Start an isolated rendezvous server, then run the host and guest with the same fresh
coordination directory and server port, using `probe.gradle` as the Gradle init file.
Use `--no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx384m'`. Start the guest
only after the host room exists and sufficient available system memory is confirmed.
Each game is restricted to 768 MiB heap, 640 MiB direct buffers, two CPU workers and
view distance four. Only temporary fixture worlds and accounts may be used.

The fixture registers real unlicensed accounts, checks distinct native progress and
reconnect, then invokes production SafeHandoffSession.begin. It explicitly accepts
its native GuiYesNo consent dialogs in both directions. The original host rejoins
through the successor's registered PeerCraft room and checks its prior inventory/XP.
The successor starts a return handoff to that connected account. The returned host
checks original-owner metadata, both bound account UUIDs, its own items/XP and the
offline successor's inventory, ender chest and XP before confirmed native shutdown.
The stable world ID is obtained by the same ensureHosting path as the player picker.

Success requires NETWORK_HANDOFF_PROBE_DONE for both roles, no failure marker,
normal exits, and inspection of native/network errors in both logs. Tokens/passwords
are not written into coordination files. If the host aborts or an authority outcome
is unresolved, preserve the fixture journals/worlds for diagnosis; never convert
such a result into a successful verification.

## First native attempt

Two clients reached explicit successor consent, then preflight aborted on the
Forge builtin `Minecraft` pseudo-JAR. Platform classification is now case-insensitive;
all eight HostManifestCapture tests passed, including a missing ordinary mod still
rejecting preflight. No successful handoff is claimed. All owned test processes were
stopped. The adjusted fixture bundles loaded production/probe classes into a real JAR
for strict manifest hashing; its bundle builds successfully. Native transfer and
return verification remain pending.

The bundled launch first exposed duplicate PeerCraft JARs; the fixture now removes
the original project JAR from its runtime classpath. The next launch timed out during
native local login before room creation. The fixture now caches empty native offline
profile properties to avoid unrelated Mojang property requests and fails explicitly
if local login is rejected. These adjustments need a repeated native run; the timeout
cause has not been proven by a complete thread trace.

The return assertions also include the owner's jump statistic, completed advancement,
tamed wolf ownership and final Data.Player UUID/XP. Updated compilation was not run:
automatic approval review timed out twice before starting Gradle. Explicit guidance
has been requested; no successful new build or handoff is claimed.

## 2026-10-09 compilation refresh

A separate on-disk Gradle home at `build/handoff-gradle-home` allowed an offline
build without writing to the original cache. Sandbox socket restrictions still
required approved local Gradle execution. The build used one worker and a 384 MiB
heap, with the existing Java 8 toolchain explicitly selected. Compilation exposed
an invalid HashMultimap argument to native Session.setProperties; the fixture now
uses authlib PropertyMap. The updated handoffProbeBundle completed successfully
(exit 0, 15 tasks, 2 executed). Available RAM after the preceding compile was
7,804 MiB. This proves fixture compilation only; native network transfer and
return are still pending.

## 2026-10-09 native run A

Logs: build/handoff-runs/run-20261009-a/{host,guest,server}.log. Native
host login and room creation succeeded, guest join/rejoin completed and consent
was accepted. Source PREFLIGHT then failed with NoSuchFileException: minecraft.jar;
successor reported Manifest exchange closed. No transfer/return success is claimed.
Both clients and the isolated server were stopped through their verified PIDs;
original exec handles confirmed terminal exits. MemAvailable samples ranged from
7,849 MiB before launch to 5,231 MiB with both clients, then 8,243 MiB after stopping.
The fixture now logs installed dependency IDs/parents/JARs and the loaded capture
class location before transfer to distinguish classification from stale runtime
classes on the next run. This diagnostic change has not yet been compiled.

## 2026-10-09 dependency diagnosis and run B

manifest-b.log identified mixinbooter as the actual unresolved minecraft.jar
dependency; minecraft/mcp were already skipped correctly. ForgePlatform now
resolves the synthetic MixinBooter container to its real plugin resource JAR,
without excluding it from manifest hashing. CodeSource lookup alone did not work
in LaunchWrapper; resource JarURLConnection did. manifest-d.log contains
NETWORK_HANDOFF_PROBE_MANIFEST_DONE and normal exit.

run-20261009-b reached archive transfer, successor installation, native successor
login and new room registration. The fixture then incorrectly inspected the
client ender inventory (not synced until opened). Closed successor playerdata
proves 3 gold ingots, 6 ender pearls and XpTotal 900 were preserved; the evidence
is in successor-player-evidence.json under that run directory. The fixture now
checks ender inventory on the integrated server thread and propagates peer
failure promptly. Updated fixture changes still need compilation and rerun.
Return/reconnect has not passed, and no full handoff success is claimed.
All owned processes are terminal. RAM samples: 8,155 MiB before launch,
6,581 MiB before guest launch, 5,205 MiB with both clients.

## 2026-10-09 successful transfer/return cycle

Evidence: passed-cycle-20261009/{host.log,guest.log,memory.csv}. Both native
clients reached NETWORK_HANDOFF_PROBE_DONE and exited 0. Coordination contained
owner-rejoined, return-verified and guest-done. The real SafeHandoffSession
transferred A → B, registered B's room, reconnected A, and transferred B → A.
Return assertions cover A's 7 diamonds/150 XP, jump statistic 17, root advancement,
tamed wolf owner, canonical account bindings, original-owner marker, separate
player saves and offline B's 3 gold/6 ender pearls/900 XP. Final stopped level.dat
Data.Player UUID/XP and absence of unassigned authenticated player progress passed.

A preceding attempt read client inventory before packets synchronized. Server
playerdata proved preservation. The fixture now waits at most 15 seconds for
client items/XP and reads ender inventory on the integrated server thread.

RAM guard recorded 60 samples, minimum MemAvailable 5,166 MiB, final 8,552 MiB.
Owned process groups stopped; server's cleanup exit was 143, clients exited 0.
During original-host reconnect a thread dump established a slow Mojang Patchy
BlockedServers static HTTP request. It completed before the planned manual stop,
so no kill occurred (PID already gone). Fixture HTTP timeouts added afterward
remain unverified in a repeated run.

This is a successful functional cycle, not clean-log release approval: guest.log
contains two queued packet NullPointerExceptions during disconnect. Other target
versions, observer, repeated third transfer and failure/recovery scenarios are
still unverified.

## 2026-10-09 cycle with closed-connection packet guard

passed-clean-cycle-20261009 contains both native logs and continuous memory.csv.
Both DONE markers and client exit 0 passed; no FATAL, NullPointerException or
PROBE_FAILED markers occurred. The observed stale queued packet race is guarded
in PacketThreadUtil scheduling: client play packets whose channel has closed are
not processed against an unloaded world. Active connection packets and server
handlers keep their original processing. The full transfer/return progress
assertions passed again. Fixture HTTP timeout flags were included in this run.
Startup Forge development-environment/library/narrator warnings remain; this
does not claim all warning-free production behavior or all scenario coverage.

## 2026-10-09 explicit decline

Run with PEERCRAFT_PROBE_SCENARIO=decline and a fresh PEERCRAFT_PROBE_PORT
using run_probe.py. Evidence: passed-decline-20261009. Native GuiYesNo button 1
was selected. Both clients reached DONE and exit 0; decline-verified marker
confirmed original server stayed live, room code stayed unchanged, both accounts
stayed connected, owner kept 7 diamonds/150 XP, guest kept 3 gold/900 XP and
6 ender pearls. Guest never started an integrated server. Final native save/stop
also passed separate playerdata, canonical bindings and owner Data.Player checks.

The initial decline attempt exposed SafeHandoffSession forwarding DECLINED as
onAborted. It now invokes onDeclined for that exact outcome; other failure
outcomes retain onAborted. This shared change was compiled and runtime tested
on Forge 1.12.2 only. RAM minimum 5230 MiB across 29 samples.

run_probe.py owns only its process groups, records RAM every 2 seconds, rejects
launch below 4 GiB and aborts below 3 GiB. On fixture failure it allows six seconds
for native diagnostics before cleanup, subject to the immediate RAM guard.
