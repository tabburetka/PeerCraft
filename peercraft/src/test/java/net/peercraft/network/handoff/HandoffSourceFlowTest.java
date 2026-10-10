package net.peercraft.network.handoff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static net.peercraft.network.handoff.HandoffAuthorityProtocol.*;
import static org.junit.jupiter.api.Assertions.*;
class HandoffSourceFlowTest {
    @TempDir Path dir;
    final List<String> events = Collections.synchronizedList(new ArrayList<>());
    int authorityState = PENDING;
    boolean sourceRunning = true;
    byte[] hash = new byte[64];
    HandoffOperation operation(boolean unknown) {
        return new HandoffOperation(m -> {
            if (m.type == COMMIT) {
                events.add("commit"); assertFalse(sourceRunning);
                if (unknown) { authorityState = UNKNOWN; throw new IOException("lost"); }
                assertEquals(STAGED, authorityState); authorityState = COMMITTED;
            }
            if (m.type == ABORT && authorityState != UNKNOWN && authorityState != COMMITTED && authorityState != ROOM_READY) authorityState = ABORTED;
            Message answer = new Message(REPLY); answer.isCurrentAttempt = true; answer.ownsCurrentEpoch = authorityState != COMMITTED && authorityState != ROOM_READY; answer.state = authorityState; answer.epoch = 1; answer.currentEpoch = 1;
            answer.room = authorityState == ROOM_READY ? "NEXT" : m.type == RECOVERED ? "SOURCE" : "";
            if (m.type == RECOVERED) { answer.sourceRestored = true; answer.epoch = 0; answer.currentEpoch = 0; answer.ownsCurrentEpoch = true; }
            return answer;
        }, new HandoffJournal(dir.resolve("attempt"), UUID.randomUUID(), 3, 0, new byte[32]));
    }
    class Steps implements HandoffSourceFlow.Steps {
        boolean consent = true, failSave;
        <T> CompletableFuture<T> step(String name, T value) { events.add(name); return CompletableFuture.completedFuture(value); }
        public CompletableFuture<Void> capabilities() { return step("capabilities", null); }
        public CompletableFuture<Boolean> consent() { return step("consent", consent); }
        public CompletableFuture<Void> preflight() { assertTrue(sourceRunning); return step("preflight", null); }
        public CompletableFuture<Void> prepareParticipants() { return step("prepare", null); }
        public CompletableFuture<Void> saveAndStop() {
            events.add("save/stop");
            if (failSave) { CompletableFuture<Void> failed = new CompletableFuture<>(); failed.completeExceptionally(new IOException("disk full")); return failed; }
            sourceRunning = false; return CompletableFuture.completedFuture(null);
        }
        public CompletableFuture<HandoffSourceFlow.Snapshot> archiveClosedWorld() {
            assertFalse(sourceRunning); return step("archive", new HandoffSourceFlow.Snapshot(dir.resolve("snapshot.zip"), hash));
        }
        public CompletableFuture<Void> transfer(HandoffSourceFlow.Snapshot snapshot) { return step("transfer", null); }
        public CompletableFuture<byte[]> verifiedStaging() { authorityState = STAGED; return step("staging", hash); }
        public void committed(long epoch) { assertFalse(sourceRunning); assertEquals(COMMITTED, authorityState); events.add("start"); authorityState = ROOM_READY; }
        public CompletableFuture<Void> installedSuccessor() { return step("installed", null); }
        public CompletableFuture<String> registeredSuccessorRoom() { return step("registered", "NEXT"); }
        public CompletableFuture<Void> stopAttemptWorkers() { return step("close workers", null); }
        public CompletableFuture<Void> cleanupConfirmedAbort() { assertEquals(ABORTED, authorityState); return step("cleanup", null); }
        public CompletableFuture<HandoffSuccessorFlow.RegisteredRoom> restoreSource() { assertEquals(ABORTED, authorityState); sourceRunning = true; return step("restore", new HandoffSuccessorFlow.RegisteredRoom("SOURCE", new byte[32])); }
        public void terminal(HandoffSourceFlow.Outcome result, String room) { events.add("terminal:" + result); }
    }
    HandoffSourceFlow flow(Steps steps, boolean unknown) {
        return new HandoffSourceFlow(steps, operation(unknown), new HandoffSourceFlow.Limits(1000, 1000, 1000, 1000), phase -> {});
    }
    @Test void fullSequenceStopsOldSimulationBeforeCommitAndStart() throws Exception {
        assertEquals(HandoffSourceFlow.Outcome.READY, flow(new Steps(), false).start().get(3, TimeUnit.SECONDS));
        assertEquals(Arrays.asList("capabilities", "consent", "preflight", "prepare", "save/stop", "archive", "transfer", "staging", "commit", "start", "installed", "registered", "close workers", "terminal:READY"), events);
    }
    @Test void declineDoesNotFreezeOrStopPlayers() throws Exception {
        Steps steps = new Steps(); steps.consent = false;
        assertEquals(HandoffSourceFlow.Outcome.DECLINED, flow(steps, false).start().get(3, TimeUnit.SECONDS));
        assertFalse(events.contains("prepare")); assertFalse(events.contains("save/stop")); assertTrue(sourceRunning);
    }
    @Test void failedSuccessorPreflightAfterConsentLeavesSourceRunning() throws Exception {
        CompletableFuture<Void> preflight = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        Steps steps = new Steps() {
            public CompletableFuture<Void> preflight() { started.countDown(); return preflight; }
        };
        CompletableFuture<HandoffSourceFlow.Outcome> outcome = flow(steps, false).start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        preflight.completeExceptionally(new IOException("Participant failed preflight"));
        assertEquals(HandoffSourceFlow.Outcome.ABORTED, outcome.get(1, TimeUnit.SECONDS));
        assertTrue(sourceRunning);
        assertFalse(events.contains("prepare"));
        assertFalse(events.contains("save/stop"));
        assertFalse(events.contains("archive"));
        assertFalse(events.contains("restore"));
        assertEquals(ABORTED, authorityState);
    }
    @Test void saveErrorNeverArchivesOrCommitsAndOnlyCleansAfterWorkersClose() throws Exception {
        Steps steps = new Steps(); steps.failSave = true;
        assertEquals(HandoffSourceFlow.Outcome.ABORTED, flow(steps, false).start().get(3, TimeUnit.SECONDS));
        assertFalse(events.contains("archive")); assertFalse(events.contains("commit"));
        assertTrue(events.indexOf("close workers") < events.indexOf("cleanup")); assertTrue(sourceRunning);
    }
    @Test void unknownCommitCannotStartCleanOrRestoreEitherCopy() throws Exception {
        assertEquals(HandoffSourceFlow.Outcome.UNKNOWN, flow(new Steps(), true).start().get(3, TimeUnit.SECONDS));
        assertFalse(sourceRunning); assertFalse(events.contains("start")); assertFalse(events.contains("cleanup")); assertFalse(events.contains("restore")); assertTrue(events.contains("close workers"));
    }
    @Test void preparationFailureRestoresRoomEvenWhenSaveNeverStarted() throws Exception {
        Steps steps = new Steps() {
            public CompletableFuture<Void> prepareParticipants() {
                events.add("prepare"); CompletableFuture<Void> result = new CompletableFuture<>();
                result.completeExceptionally(new IOException("Participant disappeared")); return result;
            }
        };
        assertEquals(HandoffSourceFlow.Outcome.ABORTED, flow(steps, false).start().get(3, TimeUnit.SECONDS));
        assertFalse(events.contains("save/stop")); assertTrue(events.contains("restore")); assertTrue(sourceRunning);
    }
}
