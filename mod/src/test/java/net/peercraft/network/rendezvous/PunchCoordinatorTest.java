package net.peercraft.network.rendezvous;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PunchCoordinatorTest {
    @Test void changedPortRequiresOwnedTokenAndReplyToProbe() throws Exception {
        InetAddress address = InetAddress.getByName("127.0.0.1");
        List<byte[]> sent = new ArrayList<>();
        AtomicInteger successes = new AtomicInteger();
        PunchCoordinator coordinator = new PunchCoordinator(new net.peercraft.network.p2p.P2PSender(null) {
            @Override public void sendData(byte[] bytes, String ip, int port) {
            assertEquals(20002, port); sent.add(bytes);
            }
        }, new RendezvousProtocol.Address(address, 20001), 123L, new PunchCoordinator.Callback() {
            public void onSuccess(String ip, int port) { assertEquals(20002, port); successes.incrementAndGet(); }
            public void onFailure(String reason) { fail(reason); }
        });
        byte[] wrong = RendezvousProtocol.encodePunch(124L);
        coordinator.onPacket(wrong, wrong.length, address, 20002);
        assertTrue(sent.isEmpty());
        byte[] punch = RendezvousProtocol.encodePunch(123L);
        coordinator.onPacket(punch, punch.length, address, 20002);
        assertEquals(2, sent.size());
        assertEquals(RendezvousProtocol.TYPE_PUNCH, RendezvousProtocol.messageType(sent.get(0), sent.get(0).length));
        assertEquals(0, successes.get());
        byte[] ack = RendezvousProtocol.encodePunchAck(123L);
        coordinator.onPacket(ack, ack.length, address, 20002);
        coordinator.onPacket(ack, ack.length, address, 20002);
        assertEquals(1, successes.get());
        coordinator.cancel();
        coordinator.onPacket(punch, punch.length, address, 20002);
        assertEquals(2, sent.size());
    }
}
