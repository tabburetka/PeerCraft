package net.peercraft.rendezvous;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static net.peercraft.rendezvous.HandoffAuthorityProtocol.*;

class HandoffDispatchTest {
    @Test void tenObserversBehindNatDoNotConsumeCommitBudgetOrRoomListLimiter() throws Exception {
        AtomicLong now = new AtomicLong(); HandoffDispatch d = new HandoffDispatch(now::get, false);
        UUID session = UUID.randomUUID(); byte[] observer = new byte[32], host = new byte[32], successor = new byte[32];
        observer[0] = 1; host[0] = 2; successor[0] = 3; InetAddress ip = InetAddress.getLoopbackAddress();
        for (int second = 0; second < 60; second++) {
            now.set(second * 1000L);
            for (int n = 0; n < 10; n++) assertTrue(d.ingress(ip, true, session, observer, QUERY));
            assertTrue(d.ingress(ip, true, session, successor, QUERY));
            assertTrue(d.ingress(ip, true, session, host, COMMIT));
        }
        // Untrusted overload has a separate budget and cannot exhaust the trusted role's quota.
        for (int n = 0; n < 100; n++) d.ingress(ip, false, UUID.randomUUID(), new byte[32], BEGIN);
        assertTrue(d.ingress(ip, true, session, host, ABORT));
    }
    @Test void fairWriterRotatesSessionsAndReservesControlSlots() {
        HandoffDispatch d = new HandoffDispatch(() -> 0, false); List<Integer> order = new ArrayList<>();
        UUID noisy = UUID.randomUUID(), other = UUID.randomUUID();
        for (int n = 0; n < 12; n++) { Message m = new Message(QUERY); m.sessionId = noisy; m.requestId = n; d.execute(m, true, () -> order.add(1)); }
        Message control = new Message(COMMIT); control.sessionId = noisy; control.requestId = 40; d.execute(control, true, () -> order.add(2));
        Message query = new Message(QUERY); query.sessionId = other; d.execute(query, true, () -> order.add(3));
        d.next().run(); d.next().run(); assertEquals(List.of(2, 3), order);
    }
}
