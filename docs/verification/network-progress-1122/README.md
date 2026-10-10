# Native network progress probe, Forge 1.12.2

This test launches two separate Minecraft processes and a local rendezvous server.
All worlds and server account records belong to fresh directories under `/tmp`.
The production AccountClient registers separate unlicensed accounts; the production
P2PBridge joins the guest through rendezvous, direct transport and LocalProxy.

Build the rendezvous jar, then start it with a separate data directory and a free
UDP port. From `peercraft-forge-1122`, launch both roles with the same unused
coordination directory and server port:

```
./gradlew -Dpeercraft.probe.role=host -Dpeercraft.probe.coordination=/tmp/peercraft-network-probe/NEW-RUN -Dpeercraft.probe.accountPort=39848 -I ../docs/verification/network-progress-1122/probe.gradle runClient
./gradlew -Dpeercraft.probe.role=guest -Dpeercraft.probe.coordination=/tmp/peercraft-network-probe/NEW-RUN -Dpeercraft.probe.accountPort=39848 -I ../docs/verification/network-progress-1122/probe.gradle runClient
```

Requires a graphical session. Both account display names are Developer; Minecraft
launcher names are Developer and GuestDeveloper. Forge 1.12.2 rejects simultaneous
connections sharing the same launcher name (`That name is already taken`), even
when their PeerCraft account UUIDs differ. The first run confirmed this rejection;
sequential same-name ownership is covered by the separate world-progress probe.

## Memory constraint

Concurrent Gradle/game runs exhausted the desktop's memory during development.
The affected runs are incomplete and provide no successful network-progress proof.
Both owned clients and the local test server were stopped after the report.
Each probe client now has an explicit 768 MiB heap ceiling. This change is not yet
runtime verified. Do not repeat the two-client launch on the user's desktop until
the complete process budget (Gradle, native buffers, both clients and desktop apps)
has been checked. Compile sequentially with one worker and a bounded Gradle heap;
avoid creating multiple Gradle daemons. A separate test machine is preferable for
the remaining concurrent game gate.

The host checks actual native account UUIDs, writes separate items/XP, then the guest
disconnects and joins the same room again. The host checks both native states and
the guest's ender chest. After confirmed production shutdown it checks separate
playerdata and canonical guest binding. The coordination files contain only public
UUIDs, room code and test status, never session tokens or passwords.

Success requires `NETWORK_PROGRESS_PROBE_DONE` for both roles and normal exits.
This probe does not yet execute host handoff or email recovery.

## Verified run

`passed-host.log` and `passed-guest.log` record successful native join and reconnect
with separate server-issued UUIDs and restored guest inventory/ender chest/XP.
The host's progress stayed distinct, and canonical account metadata excluded both
saves from anonymous reassignment. Both processes exited normally.
The guest log also contains four queued entity-packet null-pointer errors after
the fixture immediately unloaded the world on disconnect. Progress assertions
and reconnect passed, but this is not a clean disconnect log. The fixture now lets
the native connection lifecycle unload the world before advancing; that adjustment
still needs a repeated run.

Run with `--no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx384m'` in addition to
the arguments above, starting the guest only after the host room exists and available
memory is checked. The fixture uses 768 MiB heap, 640 MiB direct-buffer ceiling,
two CPU workers and view distance four. Available system memory exceeded 4 GiB
during this run. All owned test processes were stopped afterward.

The preceding failure was SPacketLoginSuccess encoded after Forge synchronously
switched the channel to PLAY. The Forge 1.12.2 login adapter now queues the verified
PeerCraft connection's Forge handshake after the existing login-success write on
the channel event loop. The successful run includes this fix; local-channel and
unidentified vanilla connections retain their original handshake path.
