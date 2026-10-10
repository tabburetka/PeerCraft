# Published 2.1.0 → current experiment upgrade verification

Status: native Forge 1.12.2 owner upgrade and repeated reopen passed; guest network and licensed authentication upgrade gates remain pending.

Downloaded CurseForge file 8817115: peercraft-neoforge-1.21.10-2.1.0.jar, published 2026-09-05.
SHA-256: f6f22b5b98041e3901327c8f3fc2281016bd5f8a29d3a1a3bb1244b77c627955.
Also recovered Forge 1.12.2 release JAR from Git commit 178deb1b; its byte identity with CurseForge has not yet been established. Artifact paths and hashes: build/upgrade-210/artifacts/manifest.json.

Required native procedure: create actual worlds and distinct owner/guest progress with the old binary, stop both clients, retain an immutable baseline copy, replace only the PeerCraft binary with the current candidate on another copy, and reopen/rejoin. Keep Minecraft and loader versions identical. Verify inventory, ender chest, XP, position, statistics, advancements, pet ownership, Data.Player, saved player UUIDs and account bindings. Cover existing authenticated accounts, anonymous guests before/after registration, unchanged licensed UUIDs, owner migration, nickname changes, destination conflicts, repeated reopen and handoff/return after upgrade. Do not infer old-version compatibility from newly generated worlds or shared unit tests.

Old release source 178deb1b shows anonymous network joiners use vanilla offline name-derived UUID, while identified joiners use PeerCraft account UUID. Therefore anonymous guest registration needs a separate explicit migration check; successful account login alone does not prove old progress preservation.

Resource policy: one target at a time, 768 MiB game heaps, bounded direct buffers and Gradle workers, MemAvailable sampling and 3 GiB abort guard. User worlds remain untouched.

## Native Forge 1.12.2 evidence, 2026-10-10

Old production classes loaded from the development JAR recovered from release commit 178deb1b (metadata 2.1.0; timestamp 2026-09-05), with current production outputs excluded from runtime classpath. The native old client created/saved the baseline world. A separate current 3.4.2 native client opened a copied baseline under a different account UUID: inventory, ender chest, XP, position, stats, root advancement and pet ownership survived. The final Data.Player UUID and saved state were verified; old playerdata bytes remained identical. The full pre-migration backup passed ZIP integrity and matched baseline level.dat/playerdata/stats/advancements bytes. A third native process reopened the migrated world and passed the same checks. All successful processes exited 0 and emitted UPGRADE_PROBE_DONE.

Evidence: passed-owner-1122-20261010/. Old run: run-20261010-101600; successful upgrade: run-20261010-101849; repeated reopen: run-20261010-102001 under build/upgrade-210. The first attempts exposed fixture exit/race and session-field errors, which were corrected; they are not successful evidence.

Scope limits: account session was injected by the fixture; this proves native owner progress migration from an actual old-created save, not real account-service authentication or guest network acceptance. Old Forge development/release JARs are from the first release commit, but byte identity of that release JAR with the CurseForge Forge upload has not been established. The downloaded published NeoForge binary is not this Forge runtime proof. Guest-before-registration, destination conflicts and actual licensed login remain separate upgrade gates.
