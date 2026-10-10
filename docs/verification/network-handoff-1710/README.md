# Forge 1.7.10 native handoff verification

## Passing native cycle, 2026-10-09

Evidence: passed-cycle-20261009/host.log, guest.log and memory.csv. Both real
clients reached NETWORK_HANDOFF_PROBE_DONE and exited 0. A → B → A includes
real local account authentication, direct P2P join/rejoin, explicit GuiYesNo
consent, archive/staging/commit, native successor hosting, room registration,
original-host reconnect and return. Assertions verify original host's 7 diamonds,
150 XP, jump statistic 17, Taking Inventory achievement (1.7.10 predates
advancements), tamed wolf owner, canonical account bindings, original-owner
marker, distinct player files and offline guest's 3 gold/6 ender pearls/900 XP.
Final stopped level.dat Data.Player UUID/XP and assigned progress checks passed.

1.7.10's client player UUID comes from the launcher Session; native server-side
player UUIDs and playerdata must instead be canonical PeerCraft account IDs.
The fixture verifies both facts separately, and server-side canonical progress
is checked on join/rejoin, successor load, original-host reconnect and return.
An initial client UUID assertion was invalid; original playerdata already had
the authenticated canonical UUID. Fixture ticks use FMLCommonHandler.bus(),
not the Forge event bus. Empty item slots are null on this version.

## Confirmed production fix

Direct GuiConnecting bypassed FMLClientHandler's play-client handshake latch
initialization. Native guest login failed with waitForPlayClient NullPointerException.
PeerCraftUi.connectLocal now uses setupServerList/connectToServer with ServerData.
Join, Multiplayer and HostMigration use this same official FML path; the fixture
also uses it. The repeated cycle had no handshake exception, NPE or FATAL markers.

Forge emits a splash diagnostic headed Minecraft Crash Report but explicitly
states it is only computer specs and not an error. Obsolete Twitch initialization
and Forge update JSON parsing warnings remain; they did not terminate the native
run. Successful functional assertions do not imply a warning-free release.

## RAM and execution

run_probe.py owns its process groups, checks MemAvailable every two seconds,
requires 4 GiB before launches and terminates only its own test processes below
3 GiB. Each Minecraft heap is 768 MiB, direct buffers 640 MiB, Gradle 384 MiB,
one worker, server 128 MiB. This run recorded 46 samples; minimum available RAM
5,381 MiB, final runner sample 8,573 MiB. All owned processes are terminal.
Only isolated temporary worlds/accounts are used.

Run with a fresh PEERCRAFT_PROBE_PORT using python3 run_probe.py. Optional
PEERCRAFT_PROBE_SCENARIO=decline passed natively on 1.7.10; evidence in passed-decline-20261009. Both DONE markers, exit 0, unchanged original room, no guest integrated server and original player progress assertions passed.
Full failure/recovery, observer, third transfer, upgrade and version matrix gates
remain pending. This passing cycle does not close those requirements.
