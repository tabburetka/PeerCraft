package net.peercraft.network.p2p;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.peercraft.network.transport.PeerTransport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PeerRouteIntegrationTest {
    private static final PeerAddress PEER = new PeerAddress(InetAddress.getLoopbackAddress(), 21001);
    private static P2PBridge newBridge() throws Exception {
        java.lang.reflect.Constructor<P2PBridge> constructor = P2PBridge.class.getDeclaredConstructor();
        constructor.setAccessible(true); return constructor.newInstance();
    }
    static class RawSender extends P2PSender {
        final AtomicInteger count = new AtomicInteger();
        RawSender() { super(null); }
        @Override public void sendData(byte[] bytes, String ip, int port) { count.incrementAndGet(); }
    }
    static class Route implements PeerTransport {
        final List<byte[]> packets = new CopyOnWriteArrayList<>();
        boolean closed;
        @Override public void send(byte[] packet) { packets.add(packet); }
        @Override public String mode() { return "relay"; }
        @Override public void close() { closed = true; }
    }
    @SuppressWarnings("unchecked") private static <T> T field(P2PBridge bridge, String name) throws Exception {
        Field field = P2PBridge.class.getDeclaredField(name); field.setAccessible(true); return (T) field.get(bridge);
    }
    private static void set(P2PBridge bridge, String name, Object value) throws Exception {
        Field field = P2PBridge.class.getDeclaredField(name); field.setAccessible(true); field.set(bridge, value);
    }
    @Test void everyPeerProtocolUsesSelectedRouteAndServerControlKeepsSharedSocket() throws Exception {
        P2PBridge bridge = newBridge(); RawSender raw = new RawSender(); Route route = new Route();
        set(bridge, "sender", raw);
        Map<PeerAddress, PeerTransport> routes = field(bridge, "peerRoutes"); routes.put(PEER, route);
        for (int magic : new int[] {2, 0xE2, 0xE3, 0xE4, 0xE7, 0xE8})
            bridge.sendRawDatagram(PEER.host(), PEER.port(), new byte[] {(byte) magic, 1});
        assertEquals(6, route.packets.size()); assertEquals(0, raw.count.get());
        for (int magic : new int[] {0xE1, 0xE6})
            bridge.sendRawDatagram(PEER.host(), PEER.port(), new byte[] {(byte) magic, 1});
        assertEquals(6, route.packets.size()); assertEquals(2, raw.count.get());
    }
    @Test void lostRelayNotifiesOnlyItsWorldTransferImmediately(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        P2PBridge bridge = newBridge(); Route route = new Route();
        AtomicInteger failed = new AtomicInteger();
        net.peercraft.network.handoff.WorldTransfer transfer = net.peercraft.network.handoff.WorldTransfer.receiver(
                91, dir.resolve("world.part"), 1024, (ip, port, bytes) -> {}, PEER.host(), PEER.port(),
                new net.peercraft.network.handoff.WorldTransfer.ReceiverCallbacks() {
                    public void onProgress(long a, long b) { }
                    public void onComplete(java.nio.file.Path file) { fail("No verified archive exists"); }
                    public void onFailed(String reason) { assertEquals("peercraft.handoff.abort.transfer_failed", reason); failed.incrementAndGet(); }
                });
        set(bridge, "clientTargetPeer", PEER); bridge.setSuccessorWorldTransfer(transfer);
        java.lang.reflect.Method failure = P2PBridge.class.getDeclaredMethod("transportFailed", PeerAddress.class,
                PeerTransport.class, String.class, boolean.class); failure.setAccessible(true);
        PeerAddress other = new PeerAddress(PEER.host(), PEER.port() + 1);
        failure.invoke(bridge, other, route, "peercraft.p2p.fail.budget_exhausted", false);
        assertEquals(0, failed.get());
        failure.invoke(bridge, PEER, route, "peercraft.p2p.fail.budget_exhausted", false);
        failure.invoke(bridge, PEER, route, "peercraft.p2p.fail.budget_exhausted", false);
        assertEquals(1, failed.get());
    }
    @Test void closedSecureRouteNeverDowngradesToPlainDatagrams() throws Exception {
        P2PBridge bridge = newBridge(); RawSender raw = new RawSender(); set(bridge, "sender", raw);
        Set<PeerAddress> secure = field(bridge, "securedPeers"); secure.add(PEER);
        bridge.sendRawDatagram(PEER.host(), PEER.port(), new byte[] {(byte) 0xE4, 1});
        assertEquals(0, raw.count.get());
    }
    @Test void sourceStopRequiresBrokerRetentionAcknowledgement() throws Exception {
        P2PBridge bridge = newBridge(); set(bridge, "sender", new RawSender());
        java.util.concurrent.CompletableFuture<Void> acknowledgement = new java.util.concurrent.CompletableFuture<>();
        AtomicInteger releases = new AtomicInteger();
        Route route = new Route() {
            @Override public java.util.concurrent.CompletableFuture<Void> retainHandoff(long offer) {
                assertEquals(37, offer); return acknowledgement;
            }
            @Override public void releaseHandoff(long offer) { assertEquals(37, offer); releases.incrementAndGet(); }
        };
        Map<PeerAddress, PeerTransport> routes = field(bridge, "peerRoutes"); routes.put(PEER, route);
        bridge.retainHandoffTransport(37, Collections.singleton(new InetSocketAddress(PEER.host(), PEER.port())));
        try {
            assertThrows(IllegalStateException.class, () -> bridge.prepareSourceStop(37));
            acknowledgement.complete(null);
            assertThrows(java.io.IOException.class, () -> bridge.awaitHandoffRetention(99));
            bridge.awaitHandoffRetention(37);
            bridge.prepareSourceStop(37);
        } finally { bridge.releaseHandoffTransport(37); }
        bridge.releaseHandoffTransport(37);
        assertEquals(1, releases.get()); assertFalse(route.closed);
    }
    @Test void rejectedRetentionNeverAllowsSourceStop() throws Exception {
        P2PBridge bridge = newBridge(); set(bridge, "sender", new RawSender());
        Route route = new Route() {
            @Override public java.util.concurrent.CompletableFuture<Void> retainHandoff(long offer) {
                java.util.concurrent.CompletableFuture<Void> rejected = new java.util.concurrent.CompletableFuture<>();
                rejected.completeExceptionally(new java.io.IOException("broker_unavailable")); return rejected;
            }
        };
        Map<PeerAddress, PeerTransport> routes = field(bridge, "peerRoutes"); routes.put(PEER, route);
        bridge.retainHandoffTransport(38, Collections.singleton(new InetSocketAddress(PEER.host(), PEER.port())));
        try {
            assertThrows(java.io.IOException.class, () -> bridge.awaitHandoffRetention(38));
            assertThrows(IllegalStateException.class, () -> bridge.prepareSourceStop(38));
        } finally { bridge.releaseHandoffTransport(38); }
        assertFalse(route.closed);
    }
    @Test void retainedHandoffUsesOriginalRouteAcrossNewGameRouteAndReleaseClosesIt() throws Exception {
        P2PBridge bridge = newBridge(); set(bridge, "sender", new RawSender());
        Route original = new Route(), replacement = new Route();
        Map<PeerAddress, PeerTransport> routes = field(bridge, "peerRoutes"); routes.put(PEER, original);
        InetSocketAddress endpoint = new InetSocketAddress(PEER.host(), PEER.port());
        bridge.retainHandoffTransport(7, Collections.singleton(endpoint), ignored -> new byte[] {(byte) 0xE8, 1});
        try {
            bridge.prepareSourceStop(7); bridge.cancelRendezvous(); assertFalse(original.closed);
            routes.put(PEER, replacement);
            bridge.sendRawDatagram(PEER.host(), PEER.port(), new byte[] {(byte) 0xE4, 9});
            bridge.sendRawDatagram(PEER.host(), PEER.port(), new byte[] {2, 9});
            assertTrue(original.packets.stream().anyMatch(packet -> packet[0] == (byte) 0xE4));
            assertEquals(1, replacement.packets.size());
        } finally { bridge.releaseHandoffTransport(7); }
        assertTrue(original.closed); assertFalse(replacement.closed);
    }
    @Test void forgedPlainPeerPacketsCannotReachNegotiatedModSyncOrIndependentHandoff() throws Exception {
        P2PBridge bridge = newBridge(); set(bridge, "clientTargetPeer", PEER);
        Map<PeerAddress, PeerTransport> routes = field(bridge, "peerRoutes"); routes.put(PEER, new Route());
        AtomicInteger dispatched = new AtomicInteger();
        set(bridge, "rendezvousListener", new RawPacketListener() {
            @Override public void onPacket(byte[] data, int length, InetAddress ip, int port) { dispatched.incrementAndGet(); }
            @Override public void cancel() {}
        });
        bridge.setHandoffControlReceiver((bytes, length, sender) -> dispatched.incrementAndGet());
        for (int magic : new int[] {0xE2, 0xE8}) {
            bridge.handleIncomingPacket(new byte[] {(byte) magic, 1}, 2, PEER.host(), PEER.port());
            bridge.handleIncomingPacket(new byte[] {(byte) magic, 1}, 2, PEER.host(), PEER.port() + 1);
        }
        assertEquals(0, dispatched.get());
    }
}
