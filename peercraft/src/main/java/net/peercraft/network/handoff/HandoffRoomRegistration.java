package net.peercraft.network.handoff;

import java.io.IOException;
import java.util.concurrent.*;

/** One launch attempt: LAN and rendezvous registration share a single finite deadline. */
public final class HandoffRoomRegistration {
    public final long offerId;
    private final long deadlineNanos;
    private final CompletableFuture<String> room = new CompletableFuture<>();
    public HandoffRoomRegistration(long offerId, long timeoutMillis) {
        this.offerId = offerId; this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }
    public void registered(String code) {
        if (code != null && !code.isEmpty()) room.complete(code);
    }
    public void failed(String reason) { room.completeExceptionally(new IOException(reason)); }
    public String await() throws IOException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) throw new IOException("Handoff room registration timed out");
        try { return room.get(remaining, TimeUnit.NANOSECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        catch (ExecutionException | TimeoutException e) { throw new IOException("Handoff room registration failed", e); }
    }
}
