package net.peercraft.network.turn;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TurnUdpClientTest {
    @Test void actualUdpAllocationRetriesAuthenticatesRefreshesAndRelays() throws Exception {
        try (FakeTurn server = new FakeTurn()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<byte[]> delivered = new AtomicReference<byte[]>();
            AtomicReference<IOException> failure = new AtomicReference<IOException>();
            TurnUdpClient client = new TurnUdpClient(server.endpoint(), "user", "password", new TurnUdpClient.Listener() {
                public void onData(byte[] bytes, InetSocketAddress source) {
                    assertEquals(server.relay, source); delivered.set(bytes); received.countDown();
                }
                public void onFailure(IOException ex) { failure.set(ex); }
            });
            try {
                assertEquals(server.relay, client.allocate());
                assertTrue(server.unauthenticatedRequests.get() >= 2); // Initial packet is deliberately lost.
                client.bindPeer(server.relay); // First permission gets an authenticated 438 challenge.
                assertEquals(2, server.permissions.get());
                client.send(new byte[] { 8, 9, 10 });
                assertTrue(received.await(3, TimeUnit.SECONDS));
                assertArrayEquals(new byte[] { 8, 9, 10 }, delivered.get());
                client.refresh(); assertEquals(1, server.refreshes.get()); assertNull(failure.get());
                assertThrows(IOException.class, () -> client.send(new byte[1197]));
                assertThrows(IOException.class, () -> client.sendTo(new InetSocketAddress(InetAddress.getLoopbackAddress(), 24501), new byte[1]));
            } finally { client.close(); }
            assertTrue(server.deallocated.await(2, TimeUnit.SECONDS));
            assertNull(server.failure.get());
        }
    }

    @Test void expiredCredentialsAreNeverAllocated() throws Exception {
        try (FakeTurn server = new FakeTurn(); TurnUdpClient client = new TurnUdpClient(server.endpoint(), "user", "password",
                System.currentTimeMillis() - 1, new TurnUdpClient.Listener() {
            public void onData(byte[] payload, InetSocketAddress source) { fail("No data expected"); }
            public void onFailure(IOException ex) { }
        })) {
            assertThrows(IOException.class, client::allocate);
            assertEquals(0, server.unauthenticatedRequests.get());
        }
    }

    @Test void foreignMalformedDatagramsDoNotStopTurnFixture() throws Exception {
        try (FakeTurn server = new FakeTurn(); DatagramSocket foreign = new DatagramSocket()) {
            byte[] quic = new byte[1200]; quic[0] = (byte) 0xC3; quic[4] = 1;
            foreign.send(new DatagramPacket(quic,quic.length,server.endpoint()));
            foreign.send(new DatagramPacket(new byte[1],1,server.endpoint()));
            try (TurnUdpClient client = new TurnUdpClient(server.endpoint(),"user","password",new TurnUdpClient.Listener() {
                public void onData(byte[] bytes,InetSocketAddress source) { }
                public void onFailure(IOException failure) { }
            })) {
                client.allocate(); client.bindPeer(server.relay); assertNull(server.failure.get());
            }
        }
    }
    @Test void rotationUsesDistinctChannelsAndKeepsOldSourceIdentity() throws Exception {
        try (FakeTurn server = new FakeTurn(false)) {
            InetSocketAddress nextPeer = new InetSocketAddress(InetAddress.getLoopbackAddress(), 24501);
            CountDownLatch received = new CountDownLatch(2);
            Map<Integer, InetSocketAddress> sources = new java.util.concurrent.ConcurrentHashMap<Integer, InetSocketAddress>();
            AtomicReference<IOException> failure = new AtomicReference<IOException>();
            try (TurnUdpClient client = new TurnUdpClient(server.endpoint(), "user", "password", new TurnUdpClient.Listener() {
                public void onData(byte[] bytes, InetSocketAddress source) { sources.put((int) bytes[0], source); received.countDown(); }
                public void onFailure(IOException ex) { failure.set(ex); }
            })) {
                try { client.allocate(); }
                catch (IOException timeout) {
                    if (server.failure.get() != null) timeout.addSuppressed(server.failure.get());
                    throw timeout;
                }
                client.bindPeer(server.relay); client.bindPeer(nextPeer);
                client.sendTo(server.relay, new byte[] { 1 }); client.sendTo(nextPeer, new byte[] { 2 });
                assertTrue(received.await(3, TimeUnit.SECONDS));
                assertEquals(server.relay, sources.get(1)); assertEquals(nextPeer, sources.get(2));
                assertEquals(2, server.channelPeers.size());
                client.retirePeer(server.relay);
                assertThrows(IOException.class, () -> client.sendTo(server.relay, new byte[1]));
                assertNull(failure.get()); assertNull(server.failure.get());
            }
        }
    }

    @Test void pinsServerEndpointAndRejectsMalformedChannelFrames() throws Exception {
        try (FakeTurn server = new FakeTurn(); DatagramSocket attacker = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicInteger messages = new AtomicInteger();
            try (TurnUdpClient client = new TurnUdpClient(server.endpoint(), "user", "password", new TurnUdpClient.Listener() {
                public void onData(byte[] bytes, InetSocketAddress source) { messages.incrementAndGet(); received.countDown(); }
                public void onFailure(IOException ex) { }
            })) {
                client.allocate(); client.bindPeer(server.relay);
                byte[] valid = new byte[] { 0x40, 0, 0, 1, 99 };
                attacker.send(new DatagramPacket(valid, valid.length, server.clientEndpoint));
                server.send(new byte[] { 0x40, 0, 0, 20, 99 }, server.clientEndpoint);
                assertFalse(received.await(100, TimeUnit.MILLISECONDS));
                client.send(new byte[] { 2 }); assertTrue(received.await(2, TimeUnit.SECONDS));
                assertEquals(1, messages.get()); assertNull(server.failure.get());
            }
        }
    }

    private static final class FakeTurn implements AutoCloseable {
        final DatagramSocket socket;
        final InetSocketAddress relay = new InetSocketAddress(InetAddress.getLoopbackAddress(), 24500);
        final AtomicInteger unauthenticatedRequests = new AtomicInteger(), permissions = new AtomicInteger(), refreshes = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final CountDownLatch deallocated = new CountDownLatch(1);
        final Map<Integer, InetSocketAddress> channelPeers = new java.util.concurrent.ConcurrentHashMap<Integer, InetSocketAddress>();
        final byte[] key;
        final boolean signedChallenges;
        byte[] firstId;
        String nonce = "nonce-one";
        volatile boolean closed;
        volatile InetSocketAddress clientEndpoint;

        FakeTurn() throws Exception { this(true); }
        FakeTurn(boolean signedChallenges) throws Exception {
            this.signedChallenges = signedChallenges;
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            key = StunCodec.longTermKey("user", "realm", "password");
            Thread reader = new Thread(this::run, "Fake-TURN"); reader.setDaemon(true); reader.start();
        }
        InetSocketAddress endpoint() { return (InetSocketAddress) socket.getLocalSocketAddress(); }
        void run() {
            while (!closed) {
                try {
                    byte[] buffer = new byte[65535]; DatagramPacket packet = new DatagramPacket(buffer, buffer.length); socket.receive(packet);
                    byte[] bytes = Arrays.copyOf(buffer, packet.getLength());
                    InetSocketAddress target = (InetSocketAddress) packet.getSocketAddress();
                    if (bytes.length < 2) {
                        if (!target.equals(clientEndpoint)) continue;
                        throw new IOException("Truncated frame from TURN client");
                    }
                    int first = ByteBuffer.wrap(bytes).getShort() & 0xffff;
                    if (first >= 0x4000 && first <= 0x7fff) {
                        if (!target.equals(clientEndpoint)) continue;
                        assertTrue(channelPeers.containsKey(first)); send(bytes, target); continue;
                    }
                    StunCodec.Message request;
                    try { request = StunCodec.parse(bytes); }
                    catch (IOException invalid) {
                        if (!target.equals(clientEndpoint)) continue;
                        throw invalid;
                    }
                    if (clientEndpoint != null && !target.equals(clientEndpoint)) continue;
                    clientEndpoint = target;
                    List<StunCodec.Attribute> attrs = new ArrayList<StunCodec.Attribute>();
                    if (request.attribute(StunCodec.USERNAME) == null) {
                        assertEquals(StunCodec.ALLOCATE_REQUEST, request.type);
                        int seen = unauthenticatedRequests.incrementAndGet();
                        if (seen == 1) { firstId = request.transactionId.clone(); continue; }
                        assertArrayEquals(firstId, request.transactionId);
                        challenge(request, 401, target, null); continue;
                    }
                    assertTrue(StunCodec.verifyIntegrity(request, key));
                    assertEquals("user", request.attribute(StunCodec.USERNAME).text());
                    assertEquals("realm", request.attribute(StunCodec.REALM).text());
                    assertEquals(nonce, request.attribute(StunCodec.NONCE).text());
                    if (request.type == StunCodec.ALLOCATE_REQUEST) {
                        assertArrayEquals(new byte[] { 17, 0, 0, 0 }, request.attribute(StunCodec.REQUESTED_TRANSPORT).value);
                        attrs.add(StunCodec.xorAddressAttribute(StunCodec.XOR_RELAYED_ADDRESS, relay, request.transactionId));
                        attrs.add(StunCodec.integerAttribute(StunCodec.LIFETIME, 600));
                        // Same endpoint sends a valid-looking wrong transaction and then wrong integrity.
                        send(StunCodec.encode(StunCodec.ALLOCATE_SUCCESS, StunCodec.newTransactionId(), attrs, key), target);
                        List<StunCodec.Attribute> invalidAttrs = new ArrayList<StunCodec.Attribute>();
                        invalidAttrs.add(StunCodec.xorAddressAttribute(StunCodec.XOR_RELAYED_ADDRESS,
                                new InetSocketAddress(InetAddress.getLoopbackAddress(), 24666), request.transactionId));
                        invalidAttrs.add(StunCodec.integerAttribute(StunCodec.LIFETIME, 600));
                        send(StunCodec.encode(StunCodec.ALLOCATE_SUCCESS, request.transactionId, invalidAttrs, new byte[16]), target);
                    } else if (request.type == StunCodec.CREATE_PERMISSION_REQUEST) {
                        InetSocketAddress address = StunCodec.xorAddress(request, StunCodec.XOR_PEER_ADDRESS);
                        assertEquals(InetAddress.getLoopbackAddress(), address.getAddress());
                        if (permissions.incrementAndGet() == 1) { nonce = "nonce-two"; challenge(request, 438, target, signedChallenges ? key : null); continue; }
                    } else if (request.type == StunCodec.CHANNEL_BIND_REQUEST) {
                        int channel = ByteBuffer.wrap(request.attribute(StunCodec.CHANNEL_NUMBER).value).getShort() & 0xffff;
                        InetSocketAddress address = StunCodec.xorAddress(request, StunCodec.XOR_PEER_ADDRESS);
                        InetSocketAddress old = channelPeers.put(channel, address);
                        assertTrue(old == null || old.equals(address), "TURN channel cannot be reassigned during rotation");
                    } else if (request.type == StunCodec.REFRESH_REQUEST) {
                        int lifetime = request.attribute(StunCodec.LIFETIME).integer();
                        if (lifetime == 0) { deallocated.countDown(); continue; }
                        refreshes.incrementAndGet(); attrs.add(StunCodec.integerAttribute(StunCodec.LIFETIME, 600));
                    } else fail("Unexpected TURN request " + request.type);
                    send(StunCodec.encode(request.type | 0x0100, request.transactionId, attrs, key), target);
                } catch (Throwable ex) { if (!closed) failure.compareAndSet(null, ex); return; }
            }
        }
        void challenge(StunCodec.Message request, int code, InetSocketAddress target, byte[] integrity) throws IOException {
            List<StunCodec.Attribute> values = new ArrayList<StunCodec.Attribute>();
            values.add(StunCodec.attribute(StunCodec.ERROR_CODE, new byte[] { 0, 0, (byte) (code / 100), (byte) (code % 100) }));
            values.add(StunCodec.textAttribute(StunCodec.REALM, "realm"));
            values.add(StunCodec.textAttribute(StunCodec.NONCE, nonce));
            send(StunCodec.encode(request.type | 0x0110, request.transactionId, values, integrity), target);
        }
        void send(byte[] bytes, InetSocketAddress address) throws IOException { socket.send(new DatagramPacket(bytes, bytes.length, address)); }
        public void close() { closed = true; socket.close(); }
    }
}
