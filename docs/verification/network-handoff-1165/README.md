# Fabric 1.16.5 native handoff fixture

The handoffProbeBundle task compiled successfully on 2026-10-09: exit 0,
9 tasks, 5 executed. Production and fixture compile against actual named 1.16.5
Minecraft/Fabric APIs. This is compilation evidence only. No native client launch,
network handoff, return or decline is claimed yet.

The fixture mirrors 1.12.2's actual A → B → A and decline assertions, including
canonical server/player identities, items, XP, ender inventory, statistics,
advancement and pets. Fabric ClientTickEvents runs the fixture. A development JAR
bundles production and fixture classes and appends the test entry point to
fabric.mod.json. World creation uses native RegistryAccess/WorldGenSettings.
The runtime JAR/classpath configuration still requires native validation.

run_probe.py starts an isolated rendezvous server and clients one at a time;
continuous RAM guard owns only its process groups. Game heap 768 MiB/direct
640 MiB, Gradle 512 MiB/one worker, server 128 MiB. Require 4 GiB available
before launches, stop below 3 GiB. Logs and temporary worlds are on disk under
build/handoff-runs-1165. Use a fresh PEERCRAFT_PROBE_PORT. Optional
PEERCRAFT_PROBE_SCENARIO=decline selects explicit refusal.

Build command from peercraft: ./gradlew :1.16.5-fabric:handoffProbeBundle
-I ../docs/verification/network-handoff-1165/probe.gradle --offline --no-daemon
--max-workers=1 -Dorg.gradle.jvmargs=-Xmx512m. Build log currently available
at build/handoff-runs/compile-1165.log in the repository root.

## Native cycle verified, 2026-10-09

Fresh isolated run `build/handoff-runs-1165/run-20261009-204556` completed A → B → A, original-owner reconnect, distinct native progress and account UUID assertions, and final saved world checks. Both clients emitted `NETWORK_HANDOFF_PROBE_DONE` and exited 0. The return used the actual update-with-backup button. Evidence is retained in `passed-cycle-20261009/`. Earlier incomplete attempts are not successful evidence. The fixture now waits for the resource overlay to finish, checks the isolated game directory, waits for server disconnect, and handles the return reclaim screen. Other scenario groups remain pending.

The separate explicit-decline run `run-20261009-204812` passed: original host stayed active, both connected accounts retained items/XP, guest ender chest remained intact, and both clients exited 0 with DONE. Evidence: `passed-decline-20261009/`.

Repeated transfer `run-20261009-204951` passed A → B → A → B → A, with four accepted transfers, three update-with-backup selections, retained progress assertions at both returns, and both client exits 0. Evidence: `passed-repeat-20261009/`. This does not yet prove every authority epoch/restart invariant in scenario 2.

Source cancellation before successor consent passed in `run-20261009-205209`: successor consent was held, source invoked production SafeHandoffSession.cancel, original server/room remained active, both account inventories/XP and guest ender chest were checked, then saved identities and original snapshot were verified. Both clients emitted DONE. Evidence: `passed-cancel-20261009/`. Other cancellation boundaries remain untested.
