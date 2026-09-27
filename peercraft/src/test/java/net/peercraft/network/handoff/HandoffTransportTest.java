package net.peercraft.network.handoff;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class HandoffTransportTest {
    @Test void gameStopKeepsParticipantMappingsUntilOperationEnds() throws Exception {
        InetSocketAddress peer = new InetSocketAddress(InetAddress.getLoopbackAddress(), 24001);
        Set<InetSocketAddress> peers = new HashSet<>(); peers.add(peer);
        CountDownLatch ping = new CountDownLatch(1);
        HandoffTransport transport = new HandoffTransport(17, peers, (target, bytes) -> {
            assertEquals(peer, target); assertArrayEquals(HandoffProtocol.encodePing(), bytes); ping.countDown();
        });
        try {
            peers.clear();
            assertTrue(ping.await(2, TimeUnit.SECONDS));
            assertFalse(transport.preservesSourceStop());
            transport.sourceStopping();
            assertTrue(transport.preservesSourceStop());
            assertTrue(transport.contains(peer));
            assertFalse(transport.contains(new InetSocketAddress(peer.getAddress(), 24002)));
        } finally { transport.close(); }
        assertFalse(transport.preservesSourceStop());
        assertFalse(transport.contains(peer));
        assertThrows(IllegalStateException.class, transport::sourceStopping);
    }
}
