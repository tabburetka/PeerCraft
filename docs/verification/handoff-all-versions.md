# Handoff runtime verification matrix

Scope is every configured Minecraft/loader target, taken from peercraft/settings.gradle.kts plus the two standalone Forge projects. Compilation and shared protocol tests do not satisfy native runtime gates. A native Forge 1.12.2 transfer/return cycle is recorded; full scenario and target coverage remains pending.

## Required scenarios for each target

1. A → B transfer: explicit consent, verified archive, authority commit, successor startup and room registration, original host reconnect.
2. B → A return, then another transfer; stable world ID, fresh epoch, room unlock, no duplicate active authority.
3. A, B and an observer: only the chosen successor launches; all reconnect to the registered room.
4. Distinct player inventory, ender chest, XP, position, stats, advancements, pets and UUID bindings survive both directions; same display names stay isolated.
5. Decline consent and cancel before commit; original world remains usable.
6. Missing/incompatible mod, corrupt/incomplete archive and installation failure; no partial replacement of an existing save.
7. Disconnect or process exit during snapshot, upload, staging, commit and successor startup; restart and inspect recovery at each boundary.
8. Lost COMMIT reply, lost ROOM_READY, rendezvous interruption and unresolved authority; retain journals and saves until an authoritative outcome.
9. Confirmed abort cleans only attempt-owned scratch files; unrelated worlds and backups remain intact.
10. Restart either participant after successful commit; former host cannot open a stale writable copy, successor retains progress.
11. Existing world from the previous mod release and anonymous-to-account transition; conflict handling and backups preserve original progress.

Each scenario requires native logs, exit status, world assertions and memory samples. Failures remain failures until reproduced successfully after a fix. Automated transport failure injection is useful evidence but does not replace native process interruption.

## Memory policy

Run one target at a time. Begin with two clients; use three only for the observer case. Cap game heap at 768 MiB, direct memory at 640 MiB, Gradle at 384 MiB with one worker, isolated server at 128 MiB. Reassess limits if a particular version needs more memory. Sample host MemAvailable and process RSS before launch and throughout the run. Do not start another client below 4 GiB MemAvailable. If availability falls below 3 GiB, end owned test processes gracefully and preserve logs/journals. Never stop unrelated applications. Use temporary worlds/accounts. Successful compilation alone is not runtime proof.

## Current coverage

Forge 1.12.2 completed a native A → B → A cycle with original-host reconnect, progress/UUID checks and RAM logging. Observed guest disconnect packet errors were fixed and absent in a repeated full cycle. Forge 1.7.10 also passed a native transfer/return cycle after fixing the FML connection entry point. Fabric 1.16.5 passed the native transfer/return cycle with the update-with-backup return choice. Other targets have no native scenario completion recorded here.

| Target | Scenarios 1–11 | Evidence |
| --- | --- | --- |
| 1.7.10-forge | Transfer/return and explicit decline passed; remaining scenarios pending | network-handoff-1710/passed-cycle-20261009 |
| 1.12.2-forge | Transfer/return and explicit decline passed; observed disconnect errors resolved; remaining scenarios pending | network-handoff-1122/passed-cycle-20261009 |
| 1.21.1-fabric | Transfer/return and repeated cycle passed, stable world ID and epochs 1–4 verified; remaining scenarios pending | network-handoff-1211/passed-cycle-20261009 |
| 1.21.1-neoforge | Pending | — |
| 1.21.2-fabric | Fixture compiled; native scenarios pending | network-handoff-1212 |
| 1.21.3-fabric | Pending | — |
| 1.21.3-neoforge | Pending | — |
| 1.21.4-fabric | Pending | — |
| 1.21.4-neoforge | Pending | — |
| 1.21.5-fabric | Pending | — |
| 1.21.5-neoforge | Pending | — |
| 1.21.6-fabric | Pending | — |
| 1.21.6-neoforge | Pending | — |
| 1.21.7-fabric | Pending | — |
| 1.21.7-neoforge | Pending | — |
| 1.21.8-fabric | Pending | — |
| 1.21.8-neoforge | Pending | — |
| 1.21.9-fabric | Pending | — |
| 1.21.9-neoforge | Pending | — |
| 1.21.10-fabric | Pending | — |
| 1.21.10-neoforge | Pending | — |
| 1.21.11-fabric | Pending | — |
| 1.21.11-neoforge | Pending | — |
| 1.16.5-fabric | Transfer/return, repeated A → B → A → B → A, explicit decline and source cancellation before consent passed; remaining scenarios pending | network-handoff-1165/passed-cycle-20261009 |
| 26.1-fabric | Pending | — |
| 26.1-neoforge | Pending | — |
| 26.1.1-fabric | Pending | — |
| 26.1.1-neoforge | Pending | — |
| 26.1.2-fabric | Pending | — |
| 26.1.2-neoforge | Pending | — |
| 26.2-fabric | Pending | — |
| 26.2-neoforge | Pending | — |
