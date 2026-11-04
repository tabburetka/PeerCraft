package net.peercraft.network.control;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A {@link DatagramSocket} that transparently encrypts everything it exchanges with the
 * rendezvous server (see {@link ControlProtocol}) and leaves every other address untouched.
 * Callers keep using plain {@code send}/{@code receive}: datagrams to the server are held until
 * the handshake finishes and then sealed; datagrams from the server are opened and handed over
 * as the original plaintext, and anything from the server that does not authenticate is dropped.
 *
 * <p>Which destinations count as "the server" is either every destination ({@link #forServer()},
 * for sockets that only talk to the server) or the endpoints announced through
 * {@link #registerServer} (the shared P2P socket, which also carries peer traffic).
 *
 * <p>An unknown self-hosted server that never answers the handshake is assumed to be an older
 * plaintext-only server; a pinned or previously seen one never is (see {@link ControlTrust}).
 */
public class SecureControlSocket extends DatagramSocket {
    static final long HELLO_RETRY_MILLIS = 1_000L;
    // Loopback answers in microseconds; local test rigs and fakes should not wait seconds.
    static final long LOOPBACK_HELLO_RETRY_MILLIS = 150L;
    static final int HELLO_ATTEMPTS_BEFORE_FALLBACK = 3;
    static final long LEGACY_RECHECK_MILLIS = 10 * 60_000L;
    static final long QUEUED_TTL_MILLIS = 30_000L;
    static final int MAX_QUEUED = 32;
    static final int MAX_RECENT = 32;
    static final long RECENT_TTL_MILLIS = 10_000L;

    private static final Set<InetSocketAddress> SERVERS = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "PeerCraft-Control-Handshake");
        thread.setDaemon(true);
        return thread;
    });

    private enum State { HANDSHAKING, READY, LEGACY }

    private static final class Pending {
        final byte[] data;
        final long at;
        Pending(byte[] data, long at) { this.data = data; this.at = at; }
    }

    private static final class Sent {
        final long counter;
        final byte[] data;
        final long at;
        Sent(long counter, byte[] data, long at) { this.counter = counter; this.data = data; this.at = at; }
    }

    /** Everything this socket knows about one server endpoint. */
    private static final class Link {
        final InetSocketAddress endpoint;
        final String name;
        State state = State.HANDSHAKING;
        ControlProtocol.ClientHello hello;
        long helloSentAt;
        int helloAttempts;
        long legacySince;
        long deadSessionId;
        boolean trustWarned;
        ControlProtocol.Keys keys;
        long nextCounter;
        ReplayWindow window;
        final ArrayDeque<Pending> queue = new ArrayDeque<>();
        final ArrayDeque<Sent> recent = new ArrayDeque<>();

        Link(InetSocketAddress endpoint) {
            this.endpoint = endpoint;
            this.name = nameOf(endpoint.getAddress());
        }

        long retryMillis() {
            return endpoint.getAddress().isLoopbackAddress() ? LOOPBACK_HELLO_RETRY_MILLIS : HELLO_RETRY_MILLIS;
        }

        boolean requiresEncryption() {
            return ControlTrust.requiresEncryption(name, endpoint.getPort(), endpoint.getAddress());
        }
    }

    private final boolean everyDestinationIsServer;
    private final Object lock = new Object();
    private final Object receiveLock = new Object();
    private final Map<InetSocketAddress, Link> links = new HashMap<>();
    private final byte[] receiveBuffer = new byte[65_536];
    private final DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
    private volatile ScheduledFuture<?> ticker;

    private SecureControlSocket(boolean everyDestinationIsServer) throws SocketException {
        super();
        this.everyDestinationIsServer = everyDestinationIsServer;
    }

    private SecureControlSocket(int port, boolean everyDestinationIsServer) throws SocketException {
        super(port);
        this.everyDestinationIsServer = everyDestinationIsServer;
    }

    /** A socket that only ever talks to the rendezvous server (any destination is treated as it). */
    public static SecureControlSocket forServer() throws SocketException {
        return new SecureControlSocket(true);
    }

    /** The shared P2P socket: encrypts only towards endpoints passed to {@link #registerServer}. */
    public static SecureControlSocket shared(int port) throws SocketException {
        return new SecureControlSocket(port, false);
    }

    /** Marks {@code address:port} as a rendezvous server for shared sockets. */
    public static void registerServer(InetAddress address, int port) {
        if (address != null && port > 0) SERVERS.add(new InetSocketAddress(address, port));
    }

    private boolean isServer(InetAddress address, int port) {
        return everyDestinationIsServer || SERVERS.contains(new InetSocketAddress(address, port));
    }

    @Override
    public void send(DatagramPacket packet) throws IOException {
        InetAddress address = packet.getAddress();
        int port = packet.getPort();
        if (address == null) { address = getInetAddress(); port = getPort(); }
        if (address == null || !isServer(address, port)) { super.send(packet); return; }
        byte[] plain = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), plain, 0, plain.length);
        InetSocketAddress endpoint = new InetSocketAddress(address, port);
        long now = System.currentTimeMillis();
        byte[] hello = null;
        Sent sealed = null;
        ControlProtocol.Keys keys = null;
        boolean plaintext = false;
        synchronized (lock) {
            Link link = links.get(endpoint);
            if (link == null) { link = new Link(endpoint); links.put(endpoint, link); }
            if (link.state == State.LEGACY && now - link.legacySince >= LEGACY_RECHECK_MILLIS) restartHandshake(link);
            switch (link.state) {
                case LEGACY:
                    // An older plaintext-only server, decided earlier: talk to it as before.
                    plaintext = true;
                    break;
                case READY:
                    keys = link.keys;
                    sealed = remember(link, plain, now);
                    break;
                default:
                    if (link.queue.size() >= MAX_QUEUED) link.queue.pollFirst();
                    link.queue.addLast(new Pending(plain, now));
                    hello = helloIfDue(link, now);
                    ensureTicker();
            }
        }
        if (plaintext) super.send(packet);
        else if (keys != null) sendSealed(keys, sealed, address, port);
        else if (hello != null) super.send(new DatagramPacket(hello, hello.length, address, port));
    }

    @Override
    public void receive(DatagramPacket packet) throws IOException {
        synchronized (receiveLock) {
            while (true) {
                receivePacket.setData(receiveBuffer, 0, receiveBuffer.length);
                super.receive(receivePacket);
                int length = receivePacket.getLength();
                InetAddress address = receivePacket.getAddress();
                int port = receivePacket.getPort();
                if (!isServer(address, port)) { deliver(packet, receiveBuffer, length, address, port); return; }
                InetSocketAddress endpoint = new InetSocketAddress(address, port);
                if (ControlProtocol.isControl(receiveBuffer, length)) {
                    byte type = ControlProtocol.type(receiveBuffer);
                    if (type == ControlProtocol.HELLO_REPLY) onReply(endpoint, length);
                    else if (type == ControlProtocol.RESET && length == ControlProtocol.RESET_SIZE) onReset(endpoint);
                    else if (type == ControlProtocol.DATA) {
                        byte[] plain = open(endpoint, length);
                        if (plain != null) { deliver(packet, plain, plain.length, address, port); return; }
                    }
                    continue;
                }
                if (acceptsPlaintext(endpoint)) { deliver(packet, receiveBuffer, length, address, port); return; }
                // Plaintext claiming to be our encrypted server: spoofed or downgraded, drop it.
            }
        }
    }

    @Override
    public void close() {
        ScheduledFuture<?> running = ticker;
        if (running != null) running.cancel(false);
        super.close();
    }

    private boolean acceptsPlaintext(InetSocketAddress endpoint) {
        synchronized (lock) {
            Link link = links.get(endpoint);
            if (link != null) return link.state == State.LEGACY;
        }
        return !ControlTrust.requiresEncryption(nameOf(endpoint.getAddress()), endpoint.getPort(), endpoint.getAddress());
    }

    private void onReply(InetSocketAddress endpoint, int length) throws IOException {
        List<byte[]> flush = new ArrayList<>();
        ControlProtocol.Keys keys;
        synchronized (lock) {
            Link link = links.get(endpoint);
            if (link == null || link.state == State.READY || link.hello == null) return;
            ControlProtocol.Established established = ControlProtocol.finishClient(link.hello, receiveBuffer, length);
            if (established == null) return;
            if (!ControlTrust.accept(link.name, endpoint.getPort(), endpoint.getAddress(), established.serverStatic)) {
                if (!link.trustWarned) {
                    link.trustWarned = true;
                    System.err.println("[PeerCraft] Rendezvous server " + link.name + ":" + endpoint.getPort()
                            + " presented an unexpected key " + ControlCrypto.fingerprint(established.serverStatic)
                            + "; refusing to talk to it. If its owner rotated the key, remove its line from "
                            + ControlTrust.storeFile().toAbsolutePath());
                }
                return;
            }
            link.state = State.READY;
            link.keys = established.keys;
            link.nextCounter = 0;
            link.window = new ReplayWindow();
            link.hello = null;
            link.recent.clear();
            long now = System.currentTimeMillis();
            keys = link.keys;
            for (Pending pending : link.queue) {
                if (now - pending.at > QUEUED_TTL_MILLIS) continue;
                flush.add(pending.data);
            }
            link.queue.clear();
            List<Sent> sealed = new ArrayList<>();
            for (byte[] data : flush) sealed.add(remember(link, data, now));
            for (Sent sent : sealed) sendSealed(keys, sent, endpoint.getAddress(), endpoint.getPort());
        }
    }

    private void onReset(InetSocketAddress endpoint) throws IOException {
        long sessionId = ControlProtocol.sessionId(receiveBuffer);
        long counter = ControlProtocol.counter(receiveBuffer);
        byte[] hello;
        synchronized (lock) {
            Link link = links.get(endpoint);
            if (link == null || link.state != State.READY || link.keys.sessionId != sessionId) return;
            // The server lost this session (restart or expiry) and processed nothing sent under
            // it from `counter` on: re-key and resend exactly those datagrams.
            long now = System.currentTimeMillis();
            List<Sent> lost = new ArrayList<>();
            for (Sent sent : link.recent) if (sent.counter >= counter && now - sent.at <= RECENT_TTL_MILLIS) lost.add(sent);
            restartHandshake(link);
            link.deadSessionId = sessionId;
            for (Sent sent : lost) link.queue.addLast(new Pending(sent.data, now));
            hello = helloIfDue(link, now);
            ensureTicker();
        }
        if (hello != null) super.send(new DatagramPacket(hello, hello.length, endpoint.getAddress(), endpoint.getPort()));
    }

    private byte[] open(InetSocketAddress endpoint, int length) {
        ControlProtocol.Keys keys;
        ReplayWindow window;
        synchronized (lock) {
            Link link = links.get(endpoint);
            if (link == null || link.state != State.READY) return null;
            keys = link.keys;
            window = link.window;
        }
        byte[] plain = ControlProtocol.open(keys, false, receiveBuffer, length);
        if (plain == null || !window.accept(ControlProtocol.counter(receiveBuffer))) return null;
        return plain;
    }

    private void restartHandshake(Link link) {
        link.state = State.HANDSHAKING;
        link.keys = null;
        link.window = null;
        link.hello = null;
        link.helloAttempts = 0;
        link.helloSentAt = 0;
        link.queue.clear();
        link.recent.clear();
    }

    /** Must hold {@link #lock}. Returns the HELLO to send now, if one is due. */
    private byte[] helloIfDue(Link link, long now) {
        if (link.hello != null && now - link.helloSentAt < link.retryMillis()) return null;
        if (link.hello == null) link.hello = new ControlProtocol.ClientHello();
        link.helloSentAt = now;
        link.helloAttempts++;
        return link.hello.packet();
    }

    /** Must hold {@link #lock}. Assigns the next counter and keeps the plaintext for a possible RESET. */
    private Sent remember(Link link, byte[] plain, long now) {
        Sent sent = new Sent(link.nextCounter++, plain, now);
        link.recent.addLast(sent);
        while (link.recent.size() > MAX_RECENT) link.recent.pollFirst();
        return sent;
    }

    private void sendSealed(ControlProtocol.Keys keys, Sent sent, InetAddress address, int port) throws IOException {
        byte[] wire;
        try {
            wire = ControlProtocol.seal(keys, true, sent.counter, sent.data, 0, sent.data.length);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not encrypt control datagram", e);
        }
        super.send(new DatagramPacket(wire, wire.length, address, port));
    }

    /** Must hold {@link #lock}. */
    private void ensureTicker() {
        if (ticker != null) return;
        ticker = TIMER.scheduleWithFixedDelay(this::tick, 50, 50, TimeUnit.MILLISECONDS);
    }

    /** Retries handshakes and, for unknown servers that never answer, falls back to plaintext. */
    private void tick() {
        if (isClosed()) { ScheduledFuture<?> running = ticker; if (running != null) running.cancel(false); return; }
        List<DatagramPacket> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (lock) {
            boolean active = false;
            for (Link link : links.values()) {
                if (link.state != State.HANDSHAKING) continue;
                for (Iterator<Pending> it = link.queue.iterator(); it.hasNext(); ) if (now - it.next().at > QUEUED_TTL_MILLIS) it.remove();
                if (link.queue.isEmpty()) continue;
                active = true;
                if (now - link.helloSentAt < link.retryMillis()) continue;
                if (link.helloAttempts >= HELLO_ATTEMPTS_BEFORE_FALLBACK && !link.requiresEncryption()) {
                    link.state = State.LEGACY;
                    link.legacySince = now;
                    link.hello = null;
                    for (Pending pending : link.queue)
                        out.add(new DatagramPacket(pending.data, pending.data.length, link.endpoint.getAddress(), link.endpoint.getPort()));
                    link.queue.clear();
                    continue;
                }
                byte[] hello = helloIfDue(link, now);
                if (hello != null) out.add(new DatagramPacket(hello, hello.length, link.endpoint.getAddress(), link.endpoint.getPort()));
            }
            // Nothing waiting on a handshake: stop ticking until the next one starts.
            if (!active && ticker != null) { ticker.cancel(false); ticker = null; }
        }
        for (DatagramPacket packet : out) {
            try { super.send(packet); } catch (IOException ignored) { }
        }
    }

    private static void deliver(DatagramPacket packet, byte[] data, int length, InetAddress address, int port) {
        byte[] target = packet.getData();
        int length0 = Math.min(length, target.length - packet.getOffset());
        System.arraycopy(data, 0, target, packet.getOffset(), length0);
        packet.setLength(length0);
        packet.setAddress(address);
        packet.setPort(port);
    }

    /** Host name the caller resolved, without a reverse lookup; the IP literal otherwise. */
    static String nameOf(InetAddress address) {
        String text = address.toString();
        int slash = text.indexOf('/');
        String host = slash > 0 ? text.substring(0, slash) : "";
        return host.isEmpty() ? address.getHostAddress() : host;
    }
}
