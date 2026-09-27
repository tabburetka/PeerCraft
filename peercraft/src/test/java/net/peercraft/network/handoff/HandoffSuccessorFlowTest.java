package net.peercraft.network.handoff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static net.peercraft.network.handoff.HandoffAuthorityProtocol.*;
import static org.junit.jupiter.api.Assertions.*;
class HandoffSuccessorFlowTest {
    @TempDir Path dir;
    final List<String> events = Collections.synchronizedList(new ArrayList<>());
    int state = PENDING;
    boolean alive = true, commitBeforeSourceLoss, failStart, loseReadyReply;
    byte[] digest = new byte[64];
    { digest[0] = 1; }
    HandoffOperation operation() {
        return new HandoffOperation(m -> {
            if (m.type == VERIFIED) state = commitBeforeSourceLoss ? COMMITTED : STAGED;
            if (m.type == ABORT && state != COMMITTED && state != ROOM_READY) state = ABORTED;
            if (m.type == READY) { state = ROOM_READY; if (loseReadyReply) throw new IOException("lost ready reply"); }
            Message result = new Message(REPLY); result.isCurrentAttempt = true; result.state = state; result.digest = digest;
            result.epoch = state == COMMITTED || state == ROOM_READY ? 1 : 0;
            result.currentEpoch = result.epoch; result.ownsCurrentEpoch = true; result.installed = m.type == INSTALLED || state == ROOM_READY;
            result.room = state == ROOM_READY ? "NEW" : ""; return result;
        }, new HandoffJournal(dir.resolve("incoming"), UUID.randomUUID(), 5, 0, new byte[32]));
    }
    class Steps implements HandoffSuccessorFlow.Steps {
        <T> CompletableFuture<T> step(String event, T value) { events.add(event); return CompletableFuture.completedFuture(value); }
        public CompletableFuture<Void> prepared() { return step("prepare", null); }
        public CompletableFuture<byte[]> receivedArchive() { return step("receive", digest); }
        public CompletableFuture<HandoffSuccessorFlow.Staged> verifyStaging() {
            return step("verify", new HandoffSuccessorFlow.Staged(digest));
        }
        public boolean sourceAlive() { return alive; }
        public CompletableFuture<Void> installCommittedWorld() { assertEquals(COMMITTED, state); return step("install", null); }
        public CompletableFuture<HandoffSuccessorFlow.RegisteredRoom> startAndRegister() {
            events.add("start");
            if (failStart) { CompletableFuture<HandoffSuccessorFlow.RegisteredRoom> f = new CompletableFuture<>(); f.completeExceptionally(new IOException("registration failed")); return f; }
            return CompletableFuture.completedFuture(new HandoffSuccessorFlow.RegisteredRoom("NEW", new byte[32]));
        }
        public CompletableFuture<Void> stopNewServer() { return step("stop server", null); }
        public CompletableFuture<Void> stopAttemptWorkers() { return step("close workers", null); }
        public CompletableFuture<Void> cleanupConfirmedAbort() { assertEquals(ABORTED, state); return step("cleanup", null); }
        public void terminal(HandoffSuccessorFlow.Outcome result, String room) { events.add("terminal:" + result); }
    }
    HandoffSuccessorFlow flow(Steps steps) { return new HandoffSuccessorFlow(steps, operation(), new HandoffSourceFlow.Limits(1000, 1000, 1000, 1000)); }
    @Test void launchesFromConfirmedCommitWithoutStartPacketAndSurvivesLostReadyReply() throws Exception {
        commitBeforeSourceLoss = true; loseReadyReply = true;
        assertEquals(HandoffSuccessorFlow.Outcome.READY, flow(new Steps()).start().get(3, TimeUnit.SECONDS));
        assertEquals(Arrays.asList("prepare", "receive", "verify", "install", "start", "close workers", "terminal:READY"), events);
    }
    @Test void sourceLostBeforeCommitAbortsBeforeDeletingScratch() throws Exception {
        alive = false;
        assertEquals(HandoffSuccessorFlow.Outcome.ABORTED, flow(new Steps()).start().get(3, TimeUnit.SECONDS));
        assertFalse(events.contains("install")); assertFalse(events.contains("start"));
        assertTrue(events.indexOf("close workers") < events.indexOf("cleanup"));
    }
    @Test void failedStartStopsServerAndRetainsCommittedWorld() throws Exception {
        commitBeforeSourceLoss = true; failStart = true;
        assertEquals(HandoffSuccessorFlow.Outcome.FAILED_AFTER_COMMIT, flow(new Steps()).start().get(3, TimeUnit.SECONDS));
        assertTrue(events.contains("stop server")); assertFalse(events.contains("cleanup"));
        assertEquals(COMMITTED, state);
    }
    @Test void cancelDuringStartNeverAnnouncesReady() throws Exception {
        commitBeforeSourceLoss = true;
        CountDownLatch starting = new CountDownLatch(1);
        CompletableFuture<HandoffSuccessorFlow.RegisteredRoom> registration = new CompletableFuture<>();
        Steps steps = new Steps() {
            public CompletableFuture<HandoffSuccessorFlow.RegisteredRoom> startAndRegister() {
                events.add("start"); starting.countDown(); return registration;
            }
        };
        HandoffSuccessorFlow flow = flow(steps);
        CompletableFuture<HandoffSuccessorFlow.Outcome> done = flow.start();
        assertTrue(starting.await(1, TimeUnit.SECONDS)); flow.cancel(); flow.cancel();
        registration.complete(new HandoffSuccessorFlow.RegisteredRoom("NEW", new byte[32]));
        assertEquals(HandoffSuccessorFlow.Outcome.FAILED_AFTER_COMMIT, done.get(3, TimeUnit.SECONDS));
        assertEquals(COMMITTED, state); assertTrue(events.contains("stop server"));
        assertFalse(events.contains("cleanup"));
    }
}
