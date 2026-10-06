package net.peercraft.network.turn;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One UDP allocation, one socket reader and bounded endpoints for one logical peer. Java 8 only. */
public final class TurnUdpClient implements AutoCloseable {
    public interface Listener {
        void onData(byte[] payload, InetSocketAddress source);
        void onFailure(IOException failure);
    }
    private static final int MAX_PEER_ENDPOINTS = 4;
    private static final long[] RETRY_MILLIS = { 250L, 500L, 1000L, 2000L, 2000L };
    private final DatagramSocket socket;
    private final InetSocketAddress server;
    private final String username, password;
    private final long expiresAtMillis;
    private final Listener listener;
    private final Map<String, Transaction> transactions = new ConcurrentHashMap<String, Transaction>();
    private final Map<InetSocketAddress, Binding> bindings = new ConcurrentHashMap<InetSocketAddress, Binding>();
    private final Map<Integer, Binding> channels = new ConcurrentHashMap<Integer, Binding>();
    private final ScheduledExecutorService maintenance;
    private final ThreadPoolExecutor deliveries;
    private final AtomicBoolean closed = new AtomicBoolean(), failed = new AtomicBoolean();
    private volatile InetSocketAddress relay, peer;
    private volatile long refreshAtNanos;
    private int nextChannel = 0x4000;
    private volatile String realm, nonce;
    private volatile byte[] integrityKey;

    public TurnUdpClient(InetSocketAddress server, String username, String password, Listener listener) throws IOException {
        this(server, username, password, Long.MAX_VALUE, listener);
    }
    public TurnUdpClient(InetSocketAddress server, String username, String password, long expiresAtMillis, Listener listener) throws IOException {
        if (server == null || server.isUnresolved() || server.getPort() == 0 || listener == null)
            throw new IllegalArgumentException("A resolved TURN endpoint and listener are required");
        if (username == null || username.isEmpty() || password == null || password.isEmpty())
            throw new IllegalArgumentException("Missing temporary TURN credentials");
        this.server = server; this.username = username; this.password = password;
        this.expiresAtMillis = expiresAtMillis; this.listener = listener;
        socket = new DatagramSocket(); socket.connect(server); // Filters all other endpoints in the kernel.
        deliveries = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(512), daemonFactory("PeerCraft-TURN-data"), new ThreadPoolExecutor.DiscardPolicy());
        maintenance = Executors.newSingleThreadScheduledExecutor(daemonFactory("PeerCraft-TURN-refresh"));
        Thread reader = daemonFactory("PeerCraft-TURN-reader").newThread(new Runnable() { public void run() { readLoop(); } });
        reader.start();
        maintenance.scheduleWithFixedDelay(new Runnable() { public void run() { maintain(); } }, 1, 1, TimeUnit.SECONDS);
    }

    /** Blocking control work; call on a connection worker, never on the shared UDP reader. */
    public synchronized InetSocketAddress allocate() throws IOException {
        ensureOpen();
        if (relay != null) return relay;
        StunCodec.Message result = request(StunCodec.ALLOCATE_REQUEST, new Attributes() {
            public List<StunCodec.Attribute> create(byte[] id) {
                List<StunCodec.Attribute> values = new ArrayList<StunCodec.Attribute>();
                values.add(StunCodec.attribute(StunCodec.REQUESTED_TRANSPORT, new byte[] { 17, 0, 0, 0 }));
                values.add(StunCodec.integerAttribute(StunCodec.LIFETIME, 600)); return values;
            }
        });
        InetSocketAddress address = StunCodec.xorAddress(result, StunCodec.XOR_RELAYED_ADDRESS);
        if (address.getAddress().isAnyLocalAddress() || address.getAddress().isMulticastAddress()) throw new IOException("Invalid TURN relay endpoint");
        updateLifetime(result); relay = address; return address;
    }

    public synchronized void bindPeer(final InetSocketAddress address) throws IOException {
        ensureOpen();
        if (relay == null) throw new IOException("TURN allocation is not ready");
        if (address == null || address.isUnresolved() || address.getPort() == 0 || address.getAddress().isAnyLocalAddress()
                || address.getAddress().isMulticastAddress()) throw new IOException("Invalid TURN peer address");
        Binding binding = bindings.get(address);
        if (binding == null) {
            if (bindings.size() >= MAX_PEER_ENDPOINTS || nextChannel > 0x7fff) throw new IOException("TURN peer endpoint limit reached");
            binding = new Binding(address, nextChannel++);
        }
        createPermission(address); channelBind(binding);
        binding.refreshAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
        bindings.put(address, binding); channels.put(binding.channel, binding); peer = address;
    }
    /** Remove a retired rotation endpoint. Its channel number is never reused by this allocation. */
    public synchronized void retirePeer(InetSocketAddress address) {
        Binding binding = bindings.remove(address);
        if (binding != null) channels.remove(binding.channel, binding);
        if (address != null && address.equals(peer)) peer = null;
    }
    public InetSocketAddress relayAddress() { return relay; }
    public InetSocketAddress peerAddress() { return peer; }
    public InetSocketAddress serverAddress() { return server; }
    public long expiresAtMillis() { return expiresAtMillis; }

    public void send(byte[] payload) throws IOException {
        InetSocketAddress target = peer;
        if (target == null) throw new IOException("TURN peer is not bound");
        sendTo(target, payload);
    }
    public void sendTo(InetSocketAddress address, byte[] payload) throws IOException {
        ensureOpen();
        Binding binding = bindings.get(address);
        if (binding == null) throw new IOException("TURN peer is not bound");
        if (payload == null || payload.length > 1196) throw new IOException("Fragment TURN payloads before sending");
        ByteBuffer frame = ByteBuffer.allocate(4 + payload.length);
        frame.putShort((short) binding.channel).putShort((short) payload.length).put(payload);
        sendWire(frame.array());
    }

    /** Explicit refresh is also useful for deterministic local protocol verification. */
    public synchronized void refresh() throws IOException {
        ensureOpen();
        if (relay == null) throw new IOException("TURN allocation is not ready");
        updateLifetime(refreshRequest(600));
    }
    private StunCodec.Message refreshRequest(final int lifetime) throws IOException {
        return request(StunCodec.REFRESH_REQUEST, new Attributes() {
            public List<StunCodec.Attribute> create(byte[] id) {
                List<StunCodec.Attribute> values = new ArrayList<StunCodec.Attribute>();
                values.add(StunCodec.integerAttribute(StunCodec.LIFETIME, lifetime)); return values;
            }
        });
    }
    private void createPermission(final InetSocketAddress address) throws IOException {
        request(StunCodec.CREATE_PERMISSION_REQUEST, new Attributes() {
            public List<StunCodec.Attribute> create(byte[] id) throws IOException {
                List<StunCodec.Attribute> values = new ArrayList<StunCodec.Attribute>();
                values.add(StunCodec.xorAddressAttribute(StunCodec.XOR_PEER_ADDRESS, address, id)); return values;
            }
        });
    }
    private void channelBind(final Binding binding) throws IOException {
        request(StunCodec.CHANNEL_BIND_REQUEST, new Attributes() {
            public List<StunCodec.Attribute> create(byte[] id) throws IOException {
                List<StunCodec.Attribute> values = new ArrayList<StunCodec.Attribute>();
                values.add(StunCodec.attribute(StunCodec.CHANNEL_NUMBER, new byte[] { (byte) (binding.channel >>> 8), (byte) binding.channel, 0, 0 }));
                values.add(StunCodec.xorAddressAttribute(StunCodec.XOR_PEER_ADDRESS, binding.address, id)); return values;
            }
        });
    }
    private StunCodec.Message request(int type, Attributes builder) throws IOException {
        for (int challenge = 0; challenge < 3; challenge++) {
            ensureOpen(); byte[] id = StunCodec.newTransactionId();
            List<StunCodec.Attribute> values = builder.create(id);
            byte[] key = integrityKey;
            if (key != null) {
                values.add(StunCodec.textAttribute(StunCodec.USERNAME, username));
                values.add(StunCodec.textAttribute(StunCodec.REALM, realm));
                values.add(StunCodec.textAttribute(StunCodec.NONCE, nonce));
            }
            StunCodec.Message response = transact(type, id, StunCodec.encode(type, id, values, key), key);
            if (response.type == (type | 0x0100)) {
                if (key == null) throw new IOException("TURN success did not authenticate temporary credentials");
                return response;
            }
            int error = response.errorCode();
            if ((error == 401 && key == null) || (error == 438 && key != null)) {
                StunCodec.Attribute newRealm = response.attribute(StunCodec.REALM), newNonce = response.attribute(StunCodec.NONCE);
                if (newRealm == null || newNonce == null || newRealm.value.length == 0 || newRealm.value.length > 256
                        || newNonce.value.length == 0 || newNonce.value.length > 1024) throw new IOException("TURN challenge omitted realm or nonce");
                realm = newRealm.text(); nonce = newNonce.text(); integrityKey = StunCodec.longTermKey(username, realm, password);
                continue;
            }
            throw new IOException("TURN request failed (" + error + ")");
        }
        throw new IOException("TURN authentication challenges exceeded retry limit");
    }
    private StunCodec.Message transact(int type, byte[] id, byte[] bytes, byte[] key) throws IOException {
        String identity = hex(id); Transaction transaction = new Transaction(type, key);
        transactions.put(identity, transaction);
        try {
            for (long timeout : RETRY_MILLIS) {
                ensureOpen(); sendWire(bytes);
                try { return transaction.result.get(timeout, TimeUnit.MILLISECONDS); }
                catch (TimeoutException timeoutException) { /* Retry the exact transaction. */ }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("TURN operation interrupted", ex); }
                catch (ExecutionException ex) { throw new IOException("TURN operation stopped", ex.getCause()); }
            }
            throw new IOException("TURN server did not answer a validated request");
        } finally { transactions.remove(identity, transaction); }
    }
    private void readLoop() {
        byte[] buffer = new byte[65535];
        while (!closed.get()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                if (!server.equals(packet.getSocketAddress())) continue;
                byte[] bytes = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
                if (bytes.length < 4) continue;
                int first = ByteBuffer.wrap(bytes).getShort() & 0xffff;
                if (first >= 0x4000 && first <= 0x7fff) {
                    int length = ByteBuffer.wrap(bytes).getShort(2) & 0xffff;
                    Binding binding = channels.get(first);
                    if (binding != null && length <= 1196 && bytes.length >= 4 + length
                            && bytes.length <= 4 + ((length + 3) & ~3)) deliver(Arrays.copyOfRange(bytes, 4, 4 + length), binding.address);
                    continue;
                }
                StunCodec.Message message;
                try { message = StunCodec.parse(bytes); } catch (IOException malformed) { continue; }
                if (message.type == StunCodec.DATA_INDICATION) {
                    StunCodec.Attribute data = message.attribute(StunCodec.DATA);
                    try {
                        InetSocketAddress target = StunCodec.xorAddress(message, StunCodec.XOR_PEER_ADDRESS);
                        if (bindings.containsKey(target) && data != null && data.value.length <= 1196) deliver(data.value.clone(), target);
                    } catch (IOException malformed) { /* Untrusted indication. */ }
                    continue;
                }
                Transaction transaction = transactions.get(hex(message.transactionId));
                if (transaction == null || (message.type != (transaction.type | 0x0100) && message.type != (transaction.type | 0x0110))) continue;
                try {
                    if (transaction.key != null && !StunCodec.verifyIntegrity(message, transaction.key)) {
                        // RFC 8489 permits 401/438 challenges without integrity. An integrity
                        // attribute that is present must still validate; all successes require it.
                        if (message.attribute(StunCodec.MESSAGE_INTEGRITY) != null || message.type != (transaction.type | 0x0110)) continue;
                        int code = message.errorCode();
                        if (code != 401 && code != 438) continue;
                        StunCodec.Attribute challengedRealm = message.attribute(StunCodec.REALM);
                        if (code == 438 && (challengedRealm == null || !realm.equals(challengedRealm.text()))) continue;
                    }
                    transaction.result.complete(message);
                } catch (IOException malformed) { /* Ignore invalid integrity without consuming transaction. */ }
            } catch (SocketException ex) { if (!closed.get()) fail(new IOException("TURN socket failed", ex)); }
            catch (IOException ex) { if (!closed.get()) fail(ex); }
        }
    }
    private void deliver(final byte[] bytes, final InetSocketAddress source) {
        try {
            deliveries.execute(new Runnable() { public void run() {
                if (!closed.get()) try { listener.onData(bytes, source); } catch (RuntimeException ignored) { /* Listener cannot kill socket reader. */ }
            } });
        } catch (RejectedExecutionException ignored) { /* Concurrent close or bounded queue loss. */ }
    }
    private synchronized void maintain() {
        if (closed.get() || relay == null) return;
        try {
            ensureOpen(); long now = System.nanoTime();
            if (now >= refreshAtNanos) refresh();
            for (Binding binding : bindings.values()) if (now >= binding.refreshAtNanos) {
                createPermission(binding.address); channelBind(binding); binding.refreshAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
            }
        } catch (IOException ex) { fail(ex); }
    }
    private void updateLifetime(StunCodec.Message response) throws IOException {
        StunCodec.Attribute attribute = response.attribute(StunCodec.LIFETIME);
        int lifetime = attribute == null ? 600 : attribute.integer();
        if (lifetime <= 0 || lifetime > 3600) throw new IOException("TURN allocation lifetime is invalid");
        refreshAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(500L, lifetime * 500L));
    }
    private void sendWire(byte[] bytes) throws IOException { socket.send(new DatagramPacket(bytes, bytes.length, server)); }
    private void ensureOpen() throws IOException {
        if (closed.get()) throw new IOException("TURN allocation is closed");
        if (System.currentTimeMillis() >= expiresAtMillis) throw new IOException("Temporary TURN credentials expired");
    }
    private void fail(IOException ex) {
        if (failed.compareAndSet(false, true)) {
            close(); try { listener.onFailure(ex); } catch (RuntimeException ignored) { }
        }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        maintenance.shutdownNow();
        // Best-effort authenticated deallocation, without holding the caller for UDP retries.
        if (relay != null && integrityKey != null && !socket.isClosed()) {
            try {
                List<StunCodec.Attribute> attrs = new ArrayList<StunCodec.Attribute>();
                attrs.add(StunCodec.integerAttribute(StunCodec.LIFETIME, 0));
                attrs.add(StunCodec.textAttribute(StunCodec.USERNAME, username));
                attrs.add(StunCodec.textAttribute(StunCodec.REALM, realm));
                attrs.add(StunCodec.textAttribute(StunCodec.NONCE, nonce));
                sendWire(StunCodec.encode(StunCodec.REFRESH_REQUEST, StunCodec.newTransactionId(), attrs, integrityKey));
            } catch (IOException ignored) { }
        }
        socket.close(); deliveries.shutdownNow();
        IOException stopped = new IOException("TURN allocation closed");
        for (Transaction transaction : transactions.values()) transaction.result.completeExceptionally(stopped);
        transactions.clear(); bindings.clear(); channels.clear(); peer = null;
    }
    private static ThreadFactory daemonFactory(final String name) {
        return new ThreadFactory() { public Thread newThread(Runnable task) { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; } };
    }
    private static String hex(byte[] id) {
        StringBuilder result = new StringBuilder(id.length * 2);
        for (byte value : id) { result.append(Character.forDigit((value >>> 4) & 15, 16)); result.append(Character.forDigit(value & 15, 16)); }
        return result.toString();
    }
    private interface Attributes { List<StunCodec.Attribute> create(byte[] id) throws IOException; }
    private static final class Transaction {
        final int type; final byte[] key; final CompletableFuture<StunCodec.Message> result = new CompletableFuture<StunCodec.Message>();
        Transaction(int type, byte[] key) { this.type = type; this.key = key; }
    }
    private static final class Binding {
        final InetSocketAddress address; final int channel; volatile long refreshAtNanos;
        Binding(InetSocketAddress address, int channel) { this.address = address; this.channel = channel; }
    }
}
