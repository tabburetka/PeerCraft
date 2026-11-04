package net.peercraft.network.ownrelay;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.peercraft.network.relay.BulkPacer;
import net.peercraft.network.relay.DirectPeerTransport;
import net.peercraft.network.transport.PeerTransport;
import net.peercraft.network.transport.SecureDatagramChannel;

/**
 * Route through a player's self-hosted peercraft-relay. It shares the P2P UDP socket, so the
 * relay sees the same NAT mapping as every other PeerCraft datagram. The owner (the player who
 * configured the relay code) opens a session and announces it to the peer; the peer joins it.
 * Payloads are encrypted end to end with keys derived from the match secret.
 */
public final class OwnRelayPeerTransport implements PeerTransport {
    public interface Datagrams { void send(byte[] datagram, InetSocketAddress to); }
    public interface Listener {
        /** Owner only: announce the session to the peer. Repeated until the peer joins. */
        void onTicket(InetSocketAddress relay, long sessionId);
        void onConnected(OwnRelayPeerTransport route);
        void onData(byte[] payload);
        /** {@code reason} is a {@code peercraft.p2p.fail.*} translation key. */
        void onFailure(String reason);
    }
    static final long OPEN_TIMEOUT_MILLIS = 6_000, PEER_TIMEOUT_MILLIS = 25_000, JOIN_TIMEOUT_MILLIS = 10_000;
    private static final long RETRY_MILLIS = 500, TICKET_INTERVAL_MILLIS = 1_000, KEEPALIVE_MILLIS = 15_000;
    private static final String FAIL = "peercraft.p2p.fail.";

    private final Datagrams out;
    private final InetSocketAddress relay;
    private final byte[] secret;
    private final boolean owner;
    private final Listener listener;
    private final SecureDatagramChannel channel;
    private final BulkPacer pacer = new BulkPacer();
    private final byte[] nonce = new byte[OwnRelayProtocol.NONCE_BYTES];
    private final AtomicBoolean closed = new AtomicBoolean(), started = new AtomicBoolean();
    private volatile long sessionId;
    private volatile boolean joined, connected;
    private volatile int refusal = -1;
    private volatile Thread worker;

    private OwnRelayPeerTransport(Datagrams out, InetSocketAddress relay, byte[] secret, long sessionId,
                                  byte[] matchKey, UUID attempt, boolean host, Listener listener) {
        if (out == null || relay == null || relay.isUnresolved() || matchKey == null || attempt == null || listener == null)
            throw new IllegalArgumentException("Missing own relay dependencies");
        this.out = out; this.relay = relay; this.secret = secret == null ? null : secret.clone();
        this.owner = secret != null; this.sessionId = sessionId; this.listener = listener;
        byte[] outgoing = DirectPeerTransport.derive(matchKey, host ? "own-relay-host-to-joiner" : "own-relay-joiner-to-host");
        byte[] incoming = DirectPeerTransport.derive(matchKey, host ? "own-relay-joiner-to-host" : "own-relay-host-to-joiner");
        channel = new SecureDatagramChannel(outgoing, incoming, attempt, attempt);
        new SecureRandom().nextBytes(nonce);
    }

    /** The player whose relay code is configured: opens a session and announces it. */
    public static OwnRelayPeerTransport owner(Datagrams out, InetSocketAddress relay, byte[] secret,
                                              byte[] matchKey, UUID attempt, boolean host, Listener listener) {
        if (secret == null || secret.length != OwnRelayProtocol.SECRET_BYTES) throw new IllegalArgumentException("Invalid relay secret");
        return new OwnRelayPeerTransport(out, relay, secret, 0, matchKey, attempt, host, listener);
    }

    /** The other player: joins the session announced through the rendezvous server. */
    public static OwnRelayPeerTransport joiner(Datagrams out, InetSocketAddress relay, long sessionId,
                                               byte[] matchKey, UUID attempt, boolean host, Listener listener) {
        if (sessionId == 0) throw new IllegalArgumentException("Missing relay session");
        return new OwnRelayPeerTransport(out, relay, null, sessionId, matchKey, attempt, host, listener);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Own relay route has already started");
        if (closed.get()) return;
        Thread thread = new Thread(this::run, "PeerCraft-OwnRelay");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    public boolean isOwner() { return owner; }
    public long sessionId() { return sessionId; }
    public InetSocketAddress relay() { return relay; }
    public boolean isConnected() { return connected; }
    public boolean isClosed() { return closed.get(); }
    @Override public String mode() { return "own_relay"; }

    @Override public void send(byte[] payload) throws IOException {
        if (closed.get()) throw new IOException("route_closed");
        if (!connected) throw new IOException("route_not_ready");
        pacer.await(payload);
        long session = sessionId;
        for (byte[] packet : channel.encode(payload)) out.send(OwnRelayProtocol.encodeData(session, packet), relay);
    }

    /**
     * Called for every relay datagram arriving on the P2P socket. Several routes may share one
     * relay (a host with several players), so only packets for this session or request are taken.
     */
    public boolean accept(byte[] packet, int length, InetSocketAddress source) {
        if (closed.get() || !relay.equals(source)) return false;
        int type = OwnRelayProtocol.type(packet, length);
        switch (type) {
            case OwnRelayProtocol.OPENED: {
                if (!owner) return false;
                long opened = OwnRelayProtocol.openedSession(packet, length, secret, nonce);
                if (opened == 0) return false;
                if (sessionId == 0) sessionId = opened;
                return true;
            }
            case OwnRelayProtocol.REFUSED: {
                int reason = OwnRelayProtocol.refusedReason(packet, length, nonce);
                if (reason < 0) return false;
                refusal = reason;
                wake();
                return true;
            }
            case OwnRelayProtocol.JOINED:
                if (owner || !OwnRelayProtocol.isJoined(packet, length, sessionId, nonce)) return false;
                joined = true; wake();
                return true;
            case OwnRelayProtocol.PEER_JOINED:
                if (!owner || sessionId == 0 || OwnRelayProtocol.sessionId(packet, length) != sessionId) return false;
                joined = true; wake();
                return true;
            case OwnRelayProtocol.DATA: {
                if (sessionId == 0 || OwnRelayProtocol.sessionId(packet, length) != sessionId) return false;
                byte[] encrypted = OwnRelayProtocol.dataPayload(packet, length);
                byte[] decoded = encrypted == null ? null : channel.accept(encrypted, System.currentTimeMillis());
                if (decoded != null) {
                    if (!joined) { joined = true; wake(); }
                    listener.onData(decoded);
                }
                return true;
            }
            case OwnRelayProtocol.CLOSE:
                if (sessionId == 0 || OwnRelayProtocol.sessionId(packet, length) != sessionId) return false;
                fail(FAIL + "own_relay_closed");
                return true;
            default:
                return false;
        }
    }

    private void run() {
        try {
            if (owner) openSession(); else joinSession();
            if (closed.get()) return;
            connected = true;
            listener.onConnected(this);
            while (!closed.get()) {
                pause(KEEPALIVE_MILLIS);
                if (!closed.get()) out.send(OwnRelayProtocol.encodeSession(OwnRelayProtocol.KEEPALIVE, sessionId), relay);
            }
        } catch (InterruptedException stopped) {
            // close() interrupts the worker; nothing else to do.
        } catch (Failure failure) {
            fail(failure.reason);
        } catch (RuntimeException unexpected) {
            fail(FAIL + "own_relay_unreachable");
        }
    }

    private void openSession() throws InterruptedException, Failure {
        byte[] request = OwnRelayProtocol.encodeAuth(OwnRelayProtocol.OPEN, secret, nonce, System.currentTimeMillis());
        long deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MILLIS;
        while (!closed.get() && sessionId == 0) {
            checkRefusal();
            if (System.currentTimeMillis() > deadline) throw new Failure(FAIL + "own_relay_unreachable");
            out.send(request, relay); // Identical retransmission: the relay answers it with the same session.
            pause(RETRY_MILLIS);
        }
        deadline = System.currentTimeMillis() + PEER_TIMEOUT_MILLIS;
        long nextTicket = 0;
        while (!closed.get() && !joined) {
            if (System.currentTimeMillis() > deadline) throw new Failure(FAIL + "own_relay_no_peer");
            if (System.currentTimeMillis() >= nextTicket) {
                nextTicket = System.currentTimeMillis() + TICKET_INTERVAL_MILLIS;
                listener.onTicket(relay, sessionId);
                // Also confirms the join if PEER_JOINED was lost.
                out.send(OwnRelayProtocol.encodeSession(OwnRelayProtocol.KEEPALIVE, sessionId), relay);
            }
            pause(RETRY_MILLIS);
        }
    }

    private void joinSession() throws InterruptedException, Failure {
        byte[] request = OwnRelayProtocol.encodeJoin(sessionId, nonce);
        long deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MILLIS;
        while (!closed.get() && !joined) {
            checkRefusal();
            if (System.currentTimeMillis() > deadline) throw new Failure(FAIL + "own_relay_unreachable");
            out.send(request, relay);
            pause(RETRY_MILLIS);
        }
    }

    private void checkRefusal() throws Failure {
        switch (refusal) {
            case -1: return;
            case OwnRelayProtocol.REASON_AUTH: throw new Failure(FAIL + "own_relay_auth");
            case OwnRelayProtocol.REASON_STALE: throw new Failure(FAIL + "own_relay_clock");
            case OwnRelayProtocol.REASON_FULL:
            case OwnRelayProtocol.REASON_RATE: throw new Failure(FAIL + "own_relay_busy");
            default: throw new Failure(FAIL + "own_relay_expired");
        }
    }

    private synchronized void pause(long millis) throws InterruptedException {
        if (!closed.get()) wait(millis);
    }
    private synchronized void wake() { notifyAll(); }

    private void fail(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        stopWorker();
        listener.onFailure(reason);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        long session = sessionId;
        if (session != 0 && (owner || joined)) {
            try { out.send(OwnRelayProtocol.encodeSession(OwnRelayProtocol.CLOSE, session), relay); }
            catch (RuntimeException ignored) { /* The relay times the session out instead. */ }
        }
        stopWorker();
    }

    private void stopWorker() {
        Thread thread = worker;
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
        wake();
    }

    private static final class Failure extends Exception {
        final String reason;
        Failure(String reason) { super(reason, null, false, false); this.reason = reason; }
    }
}
