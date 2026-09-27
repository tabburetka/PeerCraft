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
    private final ConcurrentMap<Long, CompletableFuture<HandoffAuthorityProtocol.Message>> pending = new ConcurrentHashMap<>();
    public HandoffAuthorityClient(Sender sender, InetAddress address, int port) {
        this.sender = sender; this.address = address; this.port = port;
    }
    public HandoffAuthorityProtocol.Message call(HandoffAuthorityProtocol.Message m) throws IOException {
        m.requestId = ids.incrementAndGet();
        CompletableFuture<HandoffAuthorityProtocol.Message> result = new CompletableFuture<>();
        pending.put(m.requestId, result);
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
            CompletableFuture<HandoffAuthorityProtocol.Message> future = pending.get(m.requestId);
            if (future != null) future.complete(m);
        } catch (IOException ignored) { }
    }
    public static HandoffAuthorityProtocol.Message request(int type, UUID session, long offer, long epoch, byte[] key) {
        HandoffAuthorityProtocol.Message m = new HandoffAuthorityProtocol.Message(type);
        m.sessionId = session; m.offerId = offer; m.epoch = epoch; m.key = key.clone();
        return m;
    }
    public static byte[] newKey() { byte[] key = new byte[32]; new SecureRandom().nextBytes(key); return key; }
}
