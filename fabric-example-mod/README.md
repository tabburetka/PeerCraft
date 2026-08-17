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

1. Build the mod with `./gradlew build` from this directory.
2. Start the first Minecraft client with `-Dpeercraft.mode=host`. Create or load a single-player world.
3. In the first client, click **Open to LAN**. The log should contain a message like `ХОСТ ГОТОВ` and say that UDP listens on `50001`.
4. Start the second Minecraft client with `-Dpeercraft.mode=client`. The log should contain a message like `КЛИЕНТ ГОТОВ`, local proxy port `25566`, and UDP listen port `50002`.
5. In the second client, open **Multiplayer** and connect to `127.0.0.1:25566`. Do not connect to the LAN port shown by Minecraft; connect to the proxy port.
6. If either port is busy, change both sides consistently. For example, run the host with `-Dpeercraft.hostUdpPort=50101 -Dpeercraft.clientUdpPort=50102` and the client with the same two values.

## Troubleshooting

### `finishConnect(...) failed: В соединении отказано`

This error happens before the UDP bridge is used: the joining Minecraft client cannot open a TCP connection to the local proxy. Check the joining/client Minecraft log for `Локальный TCP-прокси успешно запущен на 127.0.0.1:<port>`.

If that line is missing:

1. Make sure the joining client is started with `-Dpeercraft.mode=client` or without a mode override. Do not use `-Dpeercraft.mode=host` on the joining client.
2. Make sure the mod jar you just built is installed in the joining client's `mods` folder.
3. Make sure you connect to the proxy address (`127.0.0.1:25566` by default), not to the Minecraft LAN port shown by Open to LAN.
4. If the log says the proxy port is busy, start the joining client with another proxy port, for example `-Dpeercraft.proxyPort=25567`, and connect to `127.0.0.1:25567`.

### Host log contains client entrypoint lines

This is expected for the first Minecraft window: an integrated LAN server runs inside a Minecraft client process, so Fabric still loads the client entrypoint. With `-Dpeercraft.mode=host`, the log must say `effective mode='host'` and then `Клиентский TCP-прокси и UDP-клиент не запускаются`. If it starts `LocalProxy` anyway, the JVM property did not reach that launched process or an old mod jar is being used.

### `Адрес уже используется`

Only one process can listen on a TCP/UDP port at a time. If `25566` or `50002` is busy, another PeerCraft client is still running or another app owns the port. Close the old client or use another set of ports, for example on the joining client: `-Dpeercraft.proxyPort=25567 -Dpeercraft.clientUdpPort=50012`. If you change `clientUdpPort`, start the host with the same `-Dpeercraft.clientUdpPort=50012` so host replies to the correct client UDP port.

### `DecoderException: Failed to decode packet ... minecraft:hello`

This usually means the Minecraft TCP stream was corrupted while being wrapped into UDP datagrams. Older PeerCraft builds used a 1400-byte UDP receive buffer and reused `DatagramPacket` without resetting its length, so larger login/encryption packets could be truncated. Rebuild and install the latest jar on both clients; if the error remains, collect both clients' PeerCraft logs around the first `P2PReceiver` packets.
