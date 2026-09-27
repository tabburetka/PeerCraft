package net.peercraft.network.handoff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static net.peercraft.network.handoff.HandoffAuthorityProtocol.*;
import static org.junit.jupiter.api.Assertions.*;
class HandoffObserverFlowTest {
    @TempDir Path dir;
    @Test void lookupDoesNotBeginDuringTransferAndStartsOnlyAfterReady() throws Exception {
        AtomicInteger queries = new AtomicInteger(), reconnects = new AtomicInteger();
        HandoffOperation operation = new HandoffOperation(m -> {
            Message result = new Message(REPLY); result.isCurrentAttempt = true;
            result.state = queries.incrementAndGet() == 1 ? STAGED : ROOM_READY; result.room = "NEXT"; result.ownsCurrentEpoch = false; return result;
        }, new HandoffJournal(dir.resolve("observer"), UUID.randomUUID(), 1, 0, new byte[32]));
        HandoffObserverFlow flow = new HandoffObserverFlow(operation, new HandoffObserverFlow.Steps() {
            public void waiting() { assertEquals(0, reconnects.get()); }
            public CompletableFuture<Void> reconnect(String room, long timeout) {
                assertEquals(2, queries.get()); assertEquals("NEXT", room); reconnects.incrementAndGet(); return CompletableFuture.completedFuture(null);
            }
            public void terminal(HandoffObserverFlow.Outcome outcome) { }
        }, new HandoffSourceFlow.Limits(1000, 1000, 3000, 1000));
        assertEquals(HandoffObserverFlow.Outcome.RECONNECTED, flow.start().get(5, TimeUnit.SECONDS)); assertEquals(1, reconnects.get());
    }
    @Test void unknownResultNeverLooksUpOrReconnectsToARoom() throws Exception {
        HandoffOperation operation = new HandoffOperation(m -> { Message result = new Message(REPLY); result.isCurrentAttempt = true; result.state = UNKNOWN; return result; },
                new HandoffJournal(dir.resolve("observer"), UUID.randomUUID(), 1, 0, new byte[32]));
        HandoffObserverFlow flow = new HandoffObserverFlow(operation, new HandoffObserverFlow.Steps() {
            public void waiting() { }
            public CompletableFuture<Void> reconnect(String room, long timeout) { fail("UNKNOWN cannot reconnect"); return null; }
            public void terminal(HandoffObserverFlow.Outcome outcome) { }
        }, new HandoffSourceFlow.Limits(1000, 1000, 1000, 1000));
        assertEquals(HandoffObserverFlow.Outcome.UNKNOWN, flow.start().get(2, TimeUnit.SECONDS));
    }

    @Test void historicalReadyCannotReconnectToASupersededSession() throws Exception {
        AtomicInteger stopped = new AtomicInteger();
        HandoffOperation operation = new HandoffOperation(m -> {
            Message result = new Message(REPLY); result.isCurrentAttempt = true; result.state = ROOM_READY; result.room = "OLD";
            result.isCurrentAttempt = false; result.epoch = 1; result.currentEpoch = 2; result.ownsCurrentEpoch = false; return result;
        }, new HandoffJournal(dir.resolve("observer"), UUID.randomUUID(), 1, 0, new byte[32]));
        HandoffObserverFlow flow = new HandoffObserverFlow(operation, new HandoffObserverFlow.Steps() {
            public void waiting() { }
            public CompletableFuture<Void> reconnect(String room, long timeout) { fail("Superseded grant cannot reconnect"); return null; }
            public void stopReconnect() { stopped.incrementAndGet(); }
            public void terminal(HandoffObserverFlow.Outcome outcome) { }
        }, new HandoffSourceFlow.Limits(1000, 1000, 1000, 1000));
        assertEquals(HandoffObserverFlow.Outcome.ERROR, flow.start().get(2, TimeUnit.SECONDS));
        assertEquals(1, stopped.get());
    }
    @Test void cancellationInvalidatesPendingNativeConnection() throws Exception {
        CountDownLatch connecting = new CountDownLatch(1); CompletableFuture<Void> connection = new CompletableFuture<>();
        AtomicInteger stopped = new AtomicInteger();
        HandoffOperation operation = new HandoffOperation(m -> {
            Message result = new Message(REPLY); result.isCurrentAttempt = true; result.state = ROOM_READY; result.room = "NEXT";
            result.ownsCurrentEpoch = false; return result;
        }, new HandoffJournal(dir.resolve("observer"), UUID.randomUUID(), 1, 0, new byte[32]));
        HandoffObserverFlow flow = new HandoffObserverFlow(operation, new HandoffObserverFlow.Steps() {
            public void waiting() { }
            public CompletableFuture<Void> reconnect(String room, long timeout) { connecting.countDown(); return connection; }
            public void stopReconnect() { stopped.incrementAndGet(); }
            public void terminal(HandoffObserverFlow.Outcome outcome) { }
        }, new HandoffSourceFlow.Limits(1000, 1000, 1000, 3000));
        CompletableFuture<HandoffObserverFlow.Outcome> done = flow.start();
        assertTrue(connecting.await(1, TimeUnit.SECONDS)); flow.cancel();
        assertEquals(HandoffObserverFlow.Outcome.ERROR, done.get(1, TimeUnit.SECONDS));
        assertTrue(connection.isCancelled()); assertEquals(1, stopped.get());
    }
}
