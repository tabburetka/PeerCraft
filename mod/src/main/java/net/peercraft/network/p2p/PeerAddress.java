package net.peercraft.network.p2p;

import java.net.InetAddress;

/**
 * Routing key for host-side connections in {@link P2PBridge}: one joiner = one
 * (host, port). Kept separate from {@code network.rendezvous.RendezvousProtocol.Address}
 * so that network.p2p doesn't depend on the rendezvous package.
 */
public record PeerAddress(InetAddress host, int port) {

    public String ip() {
        return host.getHostAddress();
    }
}
