package net.peercraft.network.handoff;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class HandoffControlChannelTest {
    private static InetSocketAddress peer(int port) { return new InetSocketAddress(InetAddress.getLoopbackAddress(), port); }
    private static byte[] key(int value) { byte[] key = new byte[32]; key[0] = (byte)value; return key; }
    @Test void sameIpAndOfferDoNotAuthorizeReadyOrObserverAccept() {
        UUID session = UUID.randomUUID(); HandoffPhases phases = new HandoffPhases(); phases.advance(HandoffPhases.Phase.OFFER);
        HandoffParticipant successor = new HandoffParticipant(UUID.randomUUID(), HandoffParticipant.Role.SUCCESSOR, peer(20001), key(1));
        HandoffParticipant observer = new HandoffParticipant(null, HandoffParticipant.Role.OBSERVER, peer(20002), key(2));
        AtomicInteger accepted = new AtomicInteger();
        HandoffControlChannel channel = new HandoffControlChannel(session, 7, 0, phases, Arrays.asList(successor, observer), (p, m) -> accepted.incrementAndGet());
        byte[] accept = HandoffControlProtocol.encode(successor.message(HandoffControlProtocol.ACCEPT, session, 7, 0, new byte[0]));
        assertFalse(channel.onPacket(accept, accept.length, peer(20002)));
        byte[] wrongRole = HandoffControlProtocol.encode(observer.message(HandoffControlProtocol.ACCEPT, session, 7, 0, new byte[0]));
        assertFalse(channel.onPacket(wrongRole, wrongRole.length, peer(20002)));
        byte[] early = HandoffControlProtocol.encode(successor.message(HandoffControlProtocol.READY, session, 7, 0, new byte[0]));
        assertFalse(channel.onPacket(early, early.length, peer(20001)));
        assertTrue(channel.onPacket(accept, accept.length, peer(20001))); assertEquals(1, accepted.get());
    }
    @Test void changedPortRequiresConfirmedCommitAndOperationAuthority() {
        UUID session = UUID.randomUUID(); HandoffPhases phases = new HandoffPhases();
        for (HandoffPhases.Phase phase : HandoffPhases.Phase.values()) {
            if (phase == HandoffPhases.Phase.CAPABILITIES) continue;
            phases.advance(phase); if (phase == HandoffPhases.Phase.COMMIT_SENT) break;
        }
        HandoffParticipant successor = new HandoffParticipant(UUID.randomUUID(), HandoffParticipant.Role.SUCCESSOR, peer(20001), key(1));
        HandoffControlChannel channel = new HandoffControlChannel(session, 7, 0, phases, Collections.singleton(successor), (p, m) -> {});
        channel.committed(1); phases.advance(HandoffPhases.Phase.STARTING);
        byte[] wrong = HandoffControlProtocol.encode(new HandoffControlProtocol.Message(HandoffControlProtocol.READY, session, 7, 1, key(2), new byte[0]));
        assertFalse(channel.onPacket(wrong, wrong.length, peer(20002))); assertEquals(peer(20001), successor.endpoint());
        byte[] ready = HandoffControlProtocol.encode(successor.message(HandoffControlProtocol.READY, session, 7, 1, new byte[0]));
        assertTrue(channel.onPacket(ready, ready.length, peer(20002))); assertEquals(peer(20002), successor.endpoint());
        byte[] old = HandoffControlProtocol.encode(successor.message(HandoffControlProtocol.READY, session, 7, 0, new byte[0]));
        assertFalse(channel.onPacket(old, old.length, peer(20002)));
    }
    @Test void commitCannotBeInferredFromTimeoutAndLateAbortCannotRollback() {
        HandoffPhases phases = new HandoffPhases();
        assertThrows(IllegalStateException.class, () -> phases.advance(HandoffPhases.Phase.COMMITTED));
        for (HandoffPhases.Phase phase : HandoffPhases.Phase.values()) {
            if (phase == HandoffPhases.Phase.CAPABILITIES) continue;
            phases.advance(phase); if (phase == HandoffPhases.Phase.COMMIT_SENT) break;
        }
        assertThrows(IllegalStateException.class, () -> phases.advance(HandoffPhases.Phase.ABORTED));
        phases.advance(HandoffPhases.Phase.UNKNOWN); phases.advance(HandoffPhases.Phase.COMMITTED);
        assertThrows(IllegalStateException.class, phases::confirmedAbort);
        assertFalse(phases.permits(HandoffParticipant.Role.SOURCE, HandoffControlProtocol.ABORT));
    }
}
