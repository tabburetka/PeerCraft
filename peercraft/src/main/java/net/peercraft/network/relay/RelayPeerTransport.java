package net.peercraft.network.relay;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import net.peercraft.network.transport.PeerTransport;
import net.peercraft.network.transport.SecureDatagramChannel;
import net.peercraft.network.turn.TurnUdpClient;

/** Owns TURN allocations independently of Minecraft TCP and preserves crypto state during renewal. */
public final class RelayPeerTransport implements PeerTransport {
    public interface Listener {
        void onConnected(RelayPeerTransport route);
        void onData(byte[] payload);
        void onFailure(String reason);
    }
    public interface Allocations {
        TurnUdpClient create(InetSocketAddress server, String username, String password,
                             long expiresAt, TurnUdpClient.Listener listener) throws IOException;
    }
    private static final byte CONTROL = (byte) 0xEA, HELLO = 1, ACK = 2;
    private final RelayBrokerClient broker;
    private final long pairToken;
    private final String room;
    private final UUID attempt;
    private final boolean host;
    private final Listener listener;
    private final Allocations allocations;
    private final LongSupplier clock;
    private final AtomicBoolean closed = new AtomicBoolean(), started = new AtomicBoolean();
    private final ArrayBlockingQueue<Received> inbound = new ArrayBlockingQueue<Received>(2048);
    private final List<Retired> retired = new ArrayList<Retired>();
    private final List<RetiredEndpoint> retiredEndpoints = new ArrayList<RetiredEndpoint>();
    private final BulkPacer pacer = new BulkPacer();
    private final Object sendLock = new Object();
    private volatile TurnUdpClient current, candidate;
    private volatile InetSocketAddress target;
    private volatile SecureDatagramChannel channel;
    private volatile RelayBrokerClient.Lease lease;
    private volatile Identity identity;
    private volatile boolean connected;
    private volatile Probe probe;
    private Thread worker;
    private int committedGeneration, targetGeneration;
    private long lastBrokerSuccess, nextPoll, nextHello, renewAt;
    private long currentExpiresAt, candidateExpiresAt, nextCommit;
    private final List<byte[]> earlyPackets = new ArrayList<byte[]>();

    public RelayPeerTransport(RelayBrokerClient broker, long token, String room, UUID attempt, boolean host, Listener listener) {
        this(broker, token, room, attempt, host, listener,
                (server, username, password, expiresAt, callbacks) -> new TurnUdpClient(server, username, password, expiresAt, callbacks));
    }
    public RelayPeerTransport(RelayBrokerClient broker, long token, String room, UUID attempt, boolean host,
                              Listener listener, Allocations allocations) {
        this(broker, token, room, attempt, host, listener, allocations, System::currentTimeMillis);
    }
    RelayPeerTransport(RelayBrokerClient broker, long token, String room, UUID attempt, boolean host,
                       Listener listener, Allocations allocations, LongSupplier clock) {
        if (broker == null || attempt == null || listener == null || allocations == null || clock == null)
            throw new IllegalArgumentException("Missing relay dependencies");
        this.broker = broker; pairToken = token; this.room = room; this.attempt = attempt;
        this.host = host; this.listener = listener; this.allocations = allocations; this.clock = clock;
    }
    public void start() {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Relay route has already started");
        if (closed.get()) return;
        worker = new Thread(this::run, "PeerCraft-TURN-Link"); worker.setDaemon(true); worker.start();
    }
    private final java.util.concurrent.ExecutorService retentionIo = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<Runnable>(8),
            task -> { Thread thread = new Thread(task, "PeerCraft-Relay-Retention"); thread.setDaemon(true); return thread; });
    private final java.util.concurrent.atomic.AtomicReference<Long> pendingRelease = new java.util.concurrent.atomic.AtomicReference<Long>();
    private final java.util.concurrent.atomic.AtomicBoolean releaseQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private void flushRelease() throws IOException {
        Long offer = pendingRelease.get();
        if (offer != null && !closed.get() && lease != null) {
            validate(broker.release(lease.id, offer)); pendingRelease.compareAndSet(offer, null);
        }
    }
    @Override public java.util.concurrent.CompletableFuture<Void> retainHandoff(long offerId) {
        java.util.concurrent.CompletableFuture<Void> result = new java.util.concurrent.CompletableFuture<Void>();
        try { retentionIo.execute(() -> {
            try {
                if (closed.get() || !connected || lease == null) throw new IOException("route_not_ready");
                flushRelease();
                validate(broker.retain(lease.id, offerId)); result.complete(null);
            } catch (IOException | RuntimeException failure) { result.completeExceptionally(failure); }
        }); } catch (RuntimeException rejected) { result.completeExceptionally(rejected); }
        return result;
    }
    @Override public void releaseHandoff(long offerId) {
        pendingRelease.set(offerId); queueRelease();
    }
    private void queueRelease() {
        if (pendingRelease.get() == null || closed.get() || !releaseQueued.compareAndSet(false, true)) return;
        try { retentionIo.execute(() -> {
            try { flushRelease(); }
            catch (RelayBrokerClient.Failure failure) {
                if (!transientFailure(failure.reason)) closeWithFailure(failure.reason);
            }
            catch (IOException transientError) { /* Retry on the next broker poll. */ }
            catch (RuntimeException invalid) { closeWithFailure("lease_invalid"); }
            finally { releaseQueued.set(false); }
        }); } catch (RuntimeException ignored) { releaseQueued.set(false); }
    }
    public UUID linkId() { return identity != null ? identity.linkId : null; }
    @Override public String mode() { return "relay"; }
    @Override public void send(byte[] payload) throws IOException {
        if (closed.get()) throw new IOException("route_closed");
        pacer.await(payload);
        synchronized (sendLock) {
            TurnUdpClient socket = current; InetSocketAddress peer = target; SecureDatagramChannel crypto = channel;
            if (!connected || socket == null || peer == null || crypto == null) throw new IOException("route_not_ready");
            for (byte[] packet : crypto.encode(payload)) socket.sendTo(peer, packet);
        }
    }
    private void run() {
        String failure = null;
        try {
            if (closed.get()) return;
            long deadline = System.nanoTime() + 50_000_000_000L;
            adopt(broker.create(pairToken, room, attempt, host));
            lastBrokerSuccess = clock.getAsLong();
            while (!closed.get() && lease.credentials == null) {
                checkDeadline(deadline); pause(250);
                adopt(broker.state(lease.id));
            }
            if (closed.get()) return;
            channel = new SecureDatagramChannel(lease.sendKey, lease.receiveKey, lease.linkId, attempt);
            current = allocate(lease.credentials);
            currentExpiresAt = lease.credentials.expiresAt;
            committedGeneration = lease.generation;
            adopt(broker.endpoint(lease.id, lease.generation, current.relayAddress(), false));
            lastBrokerSuccess = clock.getAsLong(); nextPoll = 0;
            while (!closed.get()) {
                long now = clock.getAsLong();
                drainIncoming();
                if (!connected) checkDeadline(deadline);
                if (now >= nextPoll) {
                    queueRelease();
                    nextPoll = now + (connected ? 2000 : 300);
                    try { update(broker.state(lease.id)); lastBrokerSuccess = now; }
                    catch (IOException e) {
                        if (e instanceof RelayBrokerClient.Failure && !transientFailure(((RelayBrokerClient.Failure) e).reason)) throw e;
                        if (!connected || now - lastBrokerSuccess > 120_000) throw new RelayBrokerClient.Failure("broker_unavailable");
                    }
                }
                Probe pending = probe;
                if (pending != null && now >= nextHello) {
                    nextHello = now + 300;
                    control(pending.socket, pending.peer, HELLO, pending.nonce);
                }
                if (pending != null && pending.ack && pending.receivedHello && now >= nextCommit) {
                    try { finishProbe(pending, now); }
                    catch (IOException e) {
                        if (e instanceof RelayBrokerClient.Failure && !transientFailure(((RelayBrokerClient.Failure) e).reason)) throw e;
                        nextCommit = now + 2000;
                    }
                }
                if (connected && candidate == null && probe == null && now >= renewAt) {
                    try {
                        RelayBrokerClient.Lease renewed = broker.renew(lease.id); validate(renewed);
                        if (renewed.generation > committedGeneration && renewed.credentials != null) {
                            candidate = allocate(renewed.credentials);
                            candidateExpiresAt = renewed.credentials.expiresAt;
                            adopt(broker.endpoint(renewed.id, renewed.generation, candidate.relayAddress(), false));
                            if (target != null) beginProbe(candidate, target, renewed.generation, targetGeneration, true);
                        }
                    } catch (IOException e) {
                        if (e instanceof RelayBrokerClient.Failure && !transientFailure(((RelayBrokerClient.Failure) e).reason)) throw e;
                        if (candidate != null) { candidate.close(); candidate = null; }
                        renewAt = now + 10_000;
                    }
                }
                if (now >= currentExpiresAt) throw new RelayBrokerClient.Failure("credentials_expired");
                if (pending != null && now - pending.startedAt > 30_000 && connected) {
                    // Keep the committed allocation: failing to build a replacement never proves it ready.
                    if (candidate != null) { candidate.close(); candidate = null; }
                    probe = null; renewAt = now + 2000;
                }
                for (int i = retired.size() - 1; i >= 0; i--) {
                    if (now >= retired.get(i).until) { retired.remove(i).socket.close(); }
                }
                for (int i = retiredEndpoints.size() - 1; i >= 0; i--) {
                    RetiredEndpoint old = retiredEndpoints.get(i);
                    if (now >= old.until) { retiredEndpoints.remove(i); old.socket.retirePeer(old.endpoint); }
                }
                pause(10);
            }
        } catch (IOException e) {
            failure = e instanceof RelayBrokerClient.Failure ? ((RelayBrokerClient.Failure) e).reason : "turn_unavailable";
        } catch (RuntimeException e) { failure = "turn_unavailable"; }
        finally {
            boolean notify = !closed.getAndSet(true);
            retentionIo.shutdownNow(); closeSockets();
            RelayBrokerClient.Lease issued = lease;
            if (issued != null) try { broker.close(issued.id); } catch (IOException ignored) { }
            if (notify && failure != null) listener.onFailure("peercraft.p2p.fail." + failure);
        }
    }
    private static boolean transientFailure(String reason) {
        return "broker_unavailable".equals(reason) || "provider_unavailable".equals(reason) || "rotation_busy".equals(reason);
    }
    private static void checkDeadline(long deadline) throws RelayBrokerClient.Failure {
        if (System.nanoTime() >= deadline) throw new RelayBrokerClient.Failure("turn_timeout");
    }
    private void validate(RelayBrokerClient.Lease response) throws RelayBrokerClient.Failure {
        if (!attempt.equals(response.attemptId) || !(host ? "host" : "joiner").equals(response.role))
            throw new RelayBrokerClient.Failure("lease_invalid");
        Identity expected = identity;
        if (expected != null && (!expected.id.equals(response.id) || !expected.linkId.equals(response.linkId)
                || !MessageDigest.isEqual(expected.sendKey, response.sendKey) || !MessageDigest.isEqual(expected.receiveKey, response.receiveKey)))
            throw new RelayBrokerClient.Failure("lease_invalid");
        if ("disabled".equals(response.state) || "closed".equals(response.state)) throw new RelayBrokerClient.Failure("relay_disabled");
        if (expected == null) identity = new Identity(response);
    }
    private void adopt(RelayBrokerClient.Lease response) throws RelayBrokerClient.Failure {
        validate(response); lease = response;
        if (response.credentials != null) pacer.setRate(response.credentials.bulkBytesPerSecond);
    }
    private TurnUdpClient allocate(RelayBrokerClient.Credentials credentials) throws IOException {
        IOException failure = null;
        // Fixed order regardless of provider JSON ordering.
        List<InetSocketAddress> servers = new ArrayList<InetSocketAddress>(credentials.servers);
        java.util.Collections.sort(servers, (a, b) -> Integer.compare(a.getPort() == 3478 ? 0 : 1, b.getPort() == 3478 ? 0 : 1));
        for (InetSocketAddress server : servers) {
            if (closed.get()) throw new IOException("route_closed");
            final TurnUdpClient[] owner = new TurnUdpClient[1];
            TurnUdpClient socket = allocations.create(server, credentials.username, credentials.password, credentials.expiresAt,
                    new TurnUdpClient.Listener() {
                        @Override public void onData(byte[] bytes, InetSocketAddress source) {
                            if (!closed.get() && bytes.length <= 1200) inbound.offer(new Received(owner[0], bytes, source));
                        }
                        @Override public void onFailure(IOException error) {
                            // Initial failure is handled by allocate(); a committed allocation cannot fail over silently.
                            if (owner[0] == current && connected && !closed.get()) closeWithFailure("turn_unavailable");
                        }
                    });
            owner[0] = socket;
            try { socket.allocate(); return socket; }
            catch (IOException e) { failure = e; socket.close(); }
        }
        throw failure != null ? failure : new IOException("turn_unavailable");
    }
    private void update(RelayBrokerClient.Lease response) throws IOException {
        adopt(response);
        InetSocketAddress peer = response.peerEndpoint;
        if (peer == null) return;
        if ((probe != null && !probe.peer.equals(peer)) || (probe == null && (target == null || !target.equals(peer)))) {
            beginProbe(candidate != null ? candidate : current, peer,
                    candidate != null ? response.generation : committedGeneration, response.peerGeneration, candidate != null);
        }
    }
    private void beginProbe(TurnUdpClient socket, InetSocketAddress peer, int ownGeneration, int peerGeneration, boolean rotation) throws IOException {
        socket.bindPeer(peer);
        probe = new Probe(socket, peer, ownGeneration, peerGeneration, rotation, clock.getAsLong()); nextHello = 0; nextCommit = 0;
    }
    private void finishProbe(Probe verified, long now) throws IOException {
        if (probe != verified) return;
        TurnUdpClient previousSocket = current;
        if (verified.rotation) {
            adopt(broker.endpoint(lease.id, verified.ownGeneration, verified.socket.relayAddress(), true));
            synchronized (sendLock) {
                TurnUdpClient old = current; current = verified.socket; candidate = null;
                if (old != current) retired.add(new Retired(old, now + 30_000));
                committedGeneration = verified.ownGeneration;
                currentExpiresAt = candidateExpiresAt;
            }
        } else if (!connected) {
            adopt(broker.endpoint(lease.id, committedGeneration, current.relayAddress(), true));
        }
        synchronized (sendLock) {
            if (target != null && !target.equals(verified.peer)) retiredEndpoints.add(new RetiredEndpoint(previousSocket, target, now + 30_000));
            target = verified.peer; targetGeneration = verified.peerGeneration;
        }
        probe = null;
        if (!connected) {
            connected = true; listener.onConnected(this);
            for (byte[] payload : earlyPackets) listener.onData(payload);
            earlyPackets.clear();
        }
        renewAt = currentExpiresAt - 180_000 + (host ? 0 : 30_000);
    }
    private void drainIncoming() throws IOException {
        for (int i = 0; i < 128; i++) {
            Received received = inbound.poll(); if (received == null) break;
            SecureDatagramChannel crypto = channel;
            if (crypto == null || received.socket == null || !permitted(received.source)) continue;
            byte[] payload = crypto.accept(received.bytes, clock.getAsLong());
            if (payload == null) continue;
            if (payload.length == 10 && payload[0] == CONTROL) {
                long nonce = ByteBuffer.wrap(payload, 2, 8).getLong(); Probe pending = probe;
                if (payload[1] == HELLO) {
                    control(received.socket, received.source, ACK, nonce);
                    if (pending != null && pending.socket == received.socket && pending.peer.equals(received.source)) pending.receivedHello = true;
                    // A peer's rotation must also be probed in the opposite direction.
                    if (pending == null && connected && target != null && target.equals(received.source)) {
                        // Responding to HELLO suffices while our already-verified route is unchanged.
                    }
                } else if (payload[1] == ACK && pending != null && nonce == pending.nonce
                        && pending.socket == received.socket && pending.peer.equals(received.source)) pending.ack = true;
                continue;
            }
            if (payload.length != 0 && payload[0] == CONTROL) continue;
            if (connected) listener.onData(payload);
            else if (earlyPackets.size() < 128) earlyPackets.add(payload);
        }
    }
    private boolean permitted(InetSocketAddress source) {
        Probe pending = probe; RelayBrokerClient.Lease snapshot = lease;
        return source.equals(target) || (pending != null && source.equals(pending.peer))
                || (snapshot != null && source.equals(snapshot.peerEndpoint)) || permittedRetired(source);
    }
    private boolean permittedRetired(InetSocketAddress source) {
        long now = clock.getAsLong();
        for (RetiredEndpoint old : retiredEndpoints) if (now < old.until && old.endpoint.equals(source)) return true;
        return false;
    }
    private void control(TurnUdpClient socket, InetSocketAddress peer, byte kind, long nonce) throws IOException {
        byte[] bytes = ByteBuffer.allocate(10).put(CONTROL).put(kind).putLong(nonce).array();
        for (byte[] packet : channel.encode(bytes)) socket.sendTo(peer, packet);
    }
    private void closeWithFailure(String reason) {
        if (closed.compareAndSet(false, true)) {
            listener.onFailure("peercraft.p2p.fail." + reason);
            if (worker != null) worker.interrupt();
        }
    }
    private static void pause(long millis) throws IOException {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("cancelled"); }
    }
    @Override public void close() {
        if (closed.compareAndSet(false, true) && worker != null) worker.interrupt();
        // Worker owns release/revoke, never block the Minecraft UI in an HTTP request.
    }
    private void closeSockets() {
        TurnUdpClient active = current, pending = candidate; current = null; candidate = null;
        if (active != null) active.close(); if (pending != null && pending != active) pending.close();
        for (Retired old : retired) old.socket.close(); retired.clear(); retiredEndpoints.clear(); inbound.clear(); earlyPackets.clear();
    }
    private static final class Received {
        final TurnUdpClient socket; final byte[] bytes; final InetSocketAddress source;
        Received(TurnUdpClient socket, byte[] bytes, InetSocketAddress source) { this.socket = socket; this.bytes = bytes; this.source = source; }
    }
    private static final class Probe {
        final TurnUdpClient socket; final InetSocketAddress peer;
        final long nonce = new SecureRandom().nextLong(), startedAt;
        final int ownGeneration, peerGeneration; final boolean rotation;
        boolean ack, receivedHello;
        Probe(TurnUdpClient socket, InetSocketAddress peer, int own, int remote, boolean rotation, long now) {
            this.socket = socket; this.peer = peer; ownGeneration = own; peerGeneration = remote; this.rotation = rotation; startedAt = now;
        }
    }
    private static final class Retired {
        final TurnUdpClient socket; final long until;
        Retired(TurnUdpClient socket, long until) { this.socket = socket; this.until = until; }
    }
    private static final class RetiredEndpoint {
        final TurnUdpClient socket; final InetSocketAddress endpoint; final long until;
        RetiredEndpoint(TurnUdpClient socket, InetSocketAddress endpoint, long until) { this.socket = socket; this.endpoint = endpoint; this.until = until; }
    }
    private static final class Identity {
        final String id; final UUID linkId; final byte[] sendKey, receiveKey;
        Identity(RelayBrokerClient.Lease lease) {
            id = lease.id; linkId = lease.linkId; sendKey = lease.sendKey.clone(); receiveKey = lease.receiveKey.clone();
        }
    }
}
