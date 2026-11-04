package net.peercraft.network.control;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Server end of the encrypted control channel. Wraps the rendezvous UDP socket so the request
 * handlers keep working on plaintext: {@code receive} answers handshakes itself and returns only
 * opened (or, while legacy clients are allowed, plain) datagrams; {@code send} seals everything
 * addressed to a client that has a session.
 *
 * <p>Sessions follow the client's latest authenticated address (NAT rebinding), expire after
 * {@link #IDLE_MILLIS} of silence, and are capped at {@link #MAX_SESSIONS}. A datagram for a
 * session the server does not know is answered with a RESET so the client re-keys and resends.
 */
public class SecureServerSocket extends DatagramSocket {
    static final long IDLE_MILLIS = 2 * 60 * 60_000L;
    static final int MAX_SESSIONS = 100_000;
    static final int HANDSHAKES_PER_WINDOW = 30;
    static final int RESETS_PER_WINDOW = 30;
    static final long LIMIT_WINDOW_MILLIS = 10_000L;
    private static final long SWEEP_EVERY_MILLIS = 60_000L;

    private static final class Session {
        final ControlProtocol.Keys keys;
        final AtomicLong nextCounter = new AtomicLong();
        final ReplayWindow window = new ReplayWindow();
        volatile InetSocketAddress address;
        volatile long lastSeen;

        Session(ControlProtocol.Keys keys, InetSocketAddress address, long now) {
            this.keys = keys;
            this.address = address;
            this.lastSeen = now;
        }
    }

    private static final class Budget {
        long windowStart;
        int handshakes;
        int resets;
    }

    private final ControlIdentity identity;
    private final boolean allowPlaintext;
    private final LongSupplier clock;
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, Session> byAddress = new ConcurrentHashMap<>();
    private final Map<InetAddress, Budget> budgets = new ConcurrentHashMap<>();
    private final Object receiveLock = new Object();
    private final byte[] receiveBuffer = new byte[65_536];
    private final DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
    private long lastSweep;

    public SecureServerSocket(int port, ControlIdentity identity, boolean allowPlaintext) throws SocketException {
        this(port, identity, allowPlaintext, System::currentTimeMillis);
    }

    SecureServerSocket(int port, ControlIdentity identity, boolean allowPlaintext, LongSupplier clock) throws SocketException {
        super(port);
        this.identity = identity;
        this.allowPlaintext = allowPlaintext;
        this.clock = clock;
    }

    public int sessionCount() { return sessions.size(); }

    @Override
    public void receive(DatagramPacket packet) throws IOException {
        synchronized (receiveLock) {
            while (true) {
                receivePacket.setData(receiveBuffer, 0, receiveBuffer.length);
                super.receive(receivePacket);
                int length = receivePacket.getLength();
                InetAddress address = receivePacket.getAddress();
                int port = receivePacket.getPort();
                long now = clock.getAsLong();
                if (now - lastSweep >= SWEEP_EVERY_MILLIS) { lastSweep = now; sweep(now); }
                if (!ControlProtocol.isControl(receiveBuffer, length)) {
                    if (!allowPlaintext) continue;
                    deliver(packet, receiveBuffer, length, address, port);
                    return;
                }
                InetSocketAddress from = new InetSocketAddress(address, port);
                byte type = ControlProtocol.type(receiveBuffer);
                if (type == ControlProtocol.HELLO) {
                    handshake(from, length, now);
                } else if (type == ControlProtocol.DATA && length >= ControlProtocol.DATA_OVERHEAD) {
                    byte[] plain = open(from, length, now);
                    if (plain != null) { deliver(packet, plain, plain.length, address, port); return; }
                }
            }
        }
    }

    @Override
    public void send(DatagramPacket packet) throws IOException {
        InetAddress address = packet.getAddress();
        Session session = address == null ? null : byAddress.get(new InetSocketAddress(address, packet.getPort()));
        if (session == null) {
            if (allowPlaintext) super.send(packet);
            return;
        }
        byte[] wire;
        try {
            wire = ControlProtocol.seal(session.keys, false, session.nextCounter.getAndIncrement(),
                    packet.getData(), packet.getOffset(), packet.getLength());
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not encrypt control datagram", e);
        }
        super.send(new DatagramPacket(wire, wire.length, address, packet.getPort()));
    }

    private void handshake(InetSocketAddress from, int length, long now) throws IOException {
        if (length < ControlProtocol.HELLO_SIZE || !spend(from.getAddress(), now, true)) return;
        if (sessions.size() >= MAX_SESSIONS) { sweep(now); if (sessions.size() >= MAX_SESSIONS) return; }
        ControlProtocol.ServerReply reply = ControlProtocol.answer(identity.keyPair, receiveBuffer, length);
        if (reply == null || sessions.containsKey(reply.keys.sessionId)) return;
        Session session = new Session(reply.keys, from, now);
        sessions.put(reply.keys.sessionId, session);
        byAddress.put(from, session);
        super.send(new DatagramPacket(reply.packet, reply.packet.length, from.getAddress(), from.getPort()));
    }

    private byte[] open(InetSocketAddress from, int length, long now) throws IOException {
        long sessionId = ControlProtocol.sessionId(receiveBuffer);
        long counter = ControlProtocol.counter(receiveBuffer);
        Session session = sessions.get(sessionId);
        if (session == null) {
            if (spend(from.getAddress(), now, false)) {
                byte[] reset = ControlProtocol.reset(sessionId, counter);
                super.send(new DatagramPacket(reset, reset.length, from.getAddress(), from.getPort()));
            }
            return null;
        }
        byte[] plain = ControlProtocol.open(session.keys, true, receiveBuffer, length);
        if (plain == null || !session.window.accept(counter)) return null;
        session.lastSeen = now;
        InetSocketAddress previous = session.address;
        if (!previous.equals(from)) {
            byAddress.remove(previous, session);
            session.address = from;
        }
        byAddress.put(from, session);
        return plain;
    }

    private boolean spend(InetAddress address, long now, boolean handshake) {
        Budget budget = budgets.computeIfAbsent(address, a -> new Budget());
        synchronized (budget) {
            if (now - budget.windowStart >= LIMIT_WINDOW_MILLIS) {
                budget.windowStart = now;
                budget.handshakes = 0;
                budget.resets = 0;
            }
            if (handshake) return ++budget.handshakes <= HANDSHAKES_PER_WINDOW;
            return ++budget.resets <= RESETS_PER_WINDOW;
        }
    }

    private void sweep(long now) {
        for (Iterator<Session> it = sessions.values().iterator(); it.hasNext(); ) {
            Session session = it.next();
            if (now - session.lastSeen < IDLE_MILLIS) continue;
            it.remove();
            byAddress.remove(session.address, session);
        }
        budgets.entrySet().removeIf(entry -> now - entry.getValue().windowStart >= LIMIT_WINDOW_MILLIS);
    }

    private static void deliver(DatagramPacket packet, byte[] data, int length, InetAddress address, int port) {
        byte[] target = packet.getData();
        int copied = Math.min(length, target.length - packet.getOffset());
        System.arraycopy(data, 0, target, packet.getOffset(), copied);
        packet.setLength(copied);
        packet.setAddress(address);
        packet.setPort(port);
    }
}
