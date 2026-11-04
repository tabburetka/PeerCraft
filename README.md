# PeerCraft

**Play Minecraft with your friends over the real internet — no dedicated server, no port forwarding, no hassle.**

PeerCraft is a client mod — **Fabric, NeoForge, or Forge** — that turns a normal singleplayer world into a multiplayer session your friends can join directly, peer-to-peer. Click "Open to LAN" like you always do, tick one checkbox, and share a 6-character code — PeerCraft handles NAT traversal (UDP hole punching) behind the scenes so your friend connects straight to you, with nobody needing to forward a router port.

On top of that, PeerCraft has its own lightweight account and friends system, so once you've added a friend you don't even need to share a code again — if they're hosting, you'll see it in your friends list and can join with one click.

> ⚠️ **Early-stage hobby project.** It works and is actively used, but it's built and maintained by one person in their spare time. Expect occasional rough edges, and please report anything broken — see [Feedback & support](#feedback--support) below.

## 3.4.2 Beta

See the [changes since 2.1.0](docs/releases/3.4.2-beta.md) and [release JARs, checksums and validation](releases/3.4.2-beta/README.md). This beta adds persistent in-game settings, a redesigned interface, experimental host handoff and player progress migration. Back up your world before upgrading or testing handoff. Email password recovery is not ready for general use; relay/TURN remains disabled.

## Features

- **Direct P2P multiplayer over the internet** — a small rendezvous server helps you and your friend find each other, then UDP hole punching connects you directly. Players normally need no port forwarding, provided their networks allow a working direct UDP route.
- **One-click hosting** — open your world to LAN as usual, tick "play over the internet", and get a room code to share.
- **Accounts, with or without a Mojang license** — log in with your real Mojang account, or register a free nickname + password account if you don't own a copy of Minecraft (a "pirate" account). Both can play in the same world together.
- **Friends list with live presence** — see which friends are online or currently hosting a game, and connect to a hosting friend with a single click, no code required.
- **Public game browser** — tick "Open to Everyone" instead of (or besides sharing) a code, and your world shows up in the **Games** tab for any player with the mod, no friendship required (account login is required), like a lobby browser. Each listing shows the host's Minecraft version (vanilla only lets same-version clients connect), and the tab has a search box (world name) and a version filter.
- **Friend codes** — a short 6-character code is your permanent identifier for adding friends and logging in from a new device, independent of your (non-unique) nickname.
- **Mixed licensed/unlicensed hosting** — a host can choose whether to allow unlicensed ("pirate") players into their world, and can cap the number of concurrent joiners.
- **In-game settings** — open Multiplayer → ⚙ to configure hosting, joining, player limits and independent mod synchronization modes.
- **Experimental host handoff** — offer another player the world and hosting role, with archive verification and recovery tracking.
- **Player progress migration** — preserve account-based player data, with explicit host-approved migration of old guest saves and backups.
- **Account recovery cards** — sign in with your full account UUID and existing password if you lose your friend code.
- **Localized UI** — English and Russian out of the box.

## How it works

1. **Host**: load or create a singleplayer world, open the pause menu, click **Open to LAN**, tick **PeerCraft: play over the internet**, then **Start LAN World**. You'll get a short room code.
2. **Share the code** with your friend however you like (chat, Discord, voice call) — or skip this step entirely once you're friends in-game, see below.
3. **Joiner**: sign into your PeerCraft account, then from the title screen, open **Multiplayer**, and either paste the room code under the join-by-code button, or open the **Friends** tab and click **Connect** next to a friend who's currently hosting.
4. PeerCraft negotiates the connection in the background and drops you straight into your friend's world.

No dedicated Minecraft server is needed. The host must keep the world open, and both networks must allow a direct UDP connection; automatic NAT traversal cannot work through every NAT or firewall.

## Requirements

PeerCraft ships **one jar per Minecraft version per loader**. Download the file that matches the Minecraft version *you* play and the loader you run — the file name spells it out, e.g. `peercraft-fabric-1.21.8-3.4.2.jar` or `peercraft-forge-1.12.2-3.4.2.jar`.

- **Minecraft** — the main builds target **1.21.1 – 1.21.11** and **26.1 – 26.2**. Older versions (**1.16.5**, **1.12.2**, **1.7.10**) are covered by separate experimental backports; see the table below.
- **Loader**
  - **Fabric** — [Fabric Loader](https://fabricmc.net/use/) **0.19.3** or newer, plus [Fabric API](https://modrinth.com/mod/fabric-api).
  - **NeoForge** — [NeoForge](https://neoforged.net/). No extra API mod.
  - **Forge** (1.12.2 / 1.7.10 backports only) — plus a Mixin loader: [MixinBooter](https://www.curseforge.com/minecraft/mc-mods/mixin-booter) on 1.12.2, [UniMixins](https://www.curseforge.com/minecraft/mc-mods/unimixins) on 1.7.10.
- **Java** — 21 for the 1.21.x builds, 25 for the 26.x builds, 8 for the 1.16.5 / 1.12.2 / 1.7.10 backports (the same Java those game versions already need).
- **Both players run the same Minecraft version.** Minecraft's own protocol only connects same-version clients, so you and your friend must be on the same MC version. Different loaders can work only when the world and installed mods are compatible. Mod sync cannot supply missing mods across different loaders.

### Version & loader support

| Minecraft | Fabric | NeoForge | Forge |
|---|---|---|---|
| 1.21.1, 1.21.3 – 1.21.5, 1.21.8, 1.21.10, 1.21.11 | ✅ | ✅ | — |
| 1.21.6, 1.21.7, 1.21.9 | ✅ | ✅ (`-beta`-tagged build only — still a real, working release, just never marked stable) | — |
| 1.21.2 | ✅ | ❌ (NeoForge never released a build for this version — it moved straight to 1.21.3, out the next day) | — |
| 26.1.2, 26.2 | ✅ | ✅ | — |
| 26.1, 26.1.1 | ✅ | ✅ (`-beta`-tagged build only — same as 1.21.6/1.21.7/1.21.9) | — |
| 1.16.5 | 🧪 | — | — |
| 1.12.2 | — | — | 🧪 (needs MixinBooter) |
| 1.7.10 | — | — | 🧪 (needs UniMixins) |

**✅** released &nbsp;·&nbsp; **🧪** experimental backport — it builds and loads, but it hasn't been play-tested as thoroughly as the 1.21.x / 26.x builds; expect rough edges and please [report anything broken](#feedback--support) &nbsp;·&nbsp; **❌ / —** that loader was never released for that Minecraft version (a limitation of the loader, not of PeerCraft)

> **"There's a 1.7.10 (or 1.12.2) file, but the page also says 1.21 — which is it?"**
> Both. The main builds are for modern Minecraft (1.21.x / 26.x on Fabric and NeoForge); the 1.7.10 and 1.12.2 files are experimental Forge backports for players still on those versions. Download whichever matches your game — PeerCraft does **not** run on every version in between, only the ones in the table above.

A Fabric-only **1.16.5** experimental backport is included in the 3.4.2 beta.

Minecraft 26.1 was the first release with unobfuscated code; the Fabric 26.x jars are built without mappings against Minecraft as shipped.

## Installation

1. Install the loader for your Minecraft version: [Fabric Loader](https://fabricmc.net/use/), [NeoForge](https://neoforged.net/), or [Forge](https://files.minecraftforge.net/) (1.12.2 / 1.7.10).
2. Put the mods into `.minecraft/mods`:
   - **Fabric** — [Fabric API](https://modrinth.com/mod/fabric-api) + the `peercraft-fabric-<version>` jar.
   - **NeoForge** — the `peercraft-neoforge-<version>` jar. No separate API mod.
   - **Forge 1.12.2** — [MixinBooter](https://www.curseforge.com/minecraft/mc-mods/mixin-booter) + the `peercraft-forge-1.12.2-<version>` jar.
   - **Forge 1.7.10** — [UniMixins](https://www.curseforge.com/minecraft/mc-mods/unimixins) + the `peercraft-forge-1.7.10-<version>` jar.
3. Launch the game using the matching profile. Your friend needs PeerCraft too, on the same Minecraft version.

## FAQ

**Which Minecraft versions does it actually work on? I saw a 1.7.10 file but the page mentions 1.21.**
Both, plus more. The main builds are for Minecraft 1.21.x and 26.x (Fabric and NeoForge). Separately, there are experimental Forge backports for 1.12.2 and 1.7.10, and an experimental 1.16.5 Fabric backport. Download the file whose name matches the version you play — it does **not** run on every version in between. See the [support table](#version--loader-support) for the exact list.

**Can my friend and I be on different Minecraft versions / different loaders?**
Different versions, no — Minecraft's networking only connects same-version clients, so you must both be on the same Minecraft version. Different loaders can work when the world and installed mods are compatible, but this is not guaranteed for modded worlds. Mod sync rejects a loader mismatch when there are missing mods to transfer.

**Do I need to forward any ports?**
Usually no: PeerCraft attempts direct UDP NAT traversal and points at a public rendezvous server by default. Some NAT and firewall configurations still block the connection. A self-hosted rendezvous server must be reachable on its UDP port; it does not forward game traffic or bypass those restrictions.

**Can players without a Mojang license (non-premium) play?**
Yes. They register a nickname + password account in-game instead of logging in with Mojang. A host can choose whether to allow this.

**Why did hole punching fail / why can't we connect?**
Some NAT and firewall configurations prevent direct peer-to-peer connectivity. Current builds require a working direct UDP route. When both peers negotiate the newer direct-check protocol, they check supported routes for up to 20 seconds; if those checks fail, PeerCraft shows a dialog suggesting to turn off any VPN and retry. If that does not help, one of the two players can run a **self-hosted relay** ([peercraft-relay](peercraft-relay/README.md)): a small program on a PC with a forwarded UDP port or on a VPS. Paste its relay code into PeerCraft settings (or use **Own relay…** in the error dialog); the other player needs nothing. The relay only forwards packets that are encrypted end to end between the players. The PeerCraft-operated TURN fallback stays disabled in both the client and server code, including the Forge backports. A hole-punching error alone does not identify the exact NAT or firewall responsible.

**Is this safe to use with a real Mojang account?**
PeerCraft never asks for or stores your Mojang password — licensed login uses the same session-verification method Mojang's own multiplayer uses. That said, this is a hobby project without a security audit, so avoid reusing sensitive passwords for a pirate account, and treat it as you would any small independent mod.

## Known limitations

- Direct UDP connectivity is required. TURN fallback is disabled in the client and server code; configuration alone cannot enable it. Relay implementation remains experimental and awaits closed real-network/gameplay validation. The rendezvous control channel also uses UDP.
- Only one host per session (a star topology, not a full mesh) — everyone connects through the host.
- No moderation or ban system yet.
- Email password recovery is not ready for general use. If only your friend code is lost, use your recovery account ID and existing password.

## Feedback & support

Found a bug, or have an idea? Use the **Feedback** button on the title screen (opens an email to `peercraft2@gmail.com`), or [open an issue on GitHub](https://github.com/tabburetka/PeerCraft/issues).

If you'd like to support development, there's a **Donate** button on the title screen too.

## License

Released under [CC BY-NC 4.0](LICENSE) — free to use, modify, and share with attribution, for **non-commercial purposes only**. You may not sell PeerCraft, or any modpack/derivative that includes it, without the author's permission.

---

## Development

The rest of this document is for people building or contributing to PeerCraft, not for players.

Player identity, one-time save migration and vanilla compatibility during host handoff are documented in [player-data-migration.md](peercraft/docs/player-data-migration.md).

### Repository layout

Several independent Gradle projects in one repo:

- `peercraft/` — the mod itself (Fabric + NeoForge, Minecraft 1.21.1–1.21.11 and 26.1–26.2, Java 21/25, a Stonecutter multi-version build), plus an experimental Fabric-only **1.16.5** backport target (Java 8, its own `build.fabric-1165.gradle.kts`). Build one target with `cd peercraft && ./gradlew :1.21.1-fabric:build`, or the whole matrix with `./gradlew buildAll`.
- `rendezvous-server/` — the standalone UDP rendezvous server (no Minecraft/Loom dependency). Build with `cd rendezvous-server && ./gradlew jar`.
- `peercraft-relay/` — the self-hosted relay players can run when direct P2P fails (Java 17, no dependencies, plus a Dockerfile). Its wire codec is compiled from the mod's own `peercraft/src/main/java/net/peercraft/network/ownrelay/OwnRelayProtocol.java`. Build with `cd peercraft-relay && ./gradlew jar`; `./export-standalone.sh DIR` produces the public standalone repository layout. Player-facing instructions are in its [README](peercraft-relay/README.md).
- `peercraft-forge-1122/` — a Minecraft **1.12.2 Forge** backport, built on RetroFuturaGradle (Gradle 8.8, Java 8) instead of Stonecutter/Loom. It reuses the shared networking/account code straight from `peercraft/src/` via `srcDir` (with hand-resolved Java-8 / 1.12.2-API twins under `peercraft/src/shared-forge1122/` and `peercraft/src/client-1122/`). Build with `cd peercraft-forge-1122 && ./gradlew build` (first run is slow — it decompiles Minecraft) → `build/libs/peercraft-forge-1.12.2-<version>.jar`; try it with `./gradlew runClient`. Requires [MixinBooter](https://www.curseforge.com/minecraft/mc-mods/mixin-booter) at runtime for the two server-side mixins (the GUI hooks are plain Forge events). The mod ships a coremod (`PeerCraftCoreMod`) that registers its mixin config, so it does **not** need to be extracted. This backport builds and loads; it's not yet as thoroughly runtime-tested as the 1.21.x / 26.x targets.
- `peercraft-forge-1710/` — a Minecraft **1.7.10 Forge** backport, same toolchain as the 1.12.2 one (RetroFuturaGradle, Gradle 8.8, Java 8) and ported almost file-for-file from it — 1.7.10 and 1.12.2 share the pre-1.13 immediate-mode GUI (`GuiScreen` + `buttonList`), so most of the work was mechanical API renames (`fontRendererObj`, the `cpw.mods.fml.*` package, MCP `stable_12` names). It reuses the shared code from `peercraft/src/` via `srcDir` with twins under `peercraft/src/shared-forge1710/` and `peercraft/src/client-1710/`, and the loader-agnostic mod-sync core from `peercraft/src/modsync-java8/` (shared with the 1.12.2 backport). Build with `cd peercraft-forge-1710 && ./gradlew build` → `build/libs/peercraft-forge-1.7.10-<version>.jar`; try it with `./gradlew runClient`. Requires [UniMixins](https://www.curseforge.com/minecraft/mc-mods/unimixins) at runtime for the two server-side mixins (the GUI hooks are plain Forge events). Mod sync is backported here too — Modrinth HTTPS CDN transfer for exact SHA-512 matches, with P2P fallback, reading `mcmod.info` for the jar list; `./gradlew :peercraft-forge-1710:modsyncTest` runs a Minecraft-free loopback check of the handshake + transfer. The `build` (including the mixin refmap) is green; runtime application of the mixins is not yet verified.

### Configuration

The mod reads Java system properties first, environment variables second, then the values saved by the in-game **PeerCraft Settings** screen (`config/peercraft/settings.json`), then the built-in default. An explicit `-Dpeercraft.*` / `PEERCRAFT_*` always wins over a saved value. None of these are needed for normal play — the in-game UI (Join screen, Open to LAN checkbox, Settings screen) drives everything live; the launch flags are mainly useful for dev testing or an always-hosting headless machine.

Open the settings screen from the gear icon on the PeerCraft multiplayer screen (**Multiplayer → ⚙**). `mode`, `modSync.autoAccept`, the rendezvous address and the port flags are kept behind a collapsed **Для разработчиков** section since a wrong value there can disable the mod or break connections. On the 1.12.2 / 1.7.10 backports the screen covers the toggle/cycle flags (mod-sync modes, `autoAccept`, `mode`, size limits, max players); the free-text and port flags there remain launch-flag only.

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
| `peercraft.reorder.maxPending` | *(system property only)* | `4096` | How many out-of-order datagrams the receiver buffers behind one missing packet before it gives up and drops the session. Raise it on a very lossy or DPI-mangled link (see below); range `64`–`1048576`. |
| `peercraft.reorder.gapTimeoutMillis` | *(system property only)* | `12000` | How long the receiver waits for one missing packet (while re-requesting it) before dropping the session. Kept under Minecraft's own 30 s read timeout; range `1000`–`600000`. |
| `peercraft.reorder.nackDebounceMillis` | *(system property only)* | `120` | Minimum gap between repeat resend-requests for the same missing packet; range `10`–`5000`. |
| `peercraft.send.pacingMillis` | *(system property only)* | `0` | Delay inserted between the datagrams of one large payload. `0` sends them in a burst (original behavior); `1`–`2` spreads world-sync traffic so a congested uplink drops fewer packets, at the cost of slightly slower bulk transfer. Max `50`. |
| `peercraft.modSync.host` | `PEERCRAFT_MOD_SYNC_HOST` | `all` | What you share with players joining **your** world: `off` (no mod sync for joiners), `required` (only mods needed to join — client+server and server-only), `all` (every non-excluded mod). |
| `peercraft.modSync.client` | `PEERCRAFT_MOD_SYNC_CLIENT` | `all` | What you download when joining **someone else's** world: `off` (never run the handshake), `required` (only mods needed to join; client-only mods are auto-skipped), `all`. Independent of `modSync.host`. |
| `peercraft.modSync.removeExtra` | `PEERCRAFT_MOD_SYNC_REMOVE_EXTRA` | `off` | Mods you have that the host doesn't: `off` (keep everything), `keep_client` (offer to remove them, but keep purely client-side mods), `all` (offer to remove all of them). Only when loader and Minecraft version match the host; each removal is confirmed per mod, applied after the game closes and backed up. |
| `peercraft.modSync` | `PEERCRAFT_MOD_SYNC` | `true` | Legacy master switch. Still read as the default for `modSync.host` / `modSync.client` when those are unset: `false` makes both `off`. |
| `peercraft.modSync.autoAccept` | `PEERCRAFT_MOD_SYNC_AUTO_ACCEPT` | `false` | Skip confirmation only when all selected jars exactly match public Modrinth files; otherwise show the confirmation screen. Opt-in — only sensible for a closed group of trusted friends. |
| `peercraft.modSync.maxTotalMb` | `PEERCRAFT_MOD_SYNC_MAX_TOTAL_MB` | `512` | Reject a mod-sync batch whose jars total more than this many MiB, before any download starts. |
| `peercraft.modSync.maxModMb` | `PEERCRAFT_MOD_SYNC_MAX_MOD_MB` | `256` | Reject / abort any single jar larger than this many MiB. |
| `peercraft.modSync.reofferDeclined` | `PEERCRAFT_MOD_SYNC_REOFFER_DECLINED` | `false` | Always show the confirmation screen when anything is missing, even mods you unchecked before. The way back to a client-side mod you previously turned down (otherwise delete its line from `config/peercraft/modsync-declined.json`). |
| `peercraft.modSync.sendPacingMillis` | *(system property only)* | `0` | Per-chunk send throttle for the P2P jar transfer, set on the **host**. `0` uses the built-in light pacing; a small value (a 6000-byte chunk every `8` ms ≈ 750 KiB/s) throttles the host's upload so a big mod stops stalling on a lossy uplink. Max `50`. |

### Random mid-session disconnects on a lossy or DPI-mangled link

The direct P2P bridge runs Minecraft's TCP stream over UDP with its own resend-on-loss layer. If a datagram goes missing and can't be re-fetched fast enough, that layer deliberately drops the session (you're kicked to the title screen but can reconnect straight away). Ordinary internet loss rarely trips it, but anything that reorders, duplicates or drops UDP by design — most notably **ZAPRET** and similar DPI-bypass tools — makes it far more likely the longer you play, because every burst of world data is another chance to hit the limit.

First, scope the DPI-bypass tool so it does **not** touch PeerCraft's UDP ports (`50001`/`50002` by default, or whatever you set) or the peer's IP — if the disconnects stop, that was the cause. If you can't, widen the recovery window: `-Dpeercraft.reorder.gapTimeoutMillis=20000 -Dpeercraft.reorder.maxPending=16384`, and optionally add `-Dpeercraft.send.pacingMillis=1` on the **sending** side (the host, for the joiner's world-load; both sides is fine). These only need to change on the machine that's getting kicked, but matching them on both is harmless.

### Mod sync

When you join a friend's world over PeerCraft and the host has mods you're missing, PeerCraft can fetch them for you. After the NAT punch succeeds (and before Minecraft actually connects), the two sides compare mod lists; if anything is missing you get a scrollable screen listing each jar — id, version, size, download source, and whether its exact SHA-512 matches a publicly listed Modrinth file. "No public listing" and "Check unavailable" are separate states; a listing is evidence of publication, **not** a malware scan or a safety guarantee. Mods marked **client-side** in their `fabric.mod.json` carry a checkbox — untick any you don't want. Mods needed to join the host's world (client+server, and server-only) have no checkbox. A filter button cycles all / client-side / required. With manual confirmation, nothing is downloaded until you click "Download & install." Every jar is SHA-512-verified against the host's manifest before it lands in `mods/`, install is purely additive (an existing jar is never moved or overwritten), and what was installed is logged to `config/peercraft/modsync-installed.json`.

What you untick is remembered in `config/peercraft/modsync-declined.json`, so the screen doesn't nag on every re-join — it only comes back when the host offers a mod that isn't on that list (or with `peercraft.modSync.reofferDeclined=true`). If every missing mod is one you've already ruled out, the join proceeds silently.

Minecraft can't load newly installed mods without a relaunch, so after a download PeerCraft does **not** continue the join — it shows a "restart required" screen. Quit, relaunch, and reconnect; this time the mods are present and the join goes straight through. If you untick everything and nothing is required, PeerCraft connects right away with no restart. `peercraft.modSync.autoAccept=true` skips confirmation only when every selected jar has a public Modrinth listing; missing listings and lookup failures still need a click.

Mod sync is split into a **host** side and a **client** side, each set independently in the Settings screen (or via `peercraft.modSync.host` / `peercraft.modSync.client`) to **Off / Required mods only / All mods**. This lets one player download mods when joining a friend's world while, as a host, either turning mod sync off for incoming players or sharing only the mods actually required to enter the world. "Required" means a mod whose server side is genuinely needed to join; purely client-side mods (AppleSkin and the like) are the optional part. Both the host's "Required mods only" filter (what it shares) and the joiner's (what it downloads) start from each jar's `fabric.mod.json` `environment` / `mods.toml` `side` and then refine it against Modrinth's curated `client_side` / `server_side` — the same source the confirm screen uses — so a client-only mod that merely declares `"*"` is still withheld / skipped. Java 8 backports also download exact catalog matches from the Modrinth CDN over HTTPS, verifying size and SHA-512 against the host manifest before installation and falling back to P2P on catalog or download failure. They use jar metadata for side classification. A public listing and matching hash establish publication and byte identity, not mod safety. See [Java 8 transport checks](docs/modrinth-java8.md).

Optionally the joiner can also clean up: with `modSync.removeExtra` set to `keep_client` or `all`, a host on the same loader and Minecraft version also reports which of the joiner's mods it has no jar for (counting libraries it only loads nested inside another jar). The confirm screen lists them as removal rows, each with its own checkbox; auto-accept never applies to removals. Like version replacements, removals run in the bundled helper after Minecraft closes, keep the original jar in the transaction's `backups/` folder and roll back together with the rest of the batch on failure. `keep_client` skips mods classified as client-only (jar metadata refined via Modrinth on modern versions, jar metadata only on the Java 8 backports).

The P2P transfer tolerates ordinary packet loss (a mangled or dropped chunk is silently re-requested, and a transfer that fails outright can simply be retried by reconnecting — the host re-serves from a fresh session). On a badly congested or DPI-mangled uplink where a large jar keeps stalling near the end, set `-Dpeercraft.modSync.sendPacingMillis=8` on the **host** to cap its upload rate; raise the number if it still stalls, lower it for more speed.

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

For two players on different networks, PeerCraft uses the standalone rendezvous server (`rendezvous-server/`) to let host and joiner discover each other's public UDP address, then attempts direct UDP hole punching. Once punching succeeds, everything downstream uses the same P2P bridge protocol as the local test above, just talking to a real remote address instead of `127.0.0.1`.

**Self-hosted relay.** When the direct checks fail, the player who configured a relay code (`relay.ownCode`) opens a session on their `peercraft-relay` from the P2P socket and announces it as a *relay ticket*: a signed candidate update (`TYPE_CONNECTIVITY_CHECK`/`DIRECT_CANDIDATE_UPDATE`) whose reserved 8-byte field carries the session id. The rendezvous server forwards it like any candidate, so no server change is needed. The other player joins the session; payloads go through `SecureDatagramChannel` with keys derived from the match secret, so the relay sees only ciphertext. If both players have a relay, the host's is used; if neither has one, the attempt fails with `peercraft.p2p.fail.direct_failed` after a short wait for a ticket.

**TURN fallback is disabled in current builds.** `PeerCraftConfig.relayEnabled()` returns `false`, the direct-failure path in `P2PBridge` does not call `beginRelay(...)`, and `RendezvousServer.run()` does not call `startRelay()`. This applies to the main client sources and both Forge backports. Neither saved settings, launch flags nor `enabled=true` in the broker configuration restore fallback; reactivation requires code changes, rebuilt clients/server and closed real-network/gameplay validation.

The retained experimental implementation supports coturn and Cloudflare TURN, account-authenticated leases and provider health/usage guards. The [coturn preparation guide](rendezvous-server/deploy/coturn/README.ru.md) and [broker configuration](rendezvous-server/README.md) describe that implementation for future testing, not an available connection option. Normal play currently requires direct connectivity.

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
- `peercraft-relay`: `cd peercraft-relay && ./gradlew test` — relay session handling, limits and a real-socket forwarding test.
- `rendezvous-server`: `cd rendezvous-server && ./gradlew test` — unit and integration tests for the room registry, protocol, and accounts service, exercised over raw UDP with no Minecraft dependency.
- End-to-end tests that spin up a real rendezvous-server subprocess self-skip if the sibling project's jar hasn't been built yet — build it first with `cd rendezvous-server && ./gradlew jar`.
