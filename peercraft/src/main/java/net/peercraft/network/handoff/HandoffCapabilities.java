package net.peercraft.network.handoff;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A distinct packet family: v1 clients cannot interpret these probes as an offer. */
public final class HandoffCapabilities {
    public static final byte MAGIC = (byte) 0xE7;
    public static final int VERSION = 2;
    public static final long SAFE_HANDOFF = 1L;
    public interface Sender { void send(InetSocketAddress peer, byte[] packet); }
    private static final int SIZE = 19;
    private final Sender sender;
    private final long supported;
    private final Map<Long, Probe> pending = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private static final class Probe {
        final InetSocketAddress peer;
        final CompletableFuture<Long> reply = new CompletableFuture<>();
        Probe(InetSocketAddress peer) { this.peer = peer; }
    }
    public HandoffCapabilities(Sender sender, long supported) { this.sender = sender; this.supported = supported; }
    private static byte[] packet(int type, long nonce, long features) {
        return ByteBuffer.allocate(SIZE).put(MAGIC).put((byte) VERSION).put((byte) type)
                .putLong(nonce).putLong(features).array();
    }
    public void onPacket(byte[] bytes, int length, InetSocketAddress peer) {
        if (length != SIZE || bytes.length < length || bytes[0] != MAGIC || bytes[1] != VERSION) return;
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, length); buffer.position(2);
        int type = buffer.get() & 255; long nonce = buffer.getLong(); long features = buffer.getLong();
        if (type == 1) sender.send(peer, packet(2, nonce, supported));
        else if (type == 2) {
            Probe probe = pending.get(nonce);
            if (probe != null && probe.peer.equals(peer)) probe.reply.complete(features);
        }
    }
    /** Probes all participants together and returns before any OFFER or player preparation. */
    public void require(Set<InetSocketAddress> participants, long features, long timeoutMillis) throws IOException {
        if (participants.isEmpty() || timeoutMillis <= 0) throw new IOException("Invalid capability negotiation");
        Map<Long, Probe> probes = new HashMap<>();
        Set<InetSocketAddress> snapshot = new HashSet<>(participants);
        for (InetSocketAddress peer : snapshot) {
            if (peer == null || peer.isUnresolved() || peer.getPort() == 0) throw new IOException("Invalid participant");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            for (InetSocketAddress peer : snapshot) {
                long nonce; Probe probe = new Probe(peer);
                do { nonce = random.nextLong(); } while (pending.putIfAbsent(nonce, probe) != null);
                probes.put(nonce, probe);
            }
            while (true) {
                boolean complete = true;
                for (Map.Entry<Long, Probe> entry : probes.entrySet()) {
                    Probe probe = entry.getValue();
                    if (!probe.reply.isDone()) {
                        complete = false; sender.send(probe.peer, packet(1, entry.getKey(), features));
                    } else if ((probe.reply.get() & features) != features) throw new IOException("Participant lacks safe handoff support");
                }
                if (complete) return;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("Participant did not confirm handoff support");
                // Waiting on the aggregate also permits fast replies without sleeping the entire retry interval.
                CompletableFuture<?> all = CompletableFuture.allOf(probes.values().stream().map(p -> p.reply).toArray(CompletableFuture[]::new));
                try { all.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(300)), TimeUnit.NANOSECONDS); }
                catch (TimeoutException retry) { }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("Capability negotiation interrupted", interrupted);
        } catch (ExecutionException failed) { throw new IOException("Capability negotiation failed", failed); }
        finally { for (Map.Entry<Long, Probe> entry : probes.entrySet()) pending.remove(entry.getKey(), entry.getValue()); }
    }
}
