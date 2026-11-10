package net.peercraft.network.ownrelay;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.peercraft.network.rendezvous.RendezvousProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OwnRelayPeerTransportTest {
    private static final byte[] SECRET = filled(32, 5);
    private static final byte[] MATCH_KEY = filled(32, 9);

    @Test void relayTicketRoundTripsAndIsNotADirectCandidate() throws Exception {
        RendezvousProtocol.NetworkOffer offer = new RendezvousProtocol.NetworkOffer(UUID.randomUUID(), MATCH_KEY,
                Collections.<RendezvousProtocol.Address>emptyList(), false, "");
        RendezvousProtocol.Address relay = new RendezvousProtocol.Address(InetAddress.getByName("8.8.4.4"), 52100);
        byte[] ticket = RendezvousProtocol.encodeRelayTicket(offer, true, new RendezvousProtocol.RelayTicket(relay, 77L));

        RendezvousProtocol.RelayTicket decoded = RendezvousProtocol.decodeRelayTicket(ticket, ticket.length, offer, true).get();
        assertEquals(relay, decoded.relay());
        assertEquals(77L, decoded.sessionId());
        assertFalse(RendezvousProtocol.decodeRelayTicket(ticket, ticket.length, offer, false).isPresent(), "role is signed");
        assertFalse(RendezvousProtocol.decodeDirectCandidate(ticket, ticket.length, offer, true).isPresent());

        byte[] candidate = RendezvousProtocol.encodeDirectCandidate(offer, true, relay);
        assertFalse(RendezvousProtocol.decodeRelayTicket(candidate, candidate.length, offer, true).isPresent());
        ticket[22] ^= 1;
        assertFalse(RendezvousProtocol.decodeRelayTicket(ticket, ticket.length, offer, true).isPresent(), "tampered session id");
    }

    @Test @Timeout(10) void ownerAndJoinerExchangeEncryptedDataThroughRelay() throws Exception {
        FakeRelay relay = new FakeRelay();
        UUID attempt = UUID.randomUUID();
        AtomicReference<OwnRelayPeerTransport> joinerRoute = new AtomicReference<>();
        Recorder ownerEvents = new Recorder(), joinerEvents = new Recorder();
        ownerEvents.onTicket = (address, session) -> {
            if (joinerRoute.get() != null) return;
            OwnRelayPeerTransport joiner = OwnRelayPeerTransport.joiner(relay.endpoint(FakeRelay.JOINER, joinerRoute), address,
                    session, MATCH_KEY, attempt, true, joinerEvents);
            if (joinerRoute.compareAndSet(null, joiner)) joiner.start();
        };
        AtomicReference<OwnRelayPeerTransport> ownerRoute = new AtomicReference<>();
        OwnRelayPeerTransport owner = OwnRelayPeerTransport.owner(relay.endpoint(FakeRelay.OWNER, ownerRoute), FakeRelay.ADDRESS,
                SECRET, MATCH_KEY, attempt, false, ownerEvents);
        ownerRoute.set(owner);
        owner.start();

        assertTrue(ownerEvents.connected.await(5, TimeUnit.SECONDS));
        assertTrue(joinerEvents.connected.await(5, TimeUnit.SECONDS));
        assertEquals("own_relay", owner.mode());

        owner.send("from joiner side".getBytes());
        assertArrayEquals("from joiner side".getBytes(), joinerEvents.data.poll(2, TimeUnit.SECONDS));
        joinerRoute.get().send(new byte[] { 1, 2, 3 });
        assertArrayEquals(new byte[] { 1, 2, 3 }, ownerEvents.data.poll(2, TimeUnit.SECONDS));
        assertTrue(relay.forwarded.stream().noneMatch(p -> contains(p, "from joiner side".getBytes())), "relay sees only ciphertext");

        owner.close();
        assertEquals("peercraft.p2p.fail.own_relay_closed", joinerEvents.failure.poll(2, TimeUnit.SECONDS));
        assertNull(ownerEvents.failure.poll(200, TimeUnit.MILLISECONDS), "closing locally is not a failure");
    }

    @Test @Timeout(10) void wrongSecretReportsAuthFailure() throws Exception {
        FakeRelay relay = new FakeRelay();
        Recorder events = new Recorder();
        AtomicReference<OwnRelayPeerTransport> route = new AtomicReference<>();
        OwnRelayPeerTransport owner = OwnRelayPeerTransport.owner(relay.endpoint(FakeRelay.OWNER, route), FakeRelay.ADDRESS,
                filled(32, 1), MATCH_KEY, UUID.randomUUID(), true, events);
        route.set(owner);
        owner.start();
        assertEquals("peercraft.p2p.fail.own_relay_auth", events.failure.poll(5, TimeUnit.SECONDS));
    }

    /** Minimal relay behaviour on the shared codec; the real server is tested in peercraft-relay. */
    private static final class FakeRelay {
        static final InetSocketAddress ADDRESS = new InetSocketAddress(InetAddress.getLoopbackAddress(), 52100);
        static final InetSocketAddress OWNER = new InetSocketAddress(InetAddress.getLoopbackAddress(), 40001);
        static final InetSocketAddress JOINER = new InetSocketAddress(InetAddress.getLoopbackAddress(), 40002);
        final List<byte[]> forwarded = new CopyOnWriteArrayList<>();
        private final AtomicReference<OwnRelayPeerTransport> owner = new AtomicReference<>(), joiner = new AtomicReference<>();
        private volatile long session;

        OwnRelayPeerTransport.Datagrams endpoint(InetSocketAddress self, AtomicReference<OwnRelayPeerTransport> route) {
            (self.equals(OWNER) ? owner : joiner).set(null);
            AtomicReference<OwnRelayPeerTransport> slot = self.equals(OWNER) ? owner : joiner;
            return (datagram, to) -> {
                if (slot.get() == null) slot.set(route.get());
                assertEquals(ADDRESS, to);
                synchronized (this) { handle(datagram, self); }
            };
        }
        private void reply(InetSocketAddress to, byte[] packet) {
            OwnRelayPeerTransport target = (to.equals(OWNER) ? owner : joiner).get();
            if (target != null) target.accept(packet, packet.length, ADDRESS);
        }
        private void handle(byte[] packet, InetSocketAddress from) {
            int length = packet.length;
            switch (OwnRelayProtocol.type(packet, length)) {
                case OwnRelayProtocol.OPEN: {
                    byte[] nonce = OwnRelayProtocol.nonce(packet, length);
                    int verdict = OwnRelayProtocol.verifyAuth(packet, length, SECRET, System.currentTimeMillis());
                    if (verdict != 0) { reply(from, OwnRelayProtocol.encodeRefused(nonce, verdict)); return; }
                    if (session == 0) session = 4242;
                    reply(from, OwnRelayProtocol.encodeOpened(SECRET, nonce, session));
                    return;
                }
                case OwnRelayProtocol.JOIN: {
                    byte[] nonce = OwnRelayProtocol.nonce(packet, length);
                    if (OwnRelayProtocol.sessionId(packet, length) != session) return;
                    reply(from, OwnRelayProtocol.encodeJoined(session, nonce));
                    reply(OWNER, OwnRelayProtocol.encodeSession(OwnRelayProtocol.PEER_JOINED, session));
                    return;
                }
                case OwnRelayProtocol.DATA:
                    forwarded.add(packet);
                    reply(from.equals(OWNER) ? JOINER : OWNER, packet);
                    return;
                case OwnRelayProtocol.CLOSE:
                    reply(from.equals(OWNER) ? JOINER : OWNER, packet);
                    return;
                default:
            }
        }
    }

    private static final class Recorder implements OwnRelayPeerTransport.Listener {
        final CountDownLatch connected = new CountDownLatch(1);
        final LinkedBlockingQueue<byte[]> data = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<String> failure = new LinkedBlockingQueue<>();
        volatile java.util.function.BiConsumer<InetSocketAddress, Long> onTicket = (a, s) -> {};
        @Override public void onTicket(InetSocketAddress relay, long sessionId) { onTicket.accept(relay, sessionId); }
        @Override public void onConnected(OwnRelayPeerTransport route) { connected.countDown(); }
        @Override public void onData(byte[] payload) { data.add(payload); }
        @Override public void onFailure(String reason) { failure.add(reason); }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }
    private static byte[] filled(int size, int value) { byte[] b = new byte[size]; Arrays.fill(b, (byte) value); return b; }
}
