package net.peercraft.network.handoff;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the lifetime and NAT mappings of an attempt independently of Minecraft TCP. */
public final class HandoffTransport implements AutoCloseable {
    public interface Sender { void send(InetSocketAddress peer, byte[] bytes); }
    private final Set<InetSocketAddress> peers;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService keepalive;
    private volatile boolean stoppingSource;
    public final long offerId;

    public HandoffTransport(long offerId, Set<InetSocketAddress> participants, Sender sender) {
        this(offerId, participants, sender, peer -> HandoffProtocol.encodePing());
    }
    public HandoffTransport(long offerId, Set<InetSocketAddress> participants, Sender sender,
                            java.util.function.Function<InetSocketAddress, byte[]> heartbeat) {
        this.offerId = offerId;
        Set<InetSocketAddress> copy = new HashSet<>();
        for (InetSocketAddress peer : participants) {
            if (peer == null || peer.isUnresolved() || peer.getPort() == 0)
                throw new IllegalArgumentException("Invalid handoff participant");
            copy.add(peer);
        }
        if (copy.isEmpty()) throw new IllegalArgumentException("No handoff participants");
        peers = Collections.unmodifiableSet(copy);
        keepalive = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "PeerCraft-Handoff-Keepalive");
            thread.setDaemon(true); return thread;
        });
        keepalive.scheduleWithFixedDelay(() -> {
            if (closed.get()) return;
            for (InetSocketAddress peer : peers) {
                try { sender.send(peer, heartbeat.apply(peer)); }
                catch (RuntimeException ignored) { /* The operation, not a NAT ping, decides the outcome. */ }
            }
        }, 0, 5, TimeUnit.SECONDS);
    }
    /** Called only after consent and preflight, before requesting the server stop. */
    public void sourceStopping() {
        if (closed.get()) throw new IllegalStateException("Handoff transport is closed");
        stoppingSource = true;
    }
    public boolean preservesSourceStop() { return !closed.get() && stoppingSource; }
    public boolean contains(InetSocketAddress peer) { return !closed.get() && peers.contains(peer); }
    public Set<InetSocketAddress> participants() { return peers; }
    public boolean isClosed() { return closed.get(); }
    @Override public void close() {
        if (closed.compareAndSet(false, true)) keepalive.shutdownNow();
    }
}
