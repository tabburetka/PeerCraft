# PeerCraft

PeerCraft is a Fabric client-side experiment that forwards a Minecraft client connection through a custom UDP bridge to another Minecraft instance opened to LAN — either on the same machine (`peercraft.peerHost`/`peercraft.peerPort`, the default) or over the real internet between two different networks, via a small rendezvous server plus UDP hole punching (`peercraft.internetPlay=true` — see [Internet play](#internet-play-rendezvous-server--hole-punching) below).

## Configuration

The mod reads Java system properties first and environment variables second. If a value is not set, the default is used.

| System property | Environment variable | Default | Description |
| --- | --- | --- | --- |
| `peercraft.mode` | `PEERCRAFT_MODE` | `auto` | `auto`/`client` starts the local TCP proxy. `host` waits for Open to LAN and only starts the UDP host bridge. `disabled` disables PeerCraft startup. |
| `peercraft.proxyPort` | `PEERCRAFT_PROXY_PORT` | `25566` | Local TCP port that the joining Minecraft client connects to. |
| `peercraft.clientUdpPort` | `PEERCRAFT_CLIENT_UDP_PORT` | `50002` | UDP port listened to by the joining client side of the bridge. |
| `peercraft.hostUdpPort` | `PEERCRAFT_HOST_UDP_PORT` | `50001` | UDP port listened to by the host side of the bridge after Open to LAN. |
| `peercraft.peerHost` | `PEERCRAFT_PEER_HOST` | `127.0.0.1` | Address of the remote UDP peer. Use `127.0.0.1` when testing two clients on one computer. |
| `peercraft.peerPort` | `PEERCRAFT_PEER_PORT` | host: `clientUdpPort`, client: `hostUdpPort` | UDP port of the remote peer. Usually you do not need to set this for a local two-client test. Ignored when `internetPlay=true`. |
| `peercraft.internetPlay` | `PEERCRAFT_INTERNET_PLAY` | `false` | Master opt-in for internet play (rendezvous server + UDP hole punching) instead of the static `peerHost`/`peerPort` path above. `false` leaves local/loopback behavior completely unchanged. |
| `peercraft.rendezvousHost` | `PEERCRAFT_RENDEZVOUS_HOST` | `127.0.0.1` | Address of the rendezvous server. Only used when `internetPlay=true`. |
| `peercraft.rendezvousPort` | `PEERCRAFT_RENDEZVOUS_PORT` | `51000` | UDP port of the rendezvous server. |
| `peercraft.roomCode` | `PEERCRAFT_ROOM_CODE` | *(empty)* | Joiner-only: the 6-character room code given out by the host. Required when `internetPlay=true` and `mode=client`. |

## Local self-connect test

Run the two Minecraft instances from separate working directories (`run-host/` and `run-client/`) so their `logs/latest.log` files don't interleave — the IDE run configs `Minecraft Client (host)` and `Minecraft Client (join)` are already set up this way.

1. Build the mod with `./gradlew build` from this directory.
2. Start the **host** instance (`Minecraft Client (host)`, or manually with `-Dpeercraft.mode=host`, working dir `run-host/`). Create or load a single-player world.
3. Click **Open to LAN**. `run-host/logs/latest.log` should contain `ХОСТ ГОТОВ` and say that UDP listens on `50001`.
4. Start the **client** instance (`Minecraft Client (join)`, or manually with `-Dpeercraft.mode=client`, working dir `run-client/`). `run-client/logs/latest.log` should contain `КЛИЕНТ ГОТОВ`, local proxy port `25566`, and UDP listen port `50002`.
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

1. Both the host and joiner set `-Dpeercraft.internetPlay=true -Dpeercraft.rendezvousHost=<server address> -Dpeercraft.rendezvousPort=51000` in addition to their usual `-Dpeercraft.mode=host`/`client`.
2. **Host**: create/load a world, click **Open to LAN** as usual. `logs/latest.log` should show `Регистрируемся на сервере знакомств...` followed by `Комната создана! Код для второго игрока: XXXXXX` — share that code with the joiner out-of-band (chat, Discord, etc.).
3. **Joiner**: also set `-Dpeercraft.roomCode=XXXXXX`. `logs/latest.log` should show `Присоединяемся к комнате...`, then `Пир найден: <ip>:<port>, начинаем hole punching...`, then either `P2P-соединение установлено напрямую с <ip>:<port>` (success — and critically, that IP should be the peer's **real public address**, not `127.0.0.1`) or, after ~10s, `Hole punching не удался: ...` (clean failure).
4. On success, connect to `127.0.0.1:25566` (the local proxy port — unchanged) exactly as in the local test, and run through the same login/reconnect checks above.
5. To genuinely exercise NAT traversal rather than accidentally retesting a LAN, run the host and joiner on two different networks — e.g. one on home Wi-Fi and the other tethered to mobile data.
6. If punching consistently fails specifically over mobile data, suspect carrier-grade NAT (CGNAT) before assuming a bug — that's a real, common case hole punching legitimately can't solve.

### Server-side and protocol tests

`rendezvous-server` has its own unit + integration test suite (`cd rendezvous-server && ./gradlew test`) that exercises the room registry and the live server over raw UDP without needing Minecraft at all. The mod's own test suite (`./gradlew test` from this directory) includes a full client-side end-to-end test (`RendezvousEndToEndTest`) that drives the real `RendezvousClient`/`PunchCoordinator` flow against a real rendezvous-server subprocess — build the server jar first (`cd rendezvous-server && ./gradlew jar`) for that test to run instead of skipping itself.
