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
