package net.peercraft.relay;

import static org.junit.jupiter.api.Assertions.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.peercraft.network.ownrelay.OwnRelayProtocol;
import org.junit.jupiter.api.Test;

class RelayServerTest {
    private static final byte[] SECRET = filled(32, 7);
    private static final InetSocketAddress OWNER = new InetSocketAddress(InetAddress.getLoopbackAddress(), 40001);
    private static final InetSocketAddress JOINER = new InetSocketAddress(InetAddress.getLoopbackAddress(), 40002);
    private static final InetSocketAddress STRANGER = new InetSocketAddress(InetAddress.getLoopbackAddress(), 40003);

    private record Sent(byte[] datagram, InetSocketAddress to) {}
    private final List<Sent> sent = new ArrayList<>();
    private long now = 1_700_000_000_000L, nanos = 0;

    private RelayServer server(int maxSessions, long bytesPerSecond) {
        RelayConfig config = new RelayConfig(52100, SECRET, "", maxSessions, bytesPerSecond, 5, 30_000, 60_000);
        return new RelayServer(config, (datagram, to) -> sent.add(new Sent(datagram, to)), message -> {});
    }
    private void handle(RelayServer server, byte[] packet, InetSocketAddress from) {
        server.handle(packet, packet.length, from, now, nanos);
    }
    private Sent last() { return sent.get(sent.size() - 1); }
    private long open(RelayServer server, byte[] nonce) {
        handle(server, OwnRelayProtocol.encodeAuth(OwnRelayProtocol.OPEN, SECRET, nonce, now), OWNER);
        Sent reply = last();
        assertEquals(OWNER, reply.to());
        long id = OwnRelayProtocol.openedSession(reply.datagram(), reply.datagram().length, SECRET, nonce);
        assertNotEquals(0, id);
        return id;
    }

    @Test void pingProvesBothSidesKnowTheSecret() {
        RelayServer server = server(4, 1 << 20);
        byte[] nonce = filled(16, 1);
        handle(server, OwnRelayProtocol.encodeAuth(OwnRelayProtocol.PING, SECRET, nonce, now), OWNER);
        assertTrue(OwnRelayProtocol.verifyPong(last().datagram(), last().datagram().length, SECRET, nonce));

        handle(server, OwnRelayProtocol.encodeAuth(OwnRelayProtocol.PING, filled(32, 9), nonce, now), OWNER);
        assertEquals(OwnRelayProtocol.REASON_AUTH, OwnRelayProtocol.refusedReason(last().datagram(), last().datagram().length, nonce));

        handle(server, OwnRelayProtocol.encodeAuth(OwnRelayProtocol.PING, SECRET, nonce, now - 11 * 60_000L), OWNER);
        assertEquals(OwnRelayProtocol.REASON_STALE, OwnRelayProtocol.refusedReason(last().datagram(), last().datagram().length, nonce));
    }

    @Test void forwardsDataBothWaysOnlyBetweenTheTwoPlayers() {
        RelayServer server = server(4, 1 << 20);
        long id = open(server, filled(16, 2));
        byte[] joinNonce = filled(16, 3);
        handle(server, OwnRelayProtocol.encodeJoin(id, joinNonce), JOINER);
        assertTrue(sent.stream().anyMatch(s -> s.to().equals(JOINER) && OwnRelayProtocol.isJoined(s.datagram(), s.datagram().length, id, joinNonce)));
        assertTrue(sent.stream().anyMatch(s -> s.to().equals(OWNER)
                && OwnRelayProtocol.type(s.datagram(), s.datagram().length) == OwnRelayProtocol.PEER_JOINED));

        byte[] toJoiner = OwnRelayProtocol.encodeData(id, new byte[] { 1, 2, 3 });
        handle(server, toJoiner, OWNER);
        assertEquals(JOINER, last().to());
        assertArrayEquals(toJoiner, last().datagram());
        byte[] toOwner = OwnRelayProtocol.encodeData(id, new byte[] { 4 });
        handle(server, toOwner, JOINER);
        assertEquals(OWNER, last().to());

        int before = sent.size();
        handle(server, OwnRelayProtocol.encodeData(id, new byte[] { 5 }), STRANGER);
        handle(server, OwnRelayProtocol.encodeJoin(id, filled(16, 4)), STRANGER);
        assertEquals(before + 1, sent.size(), "a stranger only gets a refusal");
        assertEquals(OwnRelayProtocol.REASON_TAKEN, OwnRelayProtocol.refusedReason(last().datagram(), last().datagram().length, filled(16, 4)));
    }

    @Test void retransmittedOpenReusesSessionButReplayFromElsewhereGetsNothing() {
        RelayServer server = server(4, 1 << 20);
        byte[] nonce = filled(16, 5);
        byte[] request = OwnRelayProtocol.encodeAuth(OwnRelayProtocol.OPEN, SECRET, nonce, now);
        long id = open(server, nonce);
        handle(server, request, OWNER);
        assertEquals(id, OwnRelayProtocol.openedSession(last().datagram(), last().datagram().length, SECRET, nonce));
        int before = sent.size();
        handle(server, request, STRANGER);
        assertEquals(before, sent.size());
        assertEquals(1, server.activeSessions());
    }

    @Test void refusesWhenFullOrRateLimited() {
        RelayServer server = server(1, 1 << 20);
        open(server, filled(16, 6));
        byte[] nonce = filled(16, 7);
        handle(server, OwnRelayProtocol.encodeAuth(OwnRelayProtocol.OPEN, SECRET, nonce, now), OWNER);
        assertEquals(OwnRelayProtocol.REASON_FULL, OwnRelayProtocol.refusedReason(last().datagram(), last().datagram().length, nonce));

        RelayServer limited = server(100, 1 << 20);
        for (int i = 0; i < 5; i++) open(limited, filled(16, 20 + i));
        byte[] extra = filled(16, 30);
        handle(limited, OwnRelayProtocol.encodeAuth(OwnRelayProtocol.OPEN, SECRET, extra, now), OWNER);
        assertEquals(OwnRelayProtocol.REASON_RATE, OwnRelayProtocol.refusedReason(last().datagram(), last().datagram().length, extra));
    }

    @Test void expiresPendingAndIdleSessionsAndRateLimitsData() {
        RelayServer server = server(4, 16 * 1024);
        open(server, filled(16, 8));
        now += 31_000;
        server.sweep(now);
        assertEquals(0, server.activeSessions());

        long id = open(server, filled(16, 9));
        handle(server, OwnRelayProtocol.encodeJoin(id, filled(16, 10)), JOINER);
        int forwarded = 0;
        for (int i = 0; i < 40; i++) {
            int before = sent.size();
            handle(server, OwnRelayProtocol.encodeData(id, new byte[1000]), OWNER);
            if (sent.size() > before) forwarded++;
        }
        assertTrue(forwarded < 20, "token bucket caps a burst at about one second of budget");
        now += 61_000;
        server.sweep(now);
        assertEquals(0, server.activeSessions());
        assertTrue(sent.stream().anyMatch(s -> s.to().equals(JOINER)
                && OwnRelayProtocol.type(s.datagram(), s.datagram().length) == OwnRelayProtocol.CLOSE));
    }

    @Test void realSocketsExchangeThroughRunningRelay() throws Exception {
        RelayConfig config = new RelayConfig(52100, SECRET, "", 4, 1 << 20, 20, 30_000, 60_000);
        try (DatagramSocket relaySocket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket owner = new DatagramSocket(0, InetAddress.getLoopbackAddress());
             DatagramSocket joiner = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            Thread loop = new Thread(() -> { try { RelayServer.serve(config, relaySocket, m -> {}); } catch (Exception ignored) {} });
            loop.setDaemon(true); loop.start();
            InetSocketAddress relay = new InetSocketAddress(InetAddress.getLoopbackAddress(), relaySocket.getLocalPort());
            owner.setSoTimeout(2000); joiner.setSoTimeout(2000);

            byte[] nonce = filled(16, 11);
            send(owner, OwnRelayProtocol.encodeAuth(OwnRelayProtocol.OPEN, SECRET, nonce, System.currentTimeMillis()), relay);
            byte[] opened = receive(owner);
            long id = OwnRelayProtocol.openedSession(opened, opened.length, SECRET, nonce);
            assertNotEquals(0, id);

            send(joiner, OwnRelayProtocol.encodeJoin(id, filled(16, 12)), relay);
            assertEquals(OwnRelayProtocol.JOINED, OwnRelayProtocol.type(receive(joiner), OwnRelayProtocol.HEADER_BYTES));
            assertEquals(OwnRelayProtocol.PEER_JOINED, OwnRelayProtocol.type(receive(owner), OwnRelayProtocol.HEADER_BYTES));

            send(joiner, OwnRelayProtocol.encodeData(id, "hello".getBytes()), relay);
            byte[] data = receive(owner);
            assertArrayEquals("hello".getBytes(), OwnRelayProtocol.dataPayload(data, data.length));
            relaySocket.close();
        }
    }

    private static void send(DatagramSocket socket, byte[] bytes, InetSocketAddress to) throws Exception {
        socket.send(new DatagramPacket(bytes, bytes.length, to));
    }
    private static byte[] receive(DatagramSocket socket) throws Exception {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        return Arrays.copyOf(buffer, packet.getLength());
    }
    private static byte[] filled(int size, int value) { byte[] b = new byte[size]; Arrays.fill(b, (byte) value); return b; }
}
