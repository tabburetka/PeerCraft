# Native world progress probe, Forge 1.12.2

From `peercraft-forge-1122`, run:

```
./gradlew -I ../docs/verification/world-progress-1122/probe.gradle runClient
```

Requires a graphical Linux session. Each run creates a fresh game directory under
`/tmp/peercraft-world-probe/`, sets PeerCraft P2P mode to disabled and points account
traffic at loopback port 1. The test uses synthetic session UUIDs with the same
Minecraft name. It creates and changes only its own new flat creative worlds.

The probe:

1. Creates anonymous progress: seven diamonds, thirteen emeralds in the ender chest,
   150 XP, fixed position, seventeen jumps, completed root advancement and a tamed wolf.
2. Closes through production WorldArchiver.saveAndStop, sets an account fixture,
   reopens and checks native server/player state. Confirms source bytes remain and
   account playerdata, vanilla Data.Player and a full world backup exist.
3. Archives and installs with production WorldArchiver.archiveClosed and WorldInstall.
   Opens as a different account with the same Minecraft name. Confirms no former host
   inventory/ender chest/XP is inherited and the new account is bound.
4. Creates separate successor progress, closes, archives/installs the return copy and
   opens as the former host. Confirms its items, XP, stats, achievement, original owner
   marker, and unchanged offline successor NBT. Neither account appears anonymous.

`WORLD_PROGRESS_PROBE_DONE` and normal process exit are the success markers.
`passed-run.log` records the verified run. This covers real Minecraft save/load and
native account identity selection, using the production file/stop pipeline. It does
not perform account authentication, a two-process network handoff, or mod removal.

## Real account registration mode

Start a local rendezvous server with a separate temporary data directory, then add
`-Dpeercraft.probe.accountPort=39847` to the Gradle invocation (use its actual port).
The probe registers two unlicensed accounts through the production AccountClient,
retains their returned sessions and runs the same native save/load checks with the
server-issued UUIDs. Both use the same display name. The anonymous world is opened
with no account session; only its subsequent reopen uses the registered account.
`WORLD_PROBE_AUTHENTICATED accounts=2` confirms both registration callbacks.

`authenticated-run.log` records a successful run with real local registration and
`WORLD_PROGRESS_PROBE_DONE`. World transfer still uses the production archive/install
pipeline within one client process; it does not prove two-client network handoff.
