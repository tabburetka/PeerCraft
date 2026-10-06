package net.peercraft.network.connectivity;

import net.peercraft.network.p2p.P2PSender;
import net.peercraft.network.p2p.PeerAddress;
import net.peercraft.network.p2p.RawPacketListener;
import net.peercraft.network.rendezvous.RendezvousProtocol;
import net.peercraft.network.turn.StunCodec;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Actual connectivity checks; a NAT classification never determines their outcome. */
public final class DirectConnectivityCoordinator implements RawPacketListener {
    public static final int MESSAGE_TYPE = RendezvousProtocol.TYPE_CONNECTIVITY_CHECK;
    // Match the largest actual encrypted direct datagram (1200 minus TURN's 4-byte header).
    public static final int PROBE_SIZE = 1196;
    public static final long CHECK_WINDOW_MILLIS = 20_000;
    public static final long FALLBACK_RACE_MILLIS = 30_000;
    private static final long RETRY_MILLIS = 300;
    private static final int HEADER_SIZE = 2 + 1 + 1 + 1 + 16 + 8;
    private static final int TAG_SIZE = 16;
    private static final int MAX_CANDIDATES = 16;
    private static final int PROBE = 1, PROBE_ACK = 2, NOMINATE = 3, NOMINATE_ACK = 4;

    public interface Callback {
        void onSuccess(String ip, int port);
        /** Ends the initial direct phase. A late success may still win until the bridge commits another route. */
        void onFailure(String reason);
    }
    public interface DatagramSender { void send(byte[] bytes, InetAddress address, int port); }

    private final DatagramSender sender;
    private final RendezvousProtocol.NetworkOffer offer;
    private final boolean hostRole;
    private final Callback callback;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<PeerAddress, Check> checks = new LinkedHashMap<>();
    private final Map<PeerAddress, byte[]> discoveryTransactions = new LinkedHashMap<>();
    private final Map<PeerAddress, InetSocketAddress> discoveredMappings = new LinkedHashMap<>();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean initialPhaseExpired = new AtomicBoolean();
    private volatile boolean cancelled;
    private volatile long deadline;
    private volatile long initialDeadline;
    private volatile boolean started;
    private PeerAddress candidateRelay;
    private PeerAddress nominated;
    private PeerAddress selected;

    public DirectConnectivityCoordinator(P2PSender sender, PeerAddress logicalPeer,
                                         RendezvousProtocol.NetworkOffer offer, boolean hostRole, Callback callback) {
        this((bytes, address, port) -> sender.sendData(bytes, address.getHostAddress(), port), logicalPeer,
                offer, hostRole, callback, System::nanoTime);
    }

    /** Injectable sender/clock permit deterministic loss, one-way path and deadline tests. */
    public DirectConnectivityCoordinator(DatagramSender sender, PeerAddress logicalPeer,
                                         RendezvousProtocol.NetworkOffer offer, boolean hostRole,
                                         Callback callback, LongSupplier nanoClock) {
        if (sender == null || logicalPeer == null || offer == null || callback == null || nanoClock == null)
            throw new IllegalArgumentException("Missing direct-check dependency");
        this.sender = sender; this.offer = offer; this.hostRole = hostRole;
        this.callback = callback; this.clock = nanoClock;
        add(logicalPeer);
        for (RendezvousProtocol.Address address : offer.peerCandidates()) {
            if (address.host().isSiteLocalAddress() && !DirectCandidates.isOnLocalSubnet(address.host())) continue;
            if (address.host().isLinkLocalAddress() || address.host().isAnyLocalAddress() || address.host().isMulticastAddress()) continue;
            add(new PeerAddress(address.host(), address.port()));
        }
    }

    public void start() {
        begin();
        Thread worker = new Thread(() -> {
            while (!cancelled && !finished.get()) {
                tick();
                try { Thread.sleep(RETRY_MILLIS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }, "PeerCraft-Direct-Checks");
        worker.setDaemon(true); worker.start();
    }

    /** Optional STUN diagnostics, on the exact same direct socket; never an eligibility verdict. */
    public synchronized void addDiscoveryServer(InetAddress address, int port) {
        if (started || address == null || port < 1 || port > 65535 || discoveryTransactions.size() >= 2)
            throw new IllegalArgumentException("Invalid discovery endpoint");
        discoveryTransactions.put(new PeerAddress(address, port), StunCodec.newTransactionId());
    }

    /** The existing UDP rendezvous forwards only proof-bearing updates for this live match. */
    public synchronized void setCandidateRelay(InetAddress address, int port) {
        if (started || address == null || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid candidate relay");
        candidateRelay = new PeerAddress(address, port);
    }

    public synchronized Map<PeerAddress, InetSocketAddress> discoveredMappings() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(discoveredMappings));
    }

    public synchronized boolean onDiscoveryPacket(byte[] bytes, int length, InetAddress address, int port) {
        if (cancelled || !started || clock.getAsLong() >= deadline || length > bytes.length) return false;
        PeerAddress endpoint = new PeerAddress(address, port);
        byte[] transaction = discoveryTransactions.get(endpoint);
        if (transaction == null) return false;
        try {
            StunCodec.Message message = StunCodec.parse(Arrays.copyOf(bytes, length));
            if (message.type != StunCodec.BINDING_SUCCESS || !message.matches(transaction)) return false;
            InetSocketAddress mapped = StunCodec.mappedAddress(message);
            if (!RendezvousProtocol.isGlobalCandidate(new RendezvousProtocol.Address(mapped.getAddress(), mapped.getPort()))) return false;
            discoveredMappings.put(endpoint, mapped);
            return true;
        } catch (IOException | RuntimeException invalid) { return false; }
    }

    private synchronized void begin() {
        if (!started) {
            started = true;
            initialDeadline = clock.getAsLong() + CHECK_WINDOW_MILLIS * 1_000_000L;
            deadline = initialDeadline + FALLBACK_RACE_MILLIS * 1_000_000L;
        }
    }

    /** One retry iteration, also useful for deterministic simulations. */
    public synchronized void tick() {
        begin();
        if (cancelled || finished.get()) return;
        if (clock.getAsLong() >= initialDeadline && initialPhaseExpired.compareAndSet(false, true)) {
            callback.onFailure("direct_checks_timeout");
            if (cancelled || finished.get()) return;
        }
        if (clock.getAsLong() >= deadline) {
            finished.set(true);
            return;
        }
        for (Map.Entry<PeerAddress, byte[]> discovery : discoveryTransactions.entrySet()) {
            if (discoveredMappings.containsKey(discovery.getKey())) continue;
            try { sender.send(StunCodec.bindingRequest(discovery.getValue()), discovery.getKey().host(), discovery.getKey().port()); }
            catch (IOException ignored) { /* STUN is diagnostic; probe peer candidates anyway. */ }
        }
        if (candidateRelay != null) {
            for (InetSocketAddress mapped : discoveredMappings.values()) {
                byte[] update = RendezvousProtocol.encodeDirectCandidate(offer, hostRole,
                        new RendezvousProtocol.Address(mapped.getAddress(), mapped.getPort()));
                sender.send(update, candidateRelay.host(), candidateRelay.port());
            }
        }
        // Snapshot: loopback test senders may synchronously discover another candidate.
        for (Map.Entry<PeerAddress, Check> entry : new LinkedHashMap<>(checks).entrySet()) {
            if (finished.get() || cancelled) break;
            send(PROBE, entry.getValue().nonce, entry.getKey(), PROBE_SIZE);
        }
        if (hostRole && nominated != null && !finished.get())
            send(NOMINATE, checks.get(nominated).nonce, nominated, HEADER_SIZE + TAG_SIZE);
    }

    /** Each supported candidate has an honest observation, never a claim of universal NAT impossibility. */
    public synchronized Map<PeerAddress, String> diagnostics() {
        Map<PeerAddress, String> result = new LinkedHashMap<>();
        for (Map.Entry<PeerAddress, Check> entry : checks.entrySet()) {
            Check check = entry.getValue();
            String reason = entry.getKey().equals(selected) ? "direct_ready"
                    : check.verified() ? "pair_selection_not_confirmed"
                    : !check.ownAck && !check.peerProbe ? "no_verified_datagrams_in_either_direction"
                    : !check.ownAck ? "probe_round_trip_not_confirmed" : "peer_probe_not_received";
            result.put(entry.getKey(), reason);
        }
        return java.util.Collections.unmodifiableMap(result);
    }
    public boolean isFinished() { return finished.get(); }
    public synchronized PeerAddress selectedPeer() { return selected; }

    @Override public void cancel() { cancelled = true; finished.set(true); }

    @Override public synchronized void onPacket(byte[] bytes, int length, InetAddress address, int port) {
        if (onDiscoveryPacket(bytes, length, address, port)) return;
        if (!cancelled && started && !finished.get() && clock.getAsLong() < deadline
                && RendezvousProtocol.directCandidateAttempt(bytes, length).isPresent()) {
            java.util.Optional<RendezvousProtocol.Address> advertised =
                    RendezvousProtocol.decodeDirectCandidate(bytes, length, offer, !hostRole);
            if (advertised.isPresent() && checks.size() < MAX_CANDIDATES) {
                RendezvousProtocol.Address candidate = advertised.get();
                PeerAddress peer = new PeerAddress(candidate.host(), candidate.port());
                if (!checks.containsKey(peer)) {
                    Check check = add(peer);
                    send(PROBE, check.nonce, peer, PROBE_SIZE);
                }
            }
            return;
        }
        if (cancelled || !started || clock.getAsLong() >= deadline || port < 1 || port > 65535
                || address == null || length < HEADER_SIZE + TAG_SIZE || length > PROBE_SIZE
                || length > bytes.length || bytes[0] != RendezvousProtocol.MAGIC
                || (bytes[1] & 255) != MESSAGE_TYPE) return;
        ByteBuffer b = ByteBuffer.wrap(bytes, 0, length);
        b.position(2);
        int version = b.get() & 255, type = b.get() & 255, role = b.get() & 255;
        if (version != 1 || role != (hostRole ? 0 : 1) || type < PROBE || type > NOMINATE_ACK) return;
        if (b.getLong() != offer.attemptId().getMostSignificantBits()
                || b.getLong() != offer.attemptId().getLeastSignificantBits()) return;
        long nonce = b.getLong();
        byte[] authenticated = Arrays.copyOf(bytes, length - TAG_SIZE);
        byte[] supplied = Arrays.copyOfRange(bytes, length - TAG_SIZE, length);
        if (!MessageDigest.isEqual(supplied, tag(authenticated))) return;
        PeerAddress peer = new PeerAddress(address, port);
        Check check = checks.get(peer);
        // Only a proof-bearing probe learns a remapped source; unauthenticated traffic never changes routing.
        if (check == null) {
            if (type != PROBE || checks.size() >= MAX_CANDIDATES) return;
            check = add(peer);
        }
        if (finished.get() && !peer.equals(selected)) return;
        if (type == PROBE) {
            if (length != PROBE_SIZE) return;
            check.peerProbe = true;
            send(PROBE_ACK, nonce, peer, PROBE_SIZE);
            if (!finished.get() && !check.ownAck) send(PROBE, check.nonce, peer, PROBE_SIZE);
        } else if (type == PROBE_ACK) {
            if (length != PROBE_SIZE || nonce != check.nonce) return;
            check.ownAck = true;
        } else if (type == NOMINATE && !hostRole && check.verified()) {
            // Kept registered by the bridge after success to answer a lost nomination ACK.
            send(NOMINATE_ACK, nonce, peer, HEADER_SIZE + TAG_SIZE);
            succeed(peer);
        } else if (type == NOMINATE_ACK && hostRole && peer.equals(nominated)
                && nonce == check.nonce && check.verified()) {
            succeed(peer);
        }
        if (hostRole && !finished.get() && nominated == null && check.verified()) {
            nominated = peer;
            send(NOMINATE, check.nonce, peer, HEADER_SIZE + TAG_SIZE);
        }
    }

    private void succeed(PeerAddress peer) {
        if (finished.compareAndSet(false, true)) {
            selected = peer;
            callback.onSuccess(peer.host().getHostAddress(), peer.port());
        }
    }

    private Check add(PeerAddress peer) {
        Check old = checks.get(peer);
        if (old != null) return old;
        Check value = new Check(random.nextLong()); checks.put(peer, value); return value;
    }

    private void send(int type, long nonce, PeerAddress peer, int size) {
        ByteBuffer b = ByteBuffer.allocate(size);
        b.put(RendezvousProtocol.MAGIC).put((byte) MESSAGE_TYPE).put((byte) 1).put((byte) type);
        b.put((byte) (hostRole ? 1 : 0));
        b.putLong(offer.attemptId().getMostSignificantBits()).putLong(offer.attemptId().getLeastSignificantBits());
        b.putLong(nonce);
        byte[] bytes = b.array();
        byte[] mac = tag(Arrays.copyOf(bytes, size - TAG_SIZE));
        System.arraycopy(mac, 0, bytes, size - TAG_SIZE, TAG_SIZE);
        sender.send(bytes, peer.host(), peer.port());
    }

    private byte[] tag(byte[] bytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(offer.challengeKey(), "HmacSHA256"));
            return Arrays.copyOf(mac.doFinal(bytes), TAG_SIZE);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is required for direct checks", unavailable);
        }
    }

    private static final class Check {
        final long nonce;
        boolean peerProbe, ownAck;
        Check(long nonce) { this.nonce = nonce; }
        boolean verified() { return peerProbe && ownAck; }
    }
}
