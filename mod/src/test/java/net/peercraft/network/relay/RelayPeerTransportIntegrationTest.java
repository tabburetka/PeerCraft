package net.peercraft.network.relay;
import com.google.gson.*;
import net.peercraft.network.turn.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Actual UDP TURN transactions between independently encrypted peers and an in-memory broker. */
class RelayPeerTransportIntegrationTest {
    @Test void brokerRetentionAndReleaseKeepConnectedRelayUsable() throws Exception {
        try (TurnServer server = new TurnServer()) {
            Broker broker = new Broker(server); Events host = new Events(), join = new Events();
            RelayPeerTransport a = broker.route(true, host), b = broker.route(false, join);
            try {
                a.start(); b.start();
                assertTrue(host.ready.await(5, TimeUnit.SECONDS)); assertTrue(join.ready.await(5, TimeUnit.SECONDS));
                a.retainHandoff(123).get(3, TimeUnit.SECONDS);
                assertEquals(1, broker.retains.get()); assertEquals(123, broker.retainedOffer.get());
                broker.failRelease.set(true); a.releaseHandoff(123);
                await(() -> broker.releaseFailures.get() == 1, 3000);
                byte[] data = {2, 3, 4}; a.send(data);
                assertArrayEquals(data, join.data.poll(3, TimeUnit.SECONDS));
                assertNull(host.reason.get());
                await(() -> broker.releases.get() == 1, 5000);
            } finally { a.close(); b.close(); }
        }
    }
    @Test void largeTransferAllocationRotationAndBudgetShutdown() throws Exception {
        try (TurnServer server = new TurnServer()) {
            Broker broker = new Broker(server); Events host = new Events(), join = new Events();
            RelayPeerTransport a = broker.route(true, host), b = broker.route(false, join);
            try {
                a.start(); b.start();
                assertTrue(host.ready.await(5, TimeUnit.SECONDS)); assertTrue(join.ready.await(5, TimeUnit.SECONDS));
                byte[] large = new byte[60_000]; new Random(12).nextBytes(large); large[0] = (byte) 0xE4;
                a.send(large); assertArrayEquals(large, join.data.poll(5, TimeUnit.SECONDS));
                byte[] reply = {2, 18, 19}; b.send(reply); assertArrayEquals(reply, host.data.poll(3, TimeUnit.SECONDS));
                assertEquals(2, server.allocations.size());
                broker.offset.addAndGet(721_000);
                await(() -> broker.hostRotated(), 8_000);
                assertEquals(3, server.allocations.size(), "Renewal allocates a new username, one endpoint at a time");
                a.send(large); assertArrayEquals(large, join.data.poll(5, TimeUnit.SECONDS));
                b.send(reply); assertArrayEquals(reply, host.data.poll(3, TimeUnit.SECONDS));
                assertTrue(server.maxFrame.get() <= 1200);
                broker.offset.addAndGet(31_000); await(() -> server.deallocated.get() >= 1, 5_000);
                broker.blocked = true;
                assertTrue(host.failed.await(5, TimeUnit.SECONDS)); assertTrue(join.failed.await(5, TimeUnit.SECONDS));
                assertEquals("peercraft.p2p.fail.budget_exhausted", host.reason.get());
                assertEquals("peercraft.p2p.fail.budget_exhausted", join.reason.get());
                assertThrows(IOException.class, () -> a.send(reply)); assertNull(server.failure.get());
            } finally { a.close(); b.close(); }
        }
    }
    @Test void temporaryRenewalFailureKeepsCommittedChannelUsable() throws Exception {
        try (TurnServer server = new TurnServer()) {
            Broker broker = new Broker(server); Events host = new Events(), join = new Events();
            RelayPeerTransport a = broker.route(true, host), b = broker.route(false, join);
            try {
                a.start(); b.start();
                assertTrue(host.ready.await(5, TimeUnit.SECONDS)); assertTrue(join.ready.await(5, TimeUnit.SECONDS));
                broker.failRenew.set(true); broker.offset.addAndGet(721_000);
                await(() -> broker.renewFailures.get() == 1, 5_000);
                byte[] packet = {1, 2, 3}; a.send(packet);
                assertArrayEquals(packet, join.data.poll(3, TimeUnit.SECONDS));
                assertNull(host.reason.get()); assertEquals(2, server.allocations.size());
                broker.offset.addAndGet(11_000);
                await(() -> broker.hostRotated(), 5_000);
                b.send(packet); assertArrayEquals(packet, host.data.poll(3, TimeUnit.SECONDS));
                assertNull(host.reason.get());
            } finally { a.close(); b.close(); }
        }
    }
    @Test void unrelatedQuicDatagramIsIgnoredByLocalTurnFixture() throws Exception {
        try (TurnServer server = new TurnServer(); DatagramSocket foreign = new DatagramSocket()) {
            byte[] quic = new byte[1200]; quic[0] = (byte) 0xC3; quic[4] = 1; quic[5] = 8;
            foreign.send(new DatagramPacket(quic, quic.length, server.endpoint()));
            await(() -> server.foreignDatagrams.get() == 1, 2000);
            assertNull(server.failure.get()); assertTrue(server.allocations.isEmpty());
        }
    }
    @Test void unrelatedQuicFromRegisteredTupleIsIgnoredWithoutWeakeningStunValidation() throws Exception {
        try (TurnServer server=new TurnServer(); DatagramSocket client=new DatagramSocket()) {
            client.setSoTimeout(2000);
            byte[] id=StunCodec.newTransactionId(), key=StunCodec.longTermKey("fixture-user","realm","password");
            List<StunCodec.Attribute> attributes=new ArrayList<>();
            attributes.add(StunCodec.textAttribute(StunCodec.USERNAME,"fixture-user"));
            attributes.add(StunCodec.textAttribute(StunCodec.REALM,"realm"));
            attributes.add(StunCodec.textAttribute(StunCodec.NONCE,"nonce"));
            byte[] allocate=StunCodec.encode(StunCodec.ALLOCATE_REQUEST,id,attributes,key);
            client.send(new DatagramPacket(allocate,allocate.length,server.endpoint()));
            DatagramPacket reply=new DatagramPacket(new byte[2048],2048); client.receive(reply);
            assertEquals(1,server.allocations.size());
            byte[] quic=new byte[1200]; quic[0]=(byte)0xc3; quic[4]=1;
            client.send(new DatagramPacket(quic,quic.length,server.endpoint()));
            client.send(new DatagramPacket(new byte[1],1,server.endpoint()));
            await(() -> server.foreignDatagrams.get()>=2,2000); assertNull(server.failure.get());
            byte[] malformed=StunCodec.encode(StunCodec.ALLOCATE_REQUEST,id,attributes,key);
            malformed[3]++; client.send(new DatagramPacket(malformed,malformed.length,server.endpoint()));
            await(() -> server.failure.get()!=null,2000);
            assertTrue(server.failure.get() instanceof IOException,"Known malformed STUN remains a test failure");
        }
    }
    @Test void cancelledWaitingLeaseNeverAllocatesTurn() throws Exception {
        try (TurnServer server = new TurnServer()) {
            Broker broker = new Broker(server); Events events = new Events(); RelayPeerTransport a = broker.route(true, events);
            a.start(); await(() -> broker.requestedHost(), 2_000); a.close(); await(() -> broker.deletes.get() > 0, 2_000);
            assertEquals(0, server.allocations.size()); assertEquals(1, events.ready.getCount()); assertNull(events.reason.get());
        }
    }
    @Test void changedCipherIdentityFailsClosed() throws Exception {
        try (TurnServer server = new TurnServer()) {
            Broker broker = new Broker(server); Events events = new Events(); RelayPeerTransport a = broker.route(true, events);
            a.start(); await(() -> broker.requestedHost(), 2_000); broker.changedKey = true;
            try { assertTrue(events.failed.await(3, TimeUnit.SECONDS));
                assertEquals("peercraft.p2p.fail.lease_invalid", events.reason.get()); assertEquals(0, server.allocations.size());
            } finally { a.close(); }
        }
    }
    static void await(BooleanSupplier predicate, long millis) throws Exception {
        long end = System.nanoTime() + millis * 1_000_000;
        while (!predicate.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
        assertTrue(predicate.getAsBoolean(), "Timed out waiting for relay state");
    }
    static class Events implements RelayPeerTransport.Listener {
        final CountDownLatch ready = new CountDownLatch(1), failed = new CountDownLatch(1);
        final BlockingQueue<byte[]> data = new LinkedBlockingQueue<>(); final AtomicReference<String> reason = new AtomicReference<>();
        public void onConnected(RelayPeerTransport route) { ready.countDown(); }
        public void onData(byte[] payload) { data.add(payload); }
        public void onFailure(String why) { reason.set(why); failed.countDown(); }
    }
    static class Broker {
        final UUID attempt = UUID.randomUUID(), link = UUID.randomUUID(); final UUID[] ids = {UUID.randomUUID(), UUID.randomUUID()};
        final TurnServer server; final AtomicLong offset = new AtomicLong(); final AtomicInteger deletes = new AtomicInteger();
        volatile boolean blocked, changedKey;
        final AtomicBoolean failRenew = new AtomicBoolean(); final AtomicInteger renewFailures = new AtomicInteger();
        final AtomicInteger retains = new AtomicInteger(), releases = new AtomicInteger();
        final AtomicLong retainedOffer = new AtomicLong();
        final AtomicBoolean failRelease = new AtomicBoolean(); final AtomicInteger releaseFailures = new AtomicInteger();
        final boolean[] requested = new boolean[2]; final int[] generation = {1, 1}, confirmed = {0, 0};
        final InetSocketAddress[] endpoints = new InetSocketAddress[2]; final long[] expires = new long[2];
        Broker(TurnServer server) { this.server = server; expires[0] = expires[1] = now() + 900_000; }
        InetSocketAddress turnEndpoint() { return server.endpoint(); }
        synchronized boolean requestedHost() { return requested[0]; }
        synchronized boolean hostRotated() { return confirmed[0] >= 2 && generation[1] == 1; }
        long now() { return System.currentTimeMillis() + offset.get(); }
        RelayPeerTransport route(boolean host, RelayPeerTransport.Listener events) {
            RelayBrokerClient.Endpoints resolver = new RelayBrokerClient.Endpoints() {
                public InetSocketAddress turn(String name, int port) { return turnEndpoint(); }
                public InetSocketAddress peer(String name, int port) { return new InetSocketAddress(InetAddress.getLoopbackAddress(), port); }
            };
            RelayBrokerClient client = new RelayBrokerClient((method, path, body) -> request(host ? 0 : 1, method, path, body), resolver);
            return new RelayPeerTransport(client, 9, "ROOM", attempt, host, events,
                    (address, user, password, expiry, listener) -> new TurnUdpClient(address, user, password, expiry, listener), this::now);
        }
        synchronized JsonObject request(int role, String method, String path, JsonObject body) throws IOException {
            if (method.equals("DELETE")) { deletes.incrementAndGet(); return new JsonObject(); }
            if (blocked) throw new RelayBrokerClient.Failure("budget_exhausted");
            if (path.equals("/leases")) requested[role] = true;
            else if (path.endsWith("/retain")) { retainedOffer.set(body.get("offerId").getAsLong()); retains.incrementAndGet(); }
            else if (path.endsWith("/release")) {
                if (failRelease.getAndSet(false)) { releaseFailures.incrementAndGet(); throw new IOException("Temporary broker outage"); }
                if (body.get("offerId").getAsLong() != retainedOffer.get()) throw new IOException("Wrong retained offer");
                releases.incrementAndGet();
            }
            else if (path.endsWith("/renew")) {
                if (failRenew.getAndSet(false)) { renewFailures.incrementAndGet(); throw new RelayBrokerClient.Failure("provider_unavailable"); }
                generation[role]++; expires[role] = now() + 900_000; }
            else if (path.endsWith("/endpoint")) {
                endpoints[role] = new InetSocketAddress(InetAddress.getLoopbackAddress(), body.get("port").getAsInt());
                if (body.get("confirmed").getAsBoolean()) confirmed[role] = generation[role];
            }
            JsonObject result = new JsonObject(); result.addProperty("leaseId", ids[role].toString());
            result.addProperty("linkId", link.toString()); result.addProperty("attemptId", attempt.toString());
            result.addProperty("role", role == 0 ? "host" : "joiner"); result.addProperty("state", requested[0] && requested[1] ? "ready" : "waiting");
            result.addProperty("generation", generation[role]); result.addProperty("peerGeneration", generation[1 - role]);
            byte[] out = new byte[16], in = new byte[16]; Arrays.fill(out, (byte) (role + 1)); Arrays.fill(in, (byte) (2 - role));
            if (role == 0 && changedKey) out[0] = 3;
            result.addProperty("sendKey", Base64.getEncoder().encodeToString(out)); result.addProperty("receiveKey", Base64.getEncoder().encodeToString(in));
            if (requested[0] && requested[1]) {
                JsonObject credentials = new JsonObject(); JsonArray urls = new JsonArray(); urls.add("turn:turn.cloudflare.com:3478?transport=udp");
                credentials.add("urls", urls); credentials.addProperty("username", (role == 0 ? "host-" : "join-") + generation[role]);
                credentials.addProperty("password", "password"); credentials.addProperty("expiresAt", expires[role]); result.add("credentials", credentials);
            } else result.add("credentials", JsonNull.INSTANCE);
            InetSocketAddress peer = endpoints[1 - role];
            if (peer != null) { JsonObject address = new JsonObject(); address.addProperty("host", "127.0.0.1"); address.addProperty("port", peer.getPort()); result.add("peerEndpoint", address); }
            else result.add("peerEndpoint", JsonNull.INSTANCE);
            return result;
        }
    }
    static class Allocation {
        final InetSocketAddress client, relay; final Map<Integer, InetSocketAddress> channels = new ConcurrentHashMap<>();
        Allocation(InetSocketAddress client, int port) { this.client = client; relay = new InetSocketAddress(InetAddress.getLoopbackAddress(), port); }
    }
    static class TurnServer implements AutoCloseable {
        final DatagramSocket socket; final Map<InetSocketAddress, Allocation> allocations = new ConcurrentHashMap<>(), relays = new ConcurrentHashMap<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>(); final AtomicInteger deallocated = new AtomicInteger(), maxFrame = new AtomicInteger(), foreignDatagrams = new AtomicInteger();
        volatile boolean closed;
        TurnServer() throws Exception { socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Thread reader = new Thread(this::run, "Two-Peer-Fake-TURN"); reader.setDaemon(true); reader.start(); }
        InetSocketAddress endpoint() { return (InetSocketAddress) socket.getLocalSocketAddress(); }
        void run() {
            while (!closed) try {
                byte[] buffer = new byte[65535]; DatagramPacket packet = new DatagramPacket(buffer, buffer.length); socket.receive(packet);
                byte[] wire = Arrays.copyOf(buffer, packet.getLength()); InetSocketAddress client = (InetSocketAddress) packet.getSocketAddress();
                // TURN has only STUN (00) and ChannelData (01). A network tool may inject
                // unrelated QUIC (11) with the same source tuple as a valid client.
                if (wire.length < 2 || (wire[0] & 0x80) != 0) { foreignDatagrams.incrementAndGet(); continue; }
                int first = ByteBuffer.wrap(wire).getShort() & 65535;
                if (first >= 0x4000 && first <= 0x7fff) {
                    Allocation from = allocations.get(client);
                    if (from == null) { foreignDatagrams.incrementAndGet(); continue; }
                    maxFrame.accumulateAndGet(wire.length, Math::max); Allocation to = relays.get(from.channels.get(first));
                    if (to == null) continue; Integer channel = null;
                    for (Map.Entry<Integer, InetSocketAddress> entry : to.channels.entrySet()) if (entry.getValue().equals(from.relay)) channel = entry.getKey();
                    if (channel == null) continue;
                    wire[0] = (byte) (channel >> 8); wire[1] = (byte) (int) channel; send(wire, to.client); continue;
                }
                StunCodec.Message request;
                try { request = StunCodec.parse(wire); }
                catch (IOException invalid) {
                    // UDP listeners can receive unrelated late traffic after ephemeral-port reuse.
                    // Keep strict assertions for our registered clients, ignore unknown malformed senders.
                    if (!allocations.containsKey(client)) { foreignDatagrams.incrementAndGet(); continue; }
                    ByteBuffer header = ByteBuffer.wrap(wire);
                    throw new IOException("TURN fixture header: type=" + Integer.toHexString(first)
                            + ", actual=" + wire.length + ", declared=" + (header.getShort(2) & 65535)
                            + ", cookie=" + Integer.toHexString(header.getInt(4)), invalid);
                }
                List<StunCodec.Attribute> attributes = new ArrayList<>();
                if (request.attribute(StunCodec.USERNAME) == null) {
                    attributes.add(StunCodec.attribute(StunCodec.ERROR_CODE, new byte[] {0, 0, 4, 1}));
                    attributes.add(StunCodec.textAttribute(StunCodec.REALM, "realm")); attributes.add(StunCodec.textAttribute(StunCodec.NONCE, "nonce"));
                    send(StunCodec.encode(request.type | 0x0110, request.transactionId, attributes, null), client); continue;
                }
                byte[] key = StunCodec.longTermKey(request.attribute(StunCodec.USERNAME).text(), "realm", "password"); assertTrue(StunCodec.verifyIntegrity(request, key));
                if (request.type == StunCodec.ALLOCATE_REQUEST) {
                    Allocation allocation = allocations.computeIfAbsent(client, c -> new Allocation(c, 25000 + allocations.size())); relays.put(allocation.relay, allocation);
                    attributes.add(StunCodec.xorAddressAttribute(StunCodec.XOR_RELAYED_ADDRESS, allocation.relay, request.transactionId)); attributes.add(StunCodec.integerAttribute(StunCodec.LIFETIME, 600));
                } else if (request.type == StunCodec.CHANNEL_BIND_REQUEST) {
                    int channel = ByteBuffer.wrap(request.attribute(StunCodec.CHANNEL_NUMBER).value).getShort() & 65535;
                    allocations.get(client).channels.put(channel, StunCodec.xorAddress(request, StunCodec.XOR_PEER_ADDRESS));
                } else if (request.type == StunCodec.REFRESH_REQUEST) {
                    if (request.attribute(StunCodec.LIFETIME).integer() == 0) { deallocated.incrementAndGet(); continue; }
                    attributes.add(StunCodec.integerAttribute(StunCodec.LIFETIME, 600));
                } else assertEquals(StunCodec.CREATE_PERMISSION_REQUEST, request.type);
                send(StunCodec.encode(request.type | 0x0100, request.transactionId, attributes, key), client);
            } catch (Throwable error) { if (!closed) failure.compareAndSet(null, error); }
        }
        void send(byte[] bytes, InetSocketAddress target) throws IOException { socket.send(new DatagramPacket(bytes, bytes.length, target)); }
        public void close() { closed = true; socket.close(); }
    }
}
