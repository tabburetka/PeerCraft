# Mod Sync version matching

Mod Sync compares literal, known version strings for the same mod ID. It installs the host's version in either direction. A matching version string is sufficient even if JAR hashes differ. Replacements require an append-only capability extension on both peers and matching Minecraft version and loader. Legacy peers retain missing-mod-only synchronization.

Every replacement is listed as `local → host` and requires confirmation, including with auto-accept enabled. Required/client-only selection and remembered optional exclusions still apply. Minecraft, the loader, Java, and PeerCraft itself are excluded. Unknown versions, ambiguous physical JARs, and target filename collisions are skipped with an explanation.

The scanner reads physical top-level JARs, including `mcmod.info` on both Forge backports. Unknown or templated versions are resolved using loader metadata only when its ID and physical JAR path match, then `Implementation-Version`. Multi-mod JARs are transferable as new files but are not automatic replacement targets.

## Applying changes

All selected files are downloaded and hash-verified before installation starts. A batch containing replacements is copied to `config/peercraft/modsync/operations/<transaction-id>/`. The helper's classes are compiled for Java 8, embedded as `/peercraft/modsync-helper.jar`, and started using the current Java executable. It waits for the game JVM's file lock to be released; no shutdown hook releases this lock early. Start Minecraft through the launcher only after the helper reports completion.

The helper rechecks hashes and filenames, copies and verifies all originals into `backups/`, and records mutation intents before changing `mods/`. The entire batch rolls back on an application error. Recovery never removes an unexpected, modified JAR. Interrupted operations retain their journal and files. On the next client initialization a retained `ARMED` operation is resumed after that game exits; `APPLYING`/`RECOVERY_REQUIRED` triggers rollback after exit. A relaunch racing the helper is rejected for further Mod Sync operations. This does not prevent the launcher or Minecraft loader from starting independently.

`plan.properties` is the durable journal; `helper.log` records helper output. `APPLIED` means the full batch was installed. `ROLLED_BACK` means the original set was restored. `RECOVERY_REQUIRED` means a conflicting or damaged file prevented automatic restoration. Do not treat a prepared transaction as installed. The next startup imports final results and backup paths into the existing `modsync-installed.json` history.

## Manual restore

Close Minecraft and let any active helper finish. Read the transaction's `RESTORE.txt` and `plan.properties`. If necessary, remove only newly installed JARs still matching their recorded SHA-512, then copy original JARs from `backups/` into `mods/` using their original filenames. Keep the backups. Do not overwrite unrelated or manually changed files. Already rolled-back operations need no further replacement. No in-game undo button or automatic backup deletion is provided.

## Checks

- Modern pure tests: version comparison, negotiation codec/loopback (including streamed manifests), inventory and scanner, confirmation policy, helper processes and rollback.
- Forge 1.7.10: the Java 8 core runs the same loopback handshake tests.
- Helper process tests use isolated temporary directories and real cross-process file locks. They do not launch Minecraft or prove a successful game connection.
- Build all modern/Fabric 1.16.5 targets and standalone Forge 1.12.2/1.7.10 distributions. Verify the embedded helper class file version is 52.

Manual acceptance on Linux and Windows remains a separate check: use matching loader and game versions, choose an upgrade then a downgrade, confirm the displayed versions, exit, wait for the helper, relaunch from the launcher, and join the host. Check that only one version is loaded, unrelated mods remain, and the old JAR can be restored from its backup. Repeat with auto-accept enabled and an unchecked optional replacement.

## Recorded verification — 2026-10-18

- `buildAll -x test`: all 30 registered modern/Fabric 1.16.5 distributions built successfully.
- Standalone Forge 1.12.2 and 1.7.10: `build` passed; final `assemble` passed.
- `:1.21.1-fabric:test`: 383 passed, 8 skipped, zero failures/errors. The helper tests used `PEERCRAFT_TEST_HELPER_JAVA` pointing to the local Temurin 8 runtime and launched the actual bundled helper JAR.
- Forge 1.7.10 `modsyncTest`: 16 passed, 1 skipped, zero failures/errors; this includes replacement negotiation and streamed-manifest cases on the Java 8 core.
- All 32 production JARs contain helper classes at class-file major 52. The helper bytecode is identical across all distributions; Stonecutter adds per-target manifest metadata.
- A separate Java 8 parent process held `game.lock`, started a Java 8 helper, verified that no replacement occurred while the parent was alive, and called `Runtime.halt(0)`. After parent exit the helper applied the replacement and the original backup was verified.
- Skips concern optional AppleSkin/coturn fixtures and rendezvous-server integration prerequisites. No manual Minecraft connection or Windows execution is claimed.

Artifact SHA-256 values and test totals are recorded in `verification.json`.
