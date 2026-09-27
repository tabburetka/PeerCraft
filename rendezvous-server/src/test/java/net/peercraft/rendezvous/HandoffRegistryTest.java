package net.peercraft.rendezvous;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static net.peercraft.rendezvous.HandoffAuthorityProtocol.*;

class HandoffRegistryTest {
    @TempDir Path root;
    final UUID session = UUID.randomUUID();
    final byte[] host = key(1), successor = key(2), observer = key(3);
    static byte[] key(int n) { byte[] b = new byte[32]; b[0] = (byte)n; return b; }
    Message request(int type, byte[] key) {
        Message m = new Message(type); m.sessionId = session; m.offerId = 10;
        m.key = key; m.successorKey = successor; m.observerKey = observer;
        m.room = "SOURCE"; m.digest[0] = 1; return m;
    }
    HandoffRegistry start() throws Exception {
        HandoffRegistry r = new HandoffRegistry(root, System::currentTimeMillis);
        assertEquals(PENDING, r.handle(request(BEGIN, host), true).state); return r;
    }
    @Test void commitRequiresVerifiedSuccessorAndSurvivesRestart() throws Exception {
        HandoffRegistry r = start();
        assertEquals(PENDING, r.handle(request(COMMIT, host), true).state);
        assertEquals(PENDING, r.handle(request(VERIFIED, observer), true).state);
        assertEquals(STAGED, r.handle(request(VERIFIED, successor), false).state);
        assertEquals(COMMITTED, r.handle(request(COMMIT, host), false).state);
        r = new HandoffRegistry(root, System::currentTimeMillis);
        Message committed = r.handle(request(QUERY, observer), false);
        assertEquals(COMMITTED, committed.state); assertEquals(1, committed.epoch);
        assertEquals(COMMITTED, r.handle(request(ABORT, successor), false).state);
        assertEquals(COMMITTED, r.handle(request(COMMIT, host), false).state);
    }
    @Test void abortWinsAndUnknownNeverGrantsOwnership() throws Exception {
        HandoffRegistry r = start();
        assertEquals(ABORTED, r.handle(request(ABORT, host), false).state);
        assertEquals(ABORTED, r.handle(request(VERIFIED, successor), false).state);
        assertEquals(ABORTED, r.handle(request(COMMIT, host), false).state);
        Message missing = request(QUERY, host); missing.offerId++;
        assertEquals(UNKNOWN, r.handle(missing, false).state);
        assertEquals(DENIED, r.handle(request(QUERY, key(9)), false).state);
    }
    @Test void oldRequestsCannotOverwriteNewAttempt() throws Exception {
        HandoffRegistry r = start(); r.handle(request(ABORT, host), false);
        Message next = request(BEGIN, host); next.offerId = 11;
        assertEquals(PENDING, r.handle(next, true).state);
        assertEquals(ABORTED, r.handle(request(BEGIN, host), true).state);
        assertEquals(ABORTED, r.handle(request(COMMIT, host), false).state);
        next.type = QUERY; assertEquals(PENDING, r.handle(next, false).state);
    }
    @Test void readyRequiresNewRoomOwnershipAndCorrectRole() throws Exception {
        HandoffRegistry r = start();
        r.handle(request(VERIFIED, successor), false); r.handle(request(COMMIT, host), false);
        assertEquals(COMMITTED, r.handle(request(READY, successor), false).state);
        assertEquals(COMMITTED, r.handle(request(READY, observer), true).state);
        Message installed = request(INSTALLED, successor); installed.epoch = 1;
        assertTrue(r.handle(installed, false).installed);
        Message ready = request(READY, successor); ready.epoch = 1; ready.room = "NEW";
        assertEquals(ROOM_READY, r.handle(ready, true).state);
        assertEquals("NEW", r.handle(request(QUERY, host), false).room);
    }
    @Test void concurrentCommitAbortHasOneDurableOutcome() throws Exception {
        HandoffRegistry r = start(); r.handle(request(VERIFIED, successor), false);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Message> a = pool.submit(() -> r.handle(request(COMMIT, host), false));
            java.util.concurrent.Future<Message> b = pool.submit(() -> r.handle(request(ABORT, successor), false));
            assertEquals(a.get().state, b.get().state);
            assertEquals(a.get().state, new HandoffRegistry(root, System::currentTimeMillis).handle(request(QUERY, host), false).state);
        } finally { pool.shutdownNow(); }
    }
    @Test void codecRejectsTruncationAndRoundTrips() throws Exception {
        Message m = request(BEGIN, host); m.requestId = 31;
        byte[] bytes = encode(m); Message copy = decode(bytes, bytes.length);
        assertEquals(session, copy.sessionId); assertEquals(31, copy.requestId);
        assertArrayEquals(host, copy.key);
        for (int n = 0; n < bytes.length; n++) {
            final int length = n; assertThrows(java.io.IOException.class, () -> decode(bytes, length));
        }
    }
    @Test void retentionForgetsResultButKeepsEpochAndDoesNotReplayOldBegin() throws Exception {
        java.util.concurrent.atomic.AtomicLong time = new java.util.concurrent.atomic.AtomicLong(1000);
        HandoffRegistry r = new HandoffRegistry(root, time::get);
        r.handle(request(BEGIN, host), true); r.handle(request(ABORT, host), false);
        time.addAndGet(HandoffRegistry.RETENTION + 1); r.maintenance();
        assertEquals(UNKNOWN, r.handle(request(QUERY, host), false).state);
        assertEquals(UNKNOWN, r.handle(request(BEGIN, host), true).state);
        Message next = request(BEGIN, host); next.offerId = 11;
        assertEquals(PENDING, r.handle(next, true).state);
    }

    @Test void historicCommitDoesNotGrantLaunchAfterTheNextHandoff() throws Exception {
        HandoffRegistry r = start();
        r.handle(request(VERIFIED, successor), false); r.handle(request(COMMIT, host), false);
        assertTrue(r.handle(request(QUERY, successor), false).ownsCurrentEpoch);
        assertFalse(r.handle(request(QUERY, host), false).ownsCurrentEpoch);
        Message next = request(BEGIN, successor); next.offerId = 11; next.epoch = 1;
        next.successorKey = key(4); next.observerKey = key(5); next.room = "NEXT";
        assertEquals(PENDING, r.handle(next, true).state);
        Message previous = r.handle(request(QUERY, successor), false);
        assertEquals(COMMITTED, previous.state);
        assertFalse(previous.ownsCurrentEpoch);
        next.type = VERIFIED; next.key = key(4); r.handle(next, false);
        next.type = COMMIT; next.key = successor; r.handle(next, false);
        previous = r.handle(request(QUERY, successor), false);
        assertEquals(COMMITTED, previous.state); assertEquals(2, previous.currentEpoch);
        assertFalse(previous.ownsCurrentEpoch);
    }

    @Test void abortDoesNotDeclareSourceRestoredBeforeAuthenticatedRegistration() throws Exception {
        HandoffRegistry r = start();
        Message aborted = r.handle(request(ABORT, host), false);
        assertFalse(aborted.sourceRestored);
        Message recovered = request(RECOVERED, host); recovered.room = "RESTORED";
        assertFalse(r.handle(recovered, false).sourceRestored);
        Message wrongRole = request(RECOVERED, successor); wrongRole.room = "RESTORED";
        assertFalse(r.handle(wrongRole, true).sourceRestored);
        assertTrue(r.handle(recovered, true).sourceRestored);
        r = new HandoffRegistry(root, System::currentTimeMillis);
        Message result = r.handle(request(QUERY, observer), false);
        assertEquals(ABORTED, result.state); assertTrue(result.sourceRestored); assertEquals("RESTORED", result.room);
    }

    @Test void changingSessionIdCannotStartTwoTransfersFromOneRoom() throws Exception {
        HandoffRegistry r = start(); Message another = request(BEGIN, host); another.sessionId = UUID.randomUUID();
        assertEquals(DENIED, r.handle(another, true).state);
        r.handle(request(ABORT, host), false);
        assertEquals(PENDING, r.handle(another, true).state);
    }

}
