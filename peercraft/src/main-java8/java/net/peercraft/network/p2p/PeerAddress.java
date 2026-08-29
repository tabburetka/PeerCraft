package net.peercraft.network.p2p;

import java.net.InetAddress;
import java.util.Objects;

/**
 * Routing key for host-side connections in {@link P2PBridge}: one joiner = one
 * (host, port). Kept separate from {@code network.rendezvous.RendezvousProtocol.Address}
 * so that network.p2p doesn't depend on the rendezvous package.
 *
 * <p>Java 8 backport of the {@code record} in {@code src/main/java} — hand-written so it
 * compiles at {@code --release 8} for the Minecraft 1.16.5 target. Field set, accessor
 * names and {@code equals}/{@code hashCode} semantics are identical to the record; it is
 * used as a {@link java.util.Map} key / {@link java.util.Set} member in {@link P2PBridge},
 * so value equality must be preserved.
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

    public String ip() {
        return host.getHostAddress();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PeerAddress)) {
            return false;
        }
        PeerAddress other = (PeerAddress) o;
        return port == other.port && Objects.equals(host, other.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port);
    }

    @Override
    public String toString() {
        return "PeerAddress[host=" + host + ", port=" + port + "]";
    }
}
