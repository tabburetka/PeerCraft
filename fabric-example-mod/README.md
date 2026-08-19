# PeerCraft

PeerCraft is a Fabric client-side experiment that forwards a Minecraft client connection through a custom UDP bridge to another Minecraft instance opened to LAN — either on the same machine (`peercraft.peerHost`/`peercraft.peerPort`, the default) or over the real internet between two different networks, via a small rendezvous server plus UDP hole punching (see [Internet play](#internet-play-rendezvous-server--hole-punching) below).

No launch flags are needed for normal play: the joiner clicks **PeerCraft: Join** on the title screen and types a room code, and the host ticks the **PeerCraft: через интернет** checkbox on the vanilla "Open to LAN" screen — both decisions are made live, in-game, per click, not baked into the JVM command line. The system properties below still work and now mostly serve as *defaults* for those in-game controls (e.g. a headless/always-hosting machine, or CI/tests) rather than the only way to configure them.

## Configuration

The mod reads Java system properties first and environment variables second. If a value is not set, the default is used.

| System property | Environment variable | Default | Description |
| --- | --- | --- | --- |
| `peercraft.mode` | `PEERCRAFT_MODE` | `auto` | `auto` (the default, and normally the only value you need) starts the local TCP proxy at launch and dynamically becomes either role from there — client-side (join) if you use the title screen's Join button, or host-side if you click Open to LAN. `client`/`host` pin a single role (useful for a dedicated always-one-role machine); `disabled` disables PeerCraft entirely. |
| `peercraft.proxyPort` | `PEERCRAFT_PROXY_PORT` | `25566` | Local TCP port that the joining Minecraft client connects to. |
| `peercraft.clientUdpPort` | `PEERCRAFT_CLIENT_UDP_PORT` | `50002` | UDP port listened to by the joining client side of the bridge. |
| `peercraft.hostUdpPort` | `PEERCRAFT_HOST_UDP_PORT` | `50001` | UDP port listened to by the host side of the bridge after Open to LAN. |
| `peercraft.peerHost` | `PEERCRAFT_PEER_HOST` | `127.0.0.1` | Address of the remote UDP peer for the static (non-internet) path. Use `127.0.0.1` when testing two clients on one computer. |
| `peercraft.peerPort` | `PEERCRAFT_PEER_PORT` | host: `clientUdpPort`, client: `hostUdpPort` | UDP port of the remote peer. Usually you do not need to set this for a local two-client test. Only used on the static (non-internet) path. |
| `peercraft.internetPlay` | `PEERCRAFT_INTERNET_PLAY` | `false` | Initial state of the **PeerCraft: через интернет** checkbox on the "Open to LAN" screen — the host can still tick/untick it per click regardless of this default. Set `true` here as a convenience for a machine that always hosts over the internet. |
| `peercraft.rendezvousHost` | `PEERCRAFT_RENDEZVOUS_HOST` | `91.146.31.165` (the project's public rendezvous server) | Default address of the rendezvous server, used unless overridden via the in-game "Переопределить адрес сервера" box on the Join screen. Set to `127.0.0.1` for local dev testing against a rendezvous server run on the same machine. |
| `peercraft.rendezvousPort` | `PEERCRAFT_RENDEZVOUS_PORT` | `51000` | UDP port of the rendezvous server. |
| `peercraft.roomCode` | `PEERCRAFT_ROOM_CODE` | *(empty)* | Joiner-only convenience: pre-fills the room-code box on the in-game Join screen. No longer required — you can just type the code there instead. |

## Local self-connect test

Run the two Minecraft instances from separate working directories (`run-host/` and `run-client/`) so their `logs/latest.log` files don't interleave — the IDE run configs `Minecraft Client (host)` and `Minecraft Client (join)` are already set up this way.

1. Build the mod with `./gradlew build` from this directory.
2. Start the **host** instance (`Minecraft Client (host)`, working dir `run-host/`; `peercraft.mode` can be left unset/`auto` — pin it to `-Dpeercraft.mode=host` only if you specifically want to disable that instance's client-role code). Create or load a single-player world.
3. Click **Open to LAN** (leave the **PeerCraft: через интернет** checkbox unticked for this local test). `run-host/logs/latest.log` should contain `ХОСТ ГОТОВ` and say that UDP listens on `50001`.
4. Start the **client** instance (`Minecraft Client (join)`, working dir `run-client/`; same note on `peercraft.mode` applies). `run-client/logs/latest.log` should contain `КЛИЕНТ ГОТОВ`, local proxy port `25566`, and UDP listen port `50002`.
5. In the client instance, open **Multiplayer** and connect to `127.0.0.1:25566`. Do not connect to the LAN port shown by Minecraft; connect to the proxy port.
6. Confirm a clean login: no `Failed to decode packet 'serverbound/minecraft:hello'` in `run-host/logs/latest.log`. Each connection is a session — look for `Новая клиентская сессия <id>` on the client side and `Подключаемся к локальному MC серверу ... (сессия <id>)` / `УСПЕШНО подключились ... (сессия <id>)` on the host side, with matching ids.
7. If either port is busy, change both sides consistently. For example, run the host with `-Dpeercraft.hostUdpPort=50101 -Dpeercraft.clientUdpPort=50102` and the client with the same two values.

### Reconnect regression test

The bug this bridge used to hit (`minecraft:hello` decode failures in a tight reconnect loop) only showed up across repeated reconnects, so a single successful join isn't enough to call it fixed:

1. Without restarting either JVM, disconnect the client from the server and reconnect to `127.0.0.1:25566` again. Repeat 5-10 times.
2. After each attempt, check `run-host/logs/latest.log`: a fresh session id should appear each time, there should be no `DecoderException`, and no automatic reconnect loop (the host should only attempt a new connection when you actually reconnect).
3. Also open and close the **Multiplayer** server list a few times without joining — this exercises the same session-start path via short-lived status-ping connections and should not cause errors.
4. After a reconnect, play for a minute (move, chat, open inventory) to confirm the byte stream wasn't corrupted.

## Internet play (rendezvous server + hole punching)

For two players on different networks, PeerCraft uses a small standalone rendezvous
server (`rendezvous-server/`, a separate Gradle project — see its own directory, no
Minecraft/Loom dependency) to let the host and joiner discover each other's public UDP
address, then attempts direct UDP hole punching between them. Once punching succeeds,
everything downstream is the exact same relay protocol validated above — sessions,
ordering, retransmit, chunking — just talking to a real remote address instead of
`127.0.0.1`.

**v1 scope, by design**: no accounts, no persistent lobbies, single-use 6-character
room codes. If hole punching fails (most commonly a symmetric NAT on one side, or
carrier-grade NAT on mobile data), you get a clean log error — there is deliberately
no relay/TURN fallback (a home-hosted rendezvous server's uplink can't sustain
relaying full game traffic for multiple pairs). Only the rendezvous server itself
needs a forwarded port; neither player does — that's the entire point of hole
punching.

### Running the rendezvous server

```
cd rendezvous-server
./gradlew jar
```

Copy `rendezvous-server/build/libs/rendezvous-server-*.jar` to whatever machine will
host it (e.g. scp/USB) and run it there:

```
java -jar rendezvous-server-1.0.0.jar 51000
```

Forward UDP port `51000` (or whatever you pass as the argument) on that machine's
router to its LAN IP — the same kind of port-forward as hosting a vanilla Minecraft
server, just a UDP port instead of TCP. Note the machine's public IP or DDNS hostname.

### Testing internet play end-to-end

No launch flags are needed — both sides just use the mod's normal launch (`peercraft.mode` unset/`auto`) and the in-game controls below. `peercraft.rendezvousHost` defaults to the project's public server (`91.146.31.165:51000`); pass `-Dpeercraft.rendezvousHost=127.0.0.1` (with your own `rendezvous-server` running locally, see above) if you want to test against a local server instead of the real one, or leave it default and just use the in-game override box for a one-off test.

1. **Host**: create/load a world, open the pause menu → **Open to LAN**, tick the **PeerCraft: через интернет** checkbox, then **Start LAN World**. `logs/latest.log` should show `Регистрируемся на сервере знакомств...` followed by `Комната создана! Код для второго игрока: XXXXXX` — share that code with the joiner out-of-band (chat, Discord, etc.).
2. **Joiner**: from the title screen, click **PeerCraft: Join**, type the room code, and click **Подключиться**. The screen's status line should progress through connecting/hole-punching status messages and then automatically drop you into the world — no manual Direct Connect step. Under the hood, `logs/latest.log` shows the same `Присоединяемся к комнате...` → `Пир найден: <ip>:<port>, начинаем hole punching...` → `P2P-соединение установлено напрямую с <ip>:<port>` sequence as before (that IP should be the peer's **real public address**, not `127.0.0.1`); on failure the screen shows the error and re-enables the Connect button so you can retry with a different code without leaving the screen.
3. Also test the negative paths: a blank room code (screen shows "Введите код комнаты", no connection attempt), a wrong/expired code (screen shows the server's failure reason), and the host leaving the checkbox unticked (falls back to the static `peerHost`/`peerPort` path, unchanged from before).
4. To genuinely exercise NAT traversal rather than accidentally retesting a LAN, run the host and joiner on two different networks — e.g. one on home Wi-Fi and the other tethered to mobile data.
5. If punching consistently fails specifically over mobile data, suspect carrier-grade NAT (CGNAT) before assuming a bug — that's a real, common case hole punching legitimately can't solve.

### Server-side and protocol tests

`rendezvous-server` has its own unit + integration test suite (`cd rendezvous-server && ./gradlew test`) that exercises the room registry and the live server over raw UDP without needing Minecraft at all. The mod's own test suite (`./gradlew test` from this directory) includes a full client-side end-to-end test (`RendezvousEndToEndTest`) that drives the real `RendezvousClient`/`PunchCoordinator` flow against a real rendezvous-server subprocess — build the server jar first (`cd rendezvous-server && ./gradlew jar`) for that test to run instead of skipping itself.
