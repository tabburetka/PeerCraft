package net.peercraft.relay;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.peercraft.network.ownrelay.OwnRelayProtocol;

/**
 * Pairs a relay owner with one other player per session and forwards their already encrypted
 * datagrams. Protocol handling is single-threaded: {@link #handle} and {@link #sweep} are
 * called only from the receive loop (or directly by tests with an explicit clock).
 */
final class RelayServer implements AutoCloseable {
    interface Output { void send(byte[] datagram, InetSocketAddress to); }

    private static final int MAX_NONCES = 8192;
    private final RelayConfig config;
    private final Output output;
    private final Consumer<String> log;
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, Session> sessions = new HashMap<>();
    /** OPEN nonce -> session it created, so retransmitted OPENs get the same answer. */
    private final LinkedHashMap<ByteBuffer, Seen> nonces = new LinkedHashMap<>();
    private final Map<InetAddress, ArrayDeque<Long>> opensByIp = new HashMap<>();
    private volatile DatagramSocket socket;
    private volatile boolean closed;

    private static final class Session {
        final long id;
        final InetSocketAddress owner;
        InetSocketAddress joiner;
        final long createdAt;
        long lastActivity;
        final TokenBucket bucket;
        Session(long id, InetSocketAddress owner, long now, TokenBucket bucket) {
            this.id = id; this.owner = owner; this.createdAt = now; this.lastActivity = now; this.bucket = bucket;
        }
        InetSocketAddress other(InetSocketAddress from) {
            if (from.equals(owner)) return joiner;
            return from.equals(joiner) ? owner : null;
        }
    }
    private static final class Seen {
        final long at, sessionId; final InetSocketAddress from;
        Seen(long at, long sessionId, InetSocketAddress from) { this.at = at; this.sessionId = sessionId; this.from = from; }
    }

    RelayServer(RelayConfig config, Output output, Consumer<String> log) {
        this.config = config; this.output = output; this.log = log;
    }

    /** Production loop on a bound socket; returns when {@link #close} is called. */
    static void serve(RelayConfig config, DatagramSocket socket, Consumer<String> log) throws IOException {
        RelayServer server = new RelayServer(config, (datagram, to) -> {
            try { socket.send(new DatagramPacket(datagram, datagram.length, to)); }
            catch (IOException dropped) { /* UDP: the sender retries or times out. */ }
        }, log);
        server.run(socket);
    }

    void run(DatagramSocket bound) throws IOException {
        socket = bound;
        bound.setSoTimeout(1000);
        byte[] buffer = new byte[OwnRelayProtocol.MAX_DATAGRAM + 1];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        long nextSweep = 0;
        while (!closed) {
            try {
                packet.setLength(buffer.length);
                bound.receive(packet);
                handle(buffer, packet.getLength(), (InetSocketAddress) packet.getSocketAddress(),
                        System.currentTimeMillis(), System.nanoTime());
            } catch (SocketTimeoutException idle) {
                // Fall through to the periodic sweep.
            } catch (SocketException closedSocket) {
                if (closed || bound.isClosed()) return;
                throw closedSocket;
            }
            long now = System.currentTimeMillis();
            if (now >= nextSweep) { sweep(now); nextSweep = now + 1000; }
        }
    }

    int activeSessions() { return sessions.size(); }

    void handle(byte[] packet, int length, InetSocketAddress from, long nowMillis, long nowNanos) {
        if (length > OwnRelayProtocol.MAX_DATAGRAM) return;
        int type = OwnRelayProtocol.type(packet, length);
        switch (type) {
            case OwnRelayProtocol.PING -> ping(packet, length, from, nowMillis);
            case OwnRelayProtocol.OPEN -> open(packet, length, from, nowMillis, nowNanos);
            case OwnRelayProtocol.JOIN -> join(packet, length, from, nowMillis);
            case OwnRelayProtocol.DATA -> data(packet, length, from, nowMillis, nowNanos);
            case OwnRelayProtocol.KEEPALIVE -> keepalive(packet, length, from, nowMillis);
            case OwnRelayProtocol.CLOSE -> close(packet, length, from);
            default -> { /* Not ours, or a reply type a client should never send: ignore. */ }
        }
    }

    private void ping(byte[] packet, int length, InetSocketAddress from, long now) {
        byte[] nonce = OwnRelayProtocol.nonce(packet, length);
        if (nonce == null) return;
        int verdict = OwnRelayProtocol.verifyAuth(packet, length, config.secret, now);
        output.send(verdict == 0 ? OwnRelayProtocol.encodePong(config.secret, nonce)
                : OwnRelayProtocol.encodeRefused(nonce, verdict), from);
    }

    private void open(byte[] packet, int length, InetSocketAddress from, long now, long nowNanos) {
        byte[] nonce = OwnRelayProtocol.nonce(packet, length);
        if (nonce == null) return;
        int verdict = OwnRelayProtocol.verifyAuth(packet, length, config.secret, now);
        if (verdict != 0) { output.send(OwnRelayProtocol.encodeRefused(nonce, verdict), from); return; }
        ByteBuffer key = ByteBuffer.wrap(nonce);
        Seen seen = nonces.get(key);
        if (seen != null) {
            // A retransmission from the same owner gets its original session; a replay gets nothing.
            Session existing = sessions.get(seen.sessionId);
            if (existing != null && seen.from.equals(from))
                output.send(OwnRelayProtocol.encodeOpened(config.secret, nonce, existing.id), from);
            return;
        }
        if (!allowOpen(from.getAddress(), now)) { output.send(OwnRelayProtocol.encodeRefused(nonce, OwnRelayProtocol.REASON_RATE), from); return; }
        if (sessions.size() >= config.maxSessions || nonces.size() >= MAX_NONCES) {
            output.send(OwnRelayProtocol.encodeRefused(nonce, OwnRelayProtocol.REASON_FULL), from);
            return;
        }
        long id;
        do { id = random.nextLong(); } while (id == 0 || sessions.containsKey(id));
        sessions.put(id, new Session(id, from, now, new TokenBucket(config.maxBytesPerSecondPerSession, nowNanos)));
        nonces.put(key, new Seen(now, id, from));
        log.accept("Session opened (" + sessions.size() + "/" + config.maxSessions + " active)");
        output.send(OwnRelayProtocol.encodeOpened(config.secret, nonce, id), from);
    }

    private boolean allowOpen(InetAddress ip, long now) {
        ArrayDeque<Long> times = opensByIp.computeIfAbsent(ip, ignored -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > 60_000L) times.pollFirst();
        if (times.size() >= config.opensPerMinutePerIp) return false;
        times.addLast(now);
        return true;
    }

    private void join(byte[] packet, int length, InetSocketAddress from, long now) {
        byte[] nonce = OwnRelayProtocol.nonce(packet, length);
        if (nonce == null || length != OwnRelayProtocol.SESSION_HEADER_BYTES + OwnRelayProtocol.NONCE_BYTES) return;
        Session session = sessions.get(OwnRelayProtocol.sessionId(packet, length));
        if (session == null) { output.send(OwnRelayProtocol.encodeRefused(nonce, OwnRelayProtocol.REASON_UNKNOWN_SESSION), from); return; }
        if (from.equals(session.owner) || (session.joiner != null && !session.joiner.equals(from))) {
            output.send(OwnRelayProtocol.encodeRefused(nonce, OwnRelayProtocol.REASON_TAKEN), from);
            return;
        }
        boolean first = session.joiner == null;
        session.joiner = from;
        session.lastActivity = now;
        output.send(OwnRelayProtocol.encodeJoined(session.id, nonce), from);
        if (first) {
            output.send(OwnRelayProtocol.encodeSession(OwnRelayProtocol.PEER_JOINED, session.id), session.owner);
            log.accept("Second player joined a session");
        }
    }

    private void data(byte[] packet, int length, InetSocketAddress from, long now, long nowNanos) {
        Session session = sessions.get(OwnRelayProtocol.sessionId(packet, length));
        if (session == null) return;
        InetSocketAddress to = session.other(from);
        if (to == null || !session.bucket.take(length, nowNanos)) return;
        session.lastActivity = now;
        output.send(java.util.Arrays.copyOf(packet, length), to);
    }

    private void keepalive(byte[] packet, int length, InetSocketAddress from, long now) {
        Session session = sessions.get(OwnRelayProtocol.sessionId(packet, length));
        if (session == null) return;
        if (from.equals(session.owner)) {
            // Only refresh a session that is in use; an owner alone cannot hold a slot past the pending timeout.
            if (session.joiner != null) {
                session.lastActivity = now;
                output.send(OwnRelayProtocol.encodeSession(OwnRelayProtocol.PEER_JOINED, session.id), from);
            }
        } else if (from.equals(session.joiner)) session.lastActivity = now;
    }

    private void close(byte[] packet, int length, InetSocketAddress from) {
        Session session = sessions.get(OwnRelayProtocol.sessionId(packet, length));
        if (session == null) return;
        InetSocketAddress other = session.other(from);
        if (other == null && !from.equals(session.owner)) return;
        sessions.remove(session.id);
        if (other != null) output.send(OwnRelayProtocol.encodeSession(OwnRelayProtocol.CLOSE, session.id), other);
        log.accept("Session closed by a player (" + sessions.size() + " active)");
    }

    void sweep(long now) {
        for (Iterator<Session> it = sessions.values().iterator(); it.hasNext(); ) {
            Session session = it.next();
            boolean pending = session.joiner == null && now - session.createdAt > config.pendingTimeoutMillis;
            boolean idle = session.joiner != null && now - session.lastActivity > config.idleTimeoutMillis;
            if (!pending && !idle) continue;
            it.remove();
            output.send(OwnRelayProtocol.encodeSession(OwnRelayProtocol.CLOSE, session.id), session.owner);
            if (session.joiner != null) output.send(OwnRelayProtocol.encodeSession(OwnRelayProtocol.CLOSE, session.id), session.joiner);
            log.accept("Session " + (pending ? "expired before the second player joined" : "closed after inactivity")
                    + " (" + sessions.size() + " active)");
        }
        long window = 2 * net.peercraft.network.ownrelay.OwnRelayProtocol.MAX_CLOCK_SKEW_MILLIS;
        for (Iterator<Seen> it = nonces.values().iterator(); it.hasNext(); ) {
            if (now - it.next().at <= window) break; // Insertion order: the rest are newer.
            it.remove();
        }
        opensByIp.values().removeIf(times -> times.isEmpty() || now - times.peekLast() > 60_000L);
    }

    @Override public void close() {
        closed = true;
        DatagramSocket bound = socket;
        if (bound != null) bound.close();
    }
}
