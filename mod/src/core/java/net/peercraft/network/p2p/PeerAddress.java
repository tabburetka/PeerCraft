package net.peercraft.network.p2p;

import java.net.InetAddress;

/**
 * Routing key for host-side connections in {@link P2PBridge}: one joiner = one
 * (host, port). Kept separate from {@code network.rendezvous.RendezvousProtocol.Address}
 * so that network.p2p doesn't depend on the rendezvous package.
 */
public final class PeerAddress {
    private final InetAddress host;
    private final int port;

    public PeerAddress(InetAddress host, int port) {
        this.host = host;
        this.port = port;
    }

    public InetAddress host() {
        return host;
    }

    public int port() {
        return port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PeerAddress)) return false;
        PeerAddress other = (PeerAddress) o;
        return java.util.Objects.equals(host, other.host)
                && port == other.port;
    }

    @Override
    public int hashCode() {
        int h = 0;
        h = 31 * h + java.util.Objects.hashCode(host);
        h = 31 * h + Integer.hashCode(port);
        return h;
    }

    @Override
    public String toString() {
        return "PeerAddress[host=" + host + ", port=" + port + "]";
    }

    public String ip() {
        return host.getHostAddress();
    }
}
