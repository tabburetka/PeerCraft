package net.peercraft.network.transport;

import java.io.IOException;

/** A route for a logical peer. Its identity remains stable when its physical route changes. */
public interface PeerTransport extends AutoCloseable {
    void send(byte[] payload) throws IOException;
    String mode();
    default java.util.concurrent.CompletableFuture<Void> retainHandoff(long offerId) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    default void releaseHandoff(long offerId) { }
    @Override void close();
}
