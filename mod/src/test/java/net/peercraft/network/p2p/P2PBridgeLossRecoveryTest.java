package net.peercraft.network.p2p;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.peercraft.network.transport.PeerTransport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Host-side recovery when a joiner's first datagram (seq=0) is lost or arrives late. */
class P2PBridgeLossRecoveryTest {
    private static final PeerAddress PEER = new PeerAddress(InetAddress.getLoopbackAddress(), 21011);
    private static final long SESSION = 0x5eed_1234L;

    static class Route implements PeerTransport {
        final List<byte[]> packets = new CopyOnWriteArrayList<>();
        @Override public void send(byte[] packet) { packets.add(packet); }
        @Override public String mode() { return "direct"; }
        @Override public void close() { }
    }

    private static P2PBridge hostBridge(ServerSocket minecraft, Route route) throws Exception {
        java.lang.reflect.Constructor<P2PBridge> constructor = P2PBridge.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        P2PBridge bridge = constructor.newInstance();
        set(bridge, "isHost", true);
        set(bridge, "localMinecraftPort", minecraft.getLocalPort());
        set(bridge, "sender", new PeerRouteIntegrationTest.RawSender());
        Map<PeerAddress, PeerTransport> routes = field(bridge, "peerRoutes");
        routes.put(PEER, route);
        Set<PeerAddress> authorized = field(bridge, "authorizedPeers");
        authorized.add(PEER);
        return bridge;
    }

    @SuppressWarnings("unchecked") private static <T> T field(P2PBridge bridge, String name) throws Exception {
        Field field = P2PBridge.class.getDeclaredField(name); field.setAccessible(true); return (T) field.get(bridge);
    }
    private static void set(P2PBridge bridge, String name, Object value) throws Exception {
        Field field = P2PBridge.class.getDeclaredField(name); field.setAccessible(true); field.set(bridge, value);
    }
    private static void receive(P2PBridge bridge, long seq, byte... payload) throws Exception {
        byte[] raw = FramedPacket.encode(SESSION, seq, payload);
        Method handle = P2PBridge.class.getDeclaredMethod("handleHostIncoming", FramedPacket.class, PeerAddress.class);
        handle.setAccessible(true);
        handle.invoke(bridge, FramedPacket.decode(raw, raw.length), PEER);
    }
    private static long nackedSeq(Route route) {
        for (byte[] packet : route.packets) {
            FramedPacket frame = FramedPacket.decode(packet, packet.length);
            if (frame != null && frame.type() == FramedPacket.TYPE_NACK && frame.sessionId() == SESSION) return frame.nackSeq();
        }
        return -1;
    }

    @Test void lostFirstDatagramIsRequestedAndHeldDataIsReplayedInOrder() throws Exception {
        try (ServerSocket minecraft = new ServerSocket(0)) {
            minecraft.setSoTimeout(5_000);
            Route route = new Route();
            P2PBridge bridge = hostBridge(minecraft, route);

            receive(bridge, 1, (byte) 2);
            receive(bridge, 2, (byte) 3);
            assertEquals(0, nackedSeq(route), "host asks the joiner for the missing seq=0");

            receive(bridge, 0, (byte) 1); // the joiner's resend
            try (Socket accepted = minecraft.accept()) {
                accepted.setSoTimeout(5_000);
                InputStream in = accepted.getInputStream();
                byte[] got = new byte[3];
                int read = 0;
                while (read < 3) read += in.read(got, read, 3 - read);
                assertArrayEquals(new byte[]{1, 2, 3}, got);
            }
        }
    }

    @Test void strangersAndClosedSessionsAreNotAskedForSeqZero() throws Exception {
        try (ServerSocket minecraft = new ServerSocket(0)) {
            Route route = new Route();
            P2PBridge bridge = hostBridge(minecraft, route);
            Set<PeerAddress> authorized = field(bridge, "authorizedPeers");
            authorized.clear();
            receive(bridge, 1, (byte) 2);
            assertEquals(-1, nackedSeq(route));

            authorized.add(PEER);
            Set<Long> closed = field(bridge, "recentlyClosedSessions");
            closed.add(SESSION);
            receive(bridge, 1, (byte) 2);
            receive(bridge, 0, (byte) 1);
            assertEquals(-1, nackedSeq(route));
            Map<Long, ?> connections = field(bridge, "hostConnectionsBySessionId");
            assertTrue(connections.isEmpty(), "a closed session is never reopened by late datagrams");
        }
    }
}
