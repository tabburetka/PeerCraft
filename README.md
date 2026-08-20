# PeerCraft

**Play Minecraft with your friends over the real internet — no dedicated server, no port forwarding, no hassle.**

PeerCraft is a Fabric mod that turns a normal singleplayer world into a multiplayer session your friends can join directly, peer-to-peer. Click "Open to LAN" like you always do, tick one checkbox, and share a 6-character code — PeerCraft handles NAT traversal (UDP hole punching) behind the scenes so your friend connects straight to you, with nobody needing to forward a router port.

On top of that, PeerCraft has its own lightweight account and friends system, so once you've added a friend you don't even need to share a code again — if they're hosting, you'll see it in your friends list and can join with one click.

> ⚠️ **Early-stage hobby project.** It works and is actively used, but it's built and maintained by one person in their spare time. Expect occasional rough edges, and please report anything broken — see [Feedback & support](#feedback--support) below.

## Features

- **Direct P2P multiplayer over the internet** — a small rendezvous server helps you and your friend find each other, then UDP hole punching connects you directly. Only the rendezvous server needs a forwarded port; neither player does.
- **One-click hosting** — open your world to LAN as usual, tick "play over the internet", and get a room code to share.
- **Accounts, with or without a Mojang license** — log in with your real Mojang account, or register a free nickname + password account if you don't own a copy of Minecraft (a "pirate" account). Both can play in the same world together.
- **Friends list with live presence** — see which friends are online or currently hosting a game, and connect to a hosting friend with a single click, no code required.
- **Friend codes** — a short 6-character code is your permanent identifier for adding friends and logging in from a new device, independent of your (non-unique) nickname.
- **Mixed licensed/unlicensed hosting** — a host can choose whether to allow unlicensed ("pirate") players into their world, and can cap the number of concurrent joiners.
- **Localized UI** — English and Russian out of the box.

## How it works

1. **Host**: load or create a singleplayer world, open the pause menu, click **Open to LAN**, tick **PeerCraft: play over the internet**, then **Start LAN World**. You'll get a short room code.
2. **Share the code** with your friend however you like (chat, Discord, voice call) — or skip this step entirely once you're friends in-game, see below.
3. **Joiner**: from the title screen, open **Multiplayer**, and either paste the room code under the join-by-code button, or open the **Friends** tab and click **Connect** next to a friend who's currently hosting.
4. PeerCraft negotiates the connection in the background and drops you straight into your friend's world.

No dedicated server, no always-on hosting machine, no router configuration on either player's end.

## Requirements

- Minecraft **1.21.1**
- [Fabric Loader](https://fabricmc.net/use/) **0.19.3** or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 21

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.1.
2. Download **Fabric API** and **PeerCraft** and drop both `.jar` files into your `.minecraft/mods` folder.
3. Launch the game using the Fabric profile.

## FAQ

**Do I need to forward any ports?**
No — as a player (host or joiner) you never need to touch your router. Only the person running the shared rendezvous server needs a forwarded UDP port, and PeerCraft already points at a public one by default.

**Can players without a Mojang license (non-premium) play?**
Yes. They register a nickname + password account in-game instead of logging in with Mojang. A host can choose whether to allow this.

**Why did hole punching fail / why can't we connect?**
Most commonly one side is on a network type that blocks direct peer-to-peer connections (symmetric NAT, or carrier-grade NAT on mobile data). There's no relay fallback in the current version — if punching fails, try a different network (e.g. switch off mobile data / try a different Wi-Fi).

**Is this safe to use with a real Mojang account?**
PeerCraft never asks for or stores your Mojang password — licensed login uses the same session-verification method Mojang's own multiplayer uses. That said, this is a hobby project without a security audit, so avoid reusing sensitive passwords for a pirate account, and treat it as you would any small independent mod.

## Known limitations

- No relay/TURN fallback when direct hole punching fails.
- Only one host per session (a star topology, not a full mesh) — everyone connects through the host.
- No moderation or ban system yet.
- No account recovery if you lose your friend code.

## Feedback & support

Found a bug, or have an idea? Use the **Feedback** button on the title screen (opens an email to `peercraft2@gmail.com`), or [open an issue on GitHub](https://github.com/tabburetka/PeerCraft/issues).

If you'd like to support development, there's a **Donate** button on the title screen too.

## License

Released under [CC BY-NC 4.0](LICENSE) — free to use, modify, and share with attribution, for **non-commercial purposes only**. You may not sell PeerCraft, or any modpack/derivative that includes it, without the author's permission.

---

## Development

The rest of this document is for people building or contributing to PeerCraft, not for players.

### Repository layout

Two independent Gradle projects in one repo:

- `peercraft/` — the Fabric mod itself (Minecraft 1.21.1, Java 21, Fabric Loom, official Mojang mappings). Build with `cd peercraft && ./gradlew build`.
- `rendezvous-server/` — the standalone UDP rendezvous server (no Minecraft/Loom dependency). Build with `cd rendezvous-server && ./gradlew jar`.

### Configuration

The mod reads Java system properties first and environment variables second. If a value is not set, the default is used. None of these are needed for normal play — the in-game UI (Join screen, Open to LAN checkbox) drives everything live; these are mainly useful for dev testing or an always-hosting headless machine.

| System property | Environment variable | Default | Description |
| --- | --- | --- | --- |
| `peercraft.mode` | `PEERCRAFT_MODE` | `auto` | `auto` (the default) dynamically becomes client or host role based on what you click in-game. `client`/`host` pin a single role; `disabled` turns PeerCraft off entirely. |
| `peercraft.proxyPort` | `PEERCRAFT_PROXY_PORT` | `25566` | Local TCP port that the joining Minecraft client connects to. |
| `peercraft.clientUdpPort` | `PEERCRAFT_CLIENT_UDP_PORT` | `50002` | UDP port listened to by the joining client side of the bridge. |
| `peercraft.hostUdpPort` | `PEERCRAFT_HOST_UDP_PORT` | `50001` | UDP port listened to by the host side of the bridge after Open to LAN. |
| `peercraft.peerHost` | `PEERCRAFT_PEER_HOST` | `127.0.0.1` | Remote UDP peer address for the static (non-internet) path — used for local two-client testing. |
| `peercraft.peerPort` | `PEERCRAFT_PEER_PORT` | host: `clientUdpPort`, client: `hostUdpPort` | Remote peer UDP port on the static path. |
| `peercraft.internetPlay` | `PEERCRAFT_INTERNET_PLAY` | `false` | Initial state of the "play over the internet" checkbox on Open to LAN. |
| `peercraft.rendezvousHost` | `PEERCRAFT_RENDEZVOUS_HOST` | the project's public rendezvous server | Rendezvous server address, overridable in-game via "Override server address" on the Join screen. |
| `peercraft.rendezvousPort` | `PEERCRAFT_RENDEZVOUS_PORT` | `51000` | UDP port of the rendezvous server. |
| `peercraft.roomCode` | `PEERCRAFT_ROOM_CODE` | *(empty)* | Pre-fills the room-code box on the in-game Join screen. |

### Local self-connect test

Run the two Minecraft instances from separate working directories (`peercraft/run-host/` and `peercraft/run-client/`) so their `logs/latest.log` files don't interleave — the IDE run configs `Minecraft Client (host)` and `Minecraft Client (join)` are already set up this way.

1. Build the mod with `cd peercraft && ./gradlew build`.
2. Start the **host** instance (`Minecraft Client (host)`, working dir `run-host/`). Create or load a single-player world.
3. Click **Open to LAN** (leave "play over the internet" unticked for this local test). `run-host/logs/latest.log` should confirm the host is ready and listening on UDP `50001`.
4. Start the **client** instance (`Minecraft Client (join)`, working dir `run-client/`). `run-client/logs/latest.log` should confirm the client is ready, local proxy port `25566`, UDP listen port `50002`.
5. In the client instance, open **Multiplayer** and connect to `127.0.0.1:25566` (the proxy port, not the LAN port Minecraft shows).
6. Confirm a clean login with no packet-decode errors in `run-host/logs/latest.log`.
7. If either port is busy, change both sides consistently, e.g. `-Dpeercraft.hostUdpPort=50101 -Dpeercraft.clientUdpPort=50102` on both.

### Internet play (rendezvous server + hole punching)

For two players on different networks, PeerCraft uses the standalone rendezvous server (`rendezvous-server/`) to let host and joiner discover each other's public UDP address, then attempts direct UDP hole punching. Once punching succeeds, everything downstream is the same relay protocol validated locally above, just talking to a real remote address instead of `127.0.0.1`.

**v1 scope, by design**: no persistent lobbies beyond the accounts/friends system, single-use 6-character room codes, no relay/TURN fallback if hole punching fails (a home-hosted rendezvous server's uplink can't sustain relaying full game traffic for multiple pairs). Only the rendezvous server itself needs a forwarded port.

#### Running your own rendezvous server

```
cd rendezvous-server
./gradlew jar
```

Copy `rendezvous-server/build/libs/rendezvous-server-*.jar` to whatever machine will host it and run it there:

```
java -jar rendezvous-server-1.0.0.jar 51000
```

Forward UDP port `51000` (or whatever you pass as the argument) on that machine's router to its LAN IP, and note its public IP or DDNS hostname. Point the mod at it with `-Dpeercraft.rendezvousHost=<your-server>`.

### Tests

- `peercraft`: `cd peercraft && ./gradlew test` — unit and integration tests for the P2P relay, rendezvous protocol, and accounts/friends stack.
- `rendezvous-server`: `cd rendezvous-server && ./gradlew test` — unit and integration tests for the room registry, protocol, and accounts service, exercised over raw UDP with no Minecraft dependency.
- End-to-end tests that spin up a real rendezvous-server subprocess self-skip if the sibling project's jar hasn't been built yet — build it first with `cd rendezvous-server && ./gradlew jar`.
