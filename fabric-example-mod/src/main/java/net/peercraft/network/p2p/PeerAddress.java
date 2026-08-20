package net.peercraft.network.p2p;

import java.net.InetAddress;

/**
 * Ключ маршрутизации для host-side соединений в {@link P2PBridge}: один джойнер = один
 * (host, port). Отдельный от {@code network.rendezvous.RendezvousProtocol.Address}, чтобы
 * network.p2p не зависел от пакета rendezvous.
 */
public record PeerAddress(InetAddress host, int port) {

    public String ip() {
        return host.getHostAddress();
    }
}
