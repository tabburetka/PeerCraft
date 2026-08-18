# PeerCraft

PeerCraft is a Fabric client-side experiment that forwards a local Minecraft client connection through a UDP bridge to another local Minecraft instance opened to LAN.

## Configuration

The mod reads Java system properties first and environment variables second. If a value is not set, the default is used.

| System property | Environment variable | Default | Description |
| --- | --- | --- | --- |
| `peercraft.mode` | `PEERCRAFT_MODE` | `auto` | `auto`/`client` starts the local TCP proxy. `host` waits for Open to LAN and only starts the UDP host bridge. `disabled` disables PeerCraft startup. |
| `peercraft.proxyPort` | `PEERCRAFT_PROXY_PORT` | `25566` | Local TCP port that the joining Minecraft client connects to. |
| `peercraft.clientUdpPort` | `PEERCRAFT_CLIENT_UDP_PORT` | `50002` | UDP port listened to by the joining client side of the bridge. |
| `peercraft.hostUdpPort` | `PEERCRAFT_HOST_UDP_PORT` | `50001` | UDP port listened to by the host side of the bridge after Open to LAN. |
| `peercraft.peerHost` | `PEERCRAFT_PEER_HOST` | `127.0.0.1` | Address of the remote UDP peer. Use `127.0.0.1` when testing two clients on one computer. |
| `peercraft.peerPort` | `PEERCRAFT_PEER_PORT` | host: `clientUdpPort`, client: `hostUdpPort` | UDP port of the remote peer. Usually you do not need to set this for a local two-client test. |

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
