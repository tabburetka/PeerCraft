package net.peercraft.network.handoff;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static net.peercraft.network.handoff.HandoffAuthorityProtocol.*;

/** Source orchestration. Platform/network steps return promptly and complete outside the render thread. */
public final class HandoffSourceFlow {
    public enum Outcome { READY, DECLINED, ABORTED, UNKNOWN, FAILED_AFTER_COMMIT, RECOVERY_FAILED }
    public static final class Snapshot {
        public final Path archive;
        public final byte[] digest;
        public Snapshot(Path archive, byte[] digest) {
            if (archive == null || digest == null || digest.length != 64) throw new IllegalArgumentException("Invalid closed snapshot");
            this.archive = archive; this.digest = digest.clone();
        }
    }
    public interface Steps {
        CompletableFuture<Void> capabilities();
        CompletableFuture<Boolean> consent();
        /** Includes execution manifest, space, return destination and backup policy. */
        CompletableFuture<Void> preflight();
        CompletableFuture<Void> prepareParticipants();
        /** Completes only after saving and actual source server thread termination. */
        CompletableFuture<Void> saveAndStop();
        CompletableFuture<Snapshot> archiveClosedWorld();
        CompletableFuture<Void> transfer(Snapshot snapshot);
        CompletableFuture<byte[]> verifiedStaging();
        default void beforeCommit() throws IOException { }
        /** Mark the old copy and send START; never launch or restore the old source here. */
        void committed(long epoch) throws IOException;
        CompletableFuture<Void> installedSuccessor();
        CompletableFuture<String> registeredSuccessorRoom();
        /** Stop all attempt workers/descriptors before cleanup. */
        CompletableFuture<Void> stopAttemptWorkers();
        CompletableFuture<Void> cleanupConfirmedAbort();
        /** Must check old thread termination before reopening; returns registered source room. */
        CompletableFuture<HandoffSuccessorFlow.RegisteredRoom> restoreSource();
        default CompletableFuture<Void> stopFailedRestore() { return CompletableFuture.completedFuture(null); }
        void terminal(Outcome outcome, String room);
    }
    public static final class Limits {
        final long offer, preparation, transfer, startup;
        public Limits(long offer, long preparation, long transfer, long startup) {
            if (offer <= 0 || preparation <= 0 || transfer <= 0 || startup <= 0) throw new IllegalArgumentException("Invalid handoff deadlines");
            this.offer = offer; this.preparation = preparation; this.transfer = transfer; this.startup = startup;
        }
        public static Limits defaults() { return new Limits(20_000, 10 * 60_000, 60 * 60_000, 3 * 60_000); }
    }
    private final Steps steps;
    private final HandoffOperation operation;
    private final HandoffPhases phases;
    private final Consumer<HandoffPhases.Phase> status;
    private final Limits limits;
    private final AtomicBoolean cancelled = new AtomicBoolean(), started = new AtomicBoolean(), ended = new AtomicBoolean();
    private final CompletableFuture<Outcome> completion = new CompletableFuture<>();
    public HandoffSourceFlow(Steps steps, HandoffOperation operation, Limits limits, Consumer<HandoffPhases.Phase> status) {
        this(steps, operation, limits, status, new HandoffPhases());
    }
    public HandoffSourceFlow(Steps steps, HandoffOperation operation, Limits limits, Consumer<HandoffPhases.Phase> status, HandoffPhases phases) {
        this.steps = steps; this.operation = operation; this.limits = limits; this.status = status; this.phases = phases;
    }
    public CompletableFuture<Outcome> start() {
        if (!started.compareAndSet(false, true)) return completion;
        Thread worker = new Thread(this::run, "PeerCraft-Handoff-Source"); worker.setDaemon(true); worker.start();
        return completion;
    }
    public void cancel() { cancelled.set(true); }
    public HandoffPhases.Phase phase() { return phases.phase(); }
    private void advance(HandoffPhases.Phase phase) { phases.advance(phase); status.accept(phase); }
    private static long deadline(long millis) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }
    private <T> T await(CompletableFuture<T> task, long end, boolean cancellable) throws IOException {
        try {
            while (true) {
                if (cancellable && cancelled.get()) throw new IOException("Handoff cancelled");
                long remaining = end - System.nanoTime(); if (remaining <= 0) throw new IOException("Handoff phase timed out");
                try { return task.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS); }
                catch (TimeoutException retry) { }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException(interrupted);
        } catch (ExecutionException | CancellationException failed) { throw new IOException("Handoff step failed", failed); }
    }
    private final AtomicBoolean workersStopped = new AtomicBoolean();
    private void stopWorkers() throws IOException {
        if (workersStopped.compareAndSet(false, true)) {
            try { await(steps.stopAttemptWorkers(), deadline(limits.preparation), false); }
            catch (IOException failed) { workersStopped.set(false); throw failed; }
        }
    }
    private void finish(Outcome outcome, String room) {
        if (!ended.compareAndSet(false, true)) return;
        try { stopWorkers(); }
        catch (IOException failure) {
            org.slf4j.LoggerFactory.getLogger("peercraft").warn("[Handoff] Attempt workers did not stop", failure);
            outcome = Outcome.RECOVERY_FAILED;
        }
        try { steps.terminal(outcome, room); }
        catch (RuntimeException callbackFailure) {
            org.slf4j.LoggerFactory.getLogger("peercraft").warn("[Handoff] Terminal callback failed", callbackFailure);
        } finally { completion.complete(outcome); }
    }
    private void run() {
        boolean prepareRequested = false, stopRequested = false, committed = false, declined = false;
        try {
            await(steps.capabilities(), deadline(limits.offer), true);
            advance(HandoffPhases.Phase.OFFER);
            if (!await(steps.consent(), deadline(limits.offer), true)) { declined = true; throw new IOException("Successor declined"); }
            advance(HandoffPhases.Phase.PREFLIGHT);
            await(steps.preflight(), deadline(limits.preparation), true);
            long preparation = deadline(limits.preparation);
            advance(HandoffPhases.Phase.PREPARE); prepareRequested = true;
            await(steps.prepareParticipants(), preparation, true);
            advance(HandoffPhases.Phase.SAVE_AND_STOP); stopRequested = true;
            await(steps.saveAndStop(), preparation, true); operation.stopped();
            Snapshot snapshot = await(steps.archiveClosedWorld(), preparation, true);
            advance(HandoffPhases.Phase.TRANSFER);
            await(steps.transfer(snapshot), deadline(limits.transfer), true);
            advance(HandoffPhases.Phase.VERIFIED_STAGING);
            byte[] verified = await(steps.verifiedStaging(), deadline(limits.preparation), true);
            if (!MessageDigest.isEqual(snapshot.digest, verified)) throw new IOException("Staged snapshot differs");
            if (cancelled.get()) throw new IOException("Handoff cancelled");
            steps.beforeCommit();
            advance(HandoffPhases.Phase.COMMIT_SENT);
            Message answer = operation.commit(snapshot.digest);
            if (answer.state != COMMITTED && answer.state != ROOM_READY) {
                if (answer.state == ABORTED) throw new IOException("Authority cancelled handoff");
                phases.advance(HandoffPhases.Phase.UNKNOWN); finish(Outcome.UNKNOWN, ""); return;
            }
            committed = true; advance(HandoffPhases.Phase.COMMITTED);
            steps.committed(answer.epoch);
            try { await(steps.installedSuccessor(), deadline(limits.preparation), false); }
            catch (IOException lostInstallConfirmation) {
                Message result = operation.query();
                if (!result.isCurrentAttempt || result.currentEpoch != result.epoch || !result.installed || (result.state != COMMITTED && result.state != ROOM_READY)
                        || result.epoch != answer.epoch) throw lostInstallConfirmation;
            }
            long startup = deadline(limits.startup);
            advance(HandoffPhases.Phase.STARTING);
            String room;
            try { room = await(steps.registeredSuccessorRoom(), startup, false); }
            catch (IOException unavailable) {
                Message result = operation.query();
                if (!result.isCurrentAttempt || result.currentEpoch != result.epoch || result.state != ROOM_READY || result.epoch != answer.epoch || result.room.isEmpty()) throw unavailable;
                room = result.room;
            }
            // The control packet is a hint; registration is proved by the authority itself.
            Message ready = operation.query();
            if (!ready.isCurrentAttempt || ready.currentEpoch != ready.epoch || ready.state != ROOM_READY || ready.epoch != answer.epoch || !room.equals(ready.room))
                throw new IOException("Successor room is not registered for this attempt");
            advance(HandoffPhases.Phase.ROOM_REGISTERED); advance(HandoffPhases.Phase.READY);
            finish(Outcome.READY, room);
        } catch (IOException | RuntimeException failed) {
            org.slf4j.LoggerFactory.getLogger("peercraft").warn("[Handoff] Source failed in phase {}", phases.phase(), failed);
            if (committed) { finish(Outcome.FAILED_AFTER_COMMIT, ""); return; }
            try {
                if (!operation.abort()) {
                    Message result = operation.query();
                    if (result.state == COMMITTED || result.state == ROOM_READY) finish(Outcome.FAILED_AFTER_COMMIT, result.room);
                    else finish(Outcome.UNKNOWN, "");
                    return;
                }
                phases.confirmedAbort();
                stopWorkers();
                await(steps.cleanupConfirmedAbort(), deadline(limits.preparation), false);
                String room = "";
                if (prepareRequested) {
                    try {
                        HandoffSuccessorFlow.RegisteredRoom restored = await(steps.restoreSource(), deadline(limits.startup), false);
                        operation.sourceRestored(restored.code, restored.proof); room = restored.code;
                    } catch (IOException failedRestore) {
                        await(steps.stopFailedRestore(), deadline(limits.startup), false); throw failedRestore;
                    }
                }
                finish(declined ? Outcome.DECLINED : Outcome.ABORTED, room);
            } catch (IOException | RuntimeException unresolved) {
                finish(operationPhaseCommitted() ? Outcome.FAILED_AFTER_COMMIT : Outcome.RECOVERY_FAILED, "");
            }
        }
    }
    private boolean operationPhaseCommitted() {
        try { int state = operation.query().state; return state == COMMITTED || state == ROOM_READY; }
        catch (IOException unavailable) { return false; }
    }
}
