package net.peercraft.network.handoff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static net.peercraft.network.handoff.HandoffAuthorityProtocol.*;

class HandoffOperationTest {
    @TempDir Path dir;
    HandoffJournal journal() { return new HandoffJournal(dir.resolve("attempt"), UUID.randomUUID(), 4, 0, new byte[32]); }
    @Test void lostCommitReplyQueriesResultAndRecordsBeforeSending() throws Exception {
        HandoffJournal j = journal();
        HandoffOperation op = new HandoffOperation(m -> {
            assertEquals(HandoffJournal.Phase.COMMIT_SENT, HandoffJournal.read(dir.resolve("attempt")).phase);
            if (m.type == COMMIT) throw new IOException("lost reply");
            Message result = new Message(REPLY); result.state = COMMITTED; result.epoch = 1; return result;
        }, j);
        op.stopped(); assertEquals(COMMITTED, op.commit(new byte[64]).state);
        assertEquals(HandoffJournal.Phase.COMMITTED, HandoffJournal.read(dir.resolve("attempt")).phase);
        assertFalse(op.abort());
    }
    @Test void unknownCommitPreservesJournalAndDeniesLaunchAndCleanup() throws Exception {
        HandoffJournal j = journal();
        HandoffOperation op = new HandoffOperation(m -> { Message r = new Message(REPLY); r.state = UNKNOWN; return r; }, j);
        assertThrows(IOException.class, () -> op.commit(new byte[64]));
        op.stopped(); assertEquals(UNKNOWN, op.commit(new byte[64]).state);
        assertEquals(HandoffJournal.Phase.COMMIT_SENT, j.phase);
        assertThrows(IOException.class, op::requireCommitted); assertFalse(op.abort());
        assertEquals(HandoffJournal.Phase.COMMIT_SENT, HandoffJournal.read(dir.resolve("attempt")).phase);
    }
    @Test void historicalCommitOrAnotherOwnerCannotLaunch() throws Exception {
        HandoffJournal j = journal();
        HandoffOperation op = new HandoffOperation(m -> {
            Message r = new Message(REPLY); r.state = COMMITTED; r.epoch = 1; r.currentEpoch = 2;
            r.ownsCurrentEpoch = false; return r;
        }, j);
        assertThrows(IOException.class, op::requireCommitted);
        assertFalse(op.abort());
    }
    @Test void onlyConfirmedAbortAllowsCleanup() throws Exception {
        HandoffJournal j = journal();
        HandoffOperation op = new HandoffOperation(m -> { Message r = new Message(REPLY); r.state = ABORTED; return r; }, j);
        assertTrue(op.abort()); assertEquals(HandoffJournal.Phase.ABORTED, HandoffJournal.read(dir.resolve("attempt")).phase);
        assertThrows(IOException.class, op::stopped);
    }
    @Test void failedCommitPersistenceDoesNotConsumeEpochInMemory() throws Exception {
        HandoffJournal j = journal();
        HandoffOperation op = new HandoffOperation(m -> {
            if (m.type == COMMIT) {
                java.nio.file.Files.delete(dir.resolve("attempt"));
                java.nio.file.Files.createDirectory(dir.resolve("attempt"));
            }
            Message r = new Message(REPLY); r.state = COMMITTED; r.epoch = 1; return r;
        }, j);
        op.stopped(); assertThrows(IOException.class, () -> op.commit(new byte[64]));
        java.nio.file.Files.delete(dir.resolve("attempt"));
        assertEquals(COMMITTED, op.resolve().state);
        assertEquals(1, j.epoch); assertEquals(HandoffJournal.Phase.COMMITTED, j.phase);
    }
    @Test void beforeAndAfterRenameFailuresCanResolveSameCommitWithoutConsumingEpoch() throws Exception {
        for (boolean after : new boolean[] {false, true}) {
            Path file = dir.resolve("fault-" + after); var fail = new java.util.concurrent.atomic.AtomicBoolean(false);
            HandoffJournal j = new HandoffJournal(file, UUID.randomUUID(), 9, 0, new byte[32], moved -> {
                if (moved == after && fail.compareAndSet(true, false)) throw new IOException("injected persistence failure");
            });
            HandoffOperation op = new HandoffOperation(m -> {
                if (m.type == COMMIT) fail.set(true);
                Message r = new Message(REPLY); r.state = COMMITTED; r.epoch = 1; return r;
            }, j);
            op.stopped(); assertThrows(IOException.class, () -> op.commit(new byte[64]));
            assertEquals(0, j.epoch); assertEquals(HandoffJournal.Phase.COMMIT_SENT, j.phase);
            assertEquals(COMMITTED, op.resolve().state); assertEquals(1, j.epoch);
            HandoffJournal restarted = HandoffJournal.read(file);
            assertEquals(1, restarted.epoch); assertEquals(HandoffJournal.Phase.COMMITTED, restarted.phase);
        }
    }
}
