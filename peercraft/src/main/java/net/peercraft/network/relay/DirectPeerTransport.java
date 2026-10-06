package net.peercraft.network.relay;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.function.Consumer;
import net.peercraft.network.p2p.P2PSender;
import net.peercraft.network.transport.PeerTransport;
import net.peercraft.network.transport.SecureDatagramChannel;

/** A negotiated direct route keeps the same logical peer even if punching learned a new port. */
public final class DirectPeerTransport implements PeerTransport {
    private final P2PSender sender;
    private final InetSocketAddress endpoint;
    private final SecureDatagramChannel channel;
    private final BulkPacer pacer = new BulkPacer();
    private volatile boolean closed;
    public DirectPeerTransport(P2PSender sender, InetSocketAddress endpoint, UUID attempt, byte[] key, boolean host) {
        this.sender = sender; this.endpoint = endpoint;
        byte[] outgoing = derive(key, host ? "host-to-joiner" : "joiner-to-host");
        byte[] incoming = derive(key, host ? "joiner-to-host" : "host-to-joiner");
        channel = new SecureDatagramChannel(outgoing, incoming, attempt, attempt);
    }
    private static byte[] derive(byte[] secret, String direction) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(secret); digest.update(direction.getBytes(StandardCharsets.UTF_8));
            return java.util.Arrays.copyOf(digest.digest(), 16);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @Override public void send(byte[] payload) throws IOException {
        if (closed) throw new IOException("route_closed");
        pacer.await(payload);
        for (byte[] packet : channel.encode(payload)) sender.sendData(packet, endpoint.getAddress().getHostAddress(), endpoint.getPort());
    }
    public byte[] accept(byte[] packet, InetSocketAddress source) {
        if (closed || !endpoint.equals(source)) return null;
        return channel.accept(packet, System.currentTimeMillis());
    }
    public InetSocketAddress endpoint() { return endpoint; }
    @Override public String mode() { return "direct"; }
    @Override public void close() { closed = true; }
}
