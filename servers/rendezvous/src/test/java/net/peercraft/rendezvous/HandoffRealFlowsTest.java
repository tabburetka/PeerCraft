package net.peercraft.rendezvous;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.peercraft.network.handoff.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual registry, both wire codecs and all three production flows, including a lost COMMIT reply/restart. */
class HandoffRealFlowsTest {
    @TempDir Path root;
    @Test void threeRolesFinishWithoutSharingSuccessorLaunchPermission() throws Exception {
        UUID sid = UUID.randomUUID(); long offer = 73;
        byte[] host = new byte[32], successor = new byte[32], observer = new byte[32], hash = new byte[64];
        host[0] = 1; successor[0] = 2; observer[0] = 3; hash[0] = 9;
        AtomicReference<HandoffRegistry> registry = new AtomicReference<>(new HandoffRegistry(root.resolve("authority"), System::currentTimeMillis));
        HandoffAuthorityProtocol.Message begin = new HandoffAuthorityProtocol.Message(HandoffAuthorityProtocol.BEGIN);
        begin.sessionId = sid; begin.offerId = offer; begin.room = "SOURCE";
        begin.key = host; begin.successorKey = successor; begin.observerKey = observer;
        registry.get().handle(begin, true);
        HandoffOperation.Authority rpc = m -> {
            byte[] request = net.peercraft.network.handoff.HandoffAuthorityProtocol.encode(m);
            var wireRequest = HandoffAuthorityProtocol.decode(request, request.length);
            var reply = registry.get().handle(wireRequest, m.type == HandoffAuthorityProtocol.READY);
            if (m.type == HandoffAuthorityProtocol.COMMIT) {
                registry.set(new HandoffRegistry(root.resolve("authority"), System::currentTimeMillis));
                throw new java.io.IOException("lost COMMIT reply after durable write");
            }
            byte[] bytes = HandoffAuthorityProtocol.encode(reply);
            return net.peercraft.network.handoff.HandoffAuthorityProtocol.decode(bytes, bytes.length);
        };
        HandoffOperation sourceOp = new HandoffOperation(rpc, new HandoffJournal(root.resolve("source"), sid, offer, 0, host));
        HandoffOperation candidateOp = new HandoffOperation(rpc, new HandoffJournal(root.resolve("candidate"), sid, offer, 0, successor));
        HandoffOperation observerOp = new HandoffOperation(rpc, new HandoffJournal(root.resolve("observer"), sid, offer, 0, observer));
        CompletableFuture<Void> prepare = new CompletableFuture<>(), installed = new CompletableFuture<>();
        CompletableFuture<byte[]> received = new CompletableFuture<>(), staged = new CompletableFuture<>();
        CompletableFuture<String> room = new CompletableFuture<>(); AtomicBoolean simulatingSource = new AtomicBoolean(true);
        HandoffSourceFlow.Limits limits = new HandoffSourceFlow.Limits(5000, 5000, 5000, 5000);
        HandoffSuccessorFlow candidate = new HandoffSuccessorFlow(new HandoffSuccessorFlow.Steps() {
            public CompletableFuture<Void> prepared() { return prepare; }
            public CompletableFuture<byte[]> receivedArchive() { return received; }
            public CompletableFuture<HandoffSuccessorFlow.Staged> verifyStaging() { return CompletableFuture.completedFuture(new HandoffSuccessorFlow.Staged(hash)); }
            public boolean sourceAlive() { return true; }
            public void stagingRecorded() { staged.complete(hash); }
            public CompletableFuture<Void> installCommittedWorld() { assertFalse(simulatingSource.get()); return CompletableFuture.completedFuture(null); }
            public void installationRecorded() { installed.complete(null); }
            public CompletableFuture<HandoffSuccessorFlow.RegisteredRoom> startAndRegister() { assertFalse(simulatingSource.get()); return CompletableFuture.completedFuture(new HandoffSuccessorFlow.RegisteredRoom("NEW", new byte[32])); }
            public void readyRecorded(HandoffSuccessorFlow.RegisteredRoom r) { room.complete(r.code); }
            public CompletableFuture<Void> stopNewServer() { return CompletableFuture.completedFuture(null); }
            public CompletableFuture<Void> stopAttemptWorkers() { return CompletableFuture.completedFuture(null); }
            public CompletableFuture<Void> cleanupConfirmedAbort() { fail("Committed candidate cannot be deleted"); return CompletableFuture.completedFuture(null); }
            public void terminal(HandoffSuccessorFlow.Outcome outcome, String code) { }
        }, candidateOp, limits);
        HandoffSourceFlow source = new HandoffSourceFlow(new HandoffSourceFlow.Steps() {
            public CompletableFuture<Void> capabilities() { return CompletableFuture.completedFuture(null); }
            public CompletableFuture<Boolean> consent() { return CompletableFuture.completedFuture(true); }
            public CompletableFuture<Void> preflight() { return CompletableFuture.completedFuture(null); }
            public CompletableFuture<Void> prepareParticipants() { prepare.complete(null); return CompletableFuture.completedFuture(null); }
            public CompletableFuture<Void> saveAndStop() { simulatingSource.set(false); return CompletableFuture.completedFuture(null); }
            public CompletableFuture<HandoffSourceFlow.Snapshot> archiveClosedWorld() { assertFalse(simulatingSource.get()); return CompletableFuture.completedFuture(new HandoffSourceFlow.Snapshot(root.resolve("snapshot"), hash)); }
            public CompletableFuture<Void> transfer(HandoffSourceFlow.Snapshot snapshot) { received.complete(hash); return CompletableFuture.completedFuture(null); }
            public CompletableFuture<byte[]> verifiedStaging() { return staged; }
            public void committed(long epoch) { assertEquals(1, epoch); }
            public CompletableFuture<Void> installedSuccessor() { return installed; }
            public CompletableFuture<String> registeredSuccessorRoom() { return room; }
            public CompletableFuture<Void> stopAttemptWorkers() { return CompletableFuture.completedFuture(null); }
            public CompletableFuture<Void> cleanupConfirmedAbort() { fail("Committed source cannot clean"); return CompletableFuture.completedFuture(null); }
            public CompletableFuture<HandoffSuccessorFlow.RegisteredRoom> restoreSource() { fail("Committed source cannot restore"); return new CompletableFuture<>(); }
            public void terminal(HandoffSourceFlow.Outcome outcome, String code) { }
        }, sourceOp, limits, phase -> {});
        HandoffObserverFlow spectator = new HandoffObserverFlow(observerOp, new HandoffObserverFlow.Steps() {
            public void waiting() { }
            public CompletableFuture<Void> reconnect(String code, long timeout) { assertEquals("NEW", code); return CompletableFuture.completedFuture(null); }
            public void terminal(HandoffObserverFlow.Outcome outcome) { }
        }, limits);
        var c = candidate.start(); var o = spectator.start(); var h = source.start();
        assertEquals(HandoffSuccessorFlow.Outcome.READY, c.get(10, TimeUnit.SECONDS));
        assertEquals(HandoffSourceFlow.Outcome.READY, h.get(10, TimeUnit.SECONDS));
        assertEquals(HandoffObserverFlow.Outcome.RECONNECTED, o.get(10, TimeUnit.SECONDS));
        assertThrows(java.io.IOException.class, sourceOp::requireCommitted);
        assertThrows(java.io.IOException.class, observerOp::requireCommitted); candidateOp.requireCommitted();
    }
}
