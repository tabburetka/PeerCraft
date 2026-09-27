package net.peercraft.network.handoff;

import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** RPCs use the bridge socket, preserving the source room's address for anonymous BEGIN. */
public final class HandoffAuthorityClient {
    public interface Sender { void send(InetAddress address, int port, byte[] bytes); }
    private final Sender sender;
    private final InetAddress address;
    private final int port;
    private final AtomicLong ids = new AtomicLong(new SecureRandom().nextLong());
    private static final class Pending {
        final UUID session;
        final long offer;
        final CompletableFuture<HandoffAuthorityProtocol.Message> result = new CompletableFuture<>();
        Pending(HandoffAuthorityProtocol.Message request) { session = request.sessionId; offer = request.offerId; }
    }
    private final ConcurrentMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;
    public HandoffAuthorityClient(Sender sender, InetAddress address, int port) {
        this.sender = sender; this.address = address; this.port = port;
    }
    public HandoffAuthorityProtocol.Message call(HandoffAuthorityProtocol.Message m) throws IOException {
        if (closed) throw new IOException("Handoff authority client is closed");
        m.requestId = ids.incrementAndGet();
        Pending request = new Pending(m);
        CompletableFuture<HandoffAuthorityProtocol.Message> result = request.result;
        pending.put(m.requestId, request);
        if (closed) result.completeExceptionally(new IOException("Handoff authority client is closed"));
        try {
            byte[] bytes = HandoffAuthorityProtocol.encode(m);
            for (int i = 0; i < 10; i++) {
                sender.send(address, port, bytes);
                try { return result.get(500, TimeUnit.MILLISECONDS); }
                catch (TimeoutException retry) { }
            }
            throw new IOException("Handoff authority did not answer");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("Interrupted authority request", e);
        } catch (ExecutionException e) { throw new IOException(e.getCause()); }
        finally { pending.remove(m.requestId); }
    }
    public void onPacket(byte[] bytes, int length, InetAddress from, int fromPort) {
        if (!address.equals(from) || port != fromPort) return;
        try {
            HandoffAuthorityProtocol.Message m = HandoffAuthorityProtocol.decode(bytes, length);
            if (m.type != HandoffAuthorityProtocol.REPLY) return;
            Pending request = pending.get(m.requestId);
            if (request != null && request.session.equals(m.sessionId) && request.offer == m.offerId) request.result.complete(m);
        } catch (IOException ignored) { }
    }
    public void close() {
        closed = true;
        for (Pending request : pending.values()) request.result.completeExceptionally(new IOException("Handoff authority client is closed"));
    }
    /** Room challenges are returned only to the registered host endpoint; no account is required. */
    public byte[] roomProof(String room) throws IOException {
        HandoffAuthorityProtocol.Message request = new HandoffAuthorityProtocol.Message(HandoffAuthorityProtocol.CAPABILITIES);
        request.room = room;
        HandoffAuthorityProtocol.Message answer = call(request);
        boolean nonzero = false; for (byte b : answer.key) nonzero |= b != 0;
        if (answer.state != HandoffAuthorityProtocol.SUPPORTED || !nonzero)
            throw new IOException("Room hosting authority is not confirmed");
        return answer.key.clone();
    }
    public static HandoffAuthorityProtocol.Message request(int type, UUID session, long offer, long epoch, byte[] key) {
        HandoffAuthorityProtocol.Message m = new HandoffAuthorityProtocol.Message(type);
        m.sessionId = session; m.offerId = offer; m.epoch = epoch; m.key = key.clone();
        return m;
    }
    public static byte[] newKey() { byte[] key = new byte[32]; new SecureRandom().nextBytes(key); return key; }
}
