package net.peercraft.network.handoff;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static net.peercraft.network.handoff.HandoffAuthorityProtocol.*;

/** Successor reads COMMIT itself; delivery of START to/from the source is not a launch permission. */
public final class HandoffSuccessorFlow {
    public enum Outcome { READY, ABORTED, UNKNOWN, FAILED_AFTER_COMMIT, STOP_FAILED }
    public static final class Staged {
        public final byte[] digest;
        public Staged(byte[] digest) {
            if (digest == null || digest.length != 64) throw new IllegalArgumentException("Missing snapshot digest");
            this.digest = digest.clone();
        }
    }
    public static final class RegisteredRoom {
        public final String code;
        public final byte[] proof;
        public RegisteredRoom(String code, byte[] proof) {
            if (code == null || code.isEmpty() || proof == null || proof.length != 32) throw new IllegalArgumentException("Invalid registered room");
            this.code = code; this.proof = proof.clone();
        }
    }
    public interface Steps {
        /** Completes after consent/preflight and source PREPARE; ordinary gameplay continues until then. */
        CompletableFuture<Void> prepared();
        CompletableFuture<byte[]> receivedArchive();
        /** Fully unpack and validate before notifying rendezvous. Existing worlds stay untouched. */
        CompletableFuture<Staged> verifyStaging();
        boolean sourceAlive();
        default void stagingRecorded() throws IOException { }
        default void installationRecorded() throws IOException { }
        default void readyRecorded(RegisteredRoom room) throws IOException { }
        CompletableFuture<Void> installCommittedWorld();
        CompletableFuture<RegisteredRoom> startAndRegister();
        /** Stop a failed/late start and await actual server thread termination. */
        CompletableFuture<Void> stopNewServer();
        default void invalidateLaunch() { }
        CompletableFuture<Void> stopAttemptWorkers();
        CompletableFuture<Void> cleanupConfirmedAbort();
        void terminal(Outcome outcome, String room);
    }
    private final Steps steps;
    private final HandoffOperation operation;
    private final HandoffSourceFlow.Limits limits;
    private final AtomicBoolean started = new AtomicBoolean(), cancelled = new AtomicBoolean(), ended = new AtomicBoolean();
    private final CompletableFuture<Outcome> completion = new CompletableFuture<>();
    public HandoffSuccessorFlow(Steps steps, HandoffOperation operation, HandoffSourceFlow.Limits limits) {
        this.steps = steps; this.operation = operation; this.limits = limits;
    }
    public CompletableFuture<Outcome> start() {
        if (started.compareAndSet(false, true)) {
            Thread worker = new Thread(this::run, "PeerCraft-Handoff-Successor"); worker.setDaemon(true); worker.start();
        }
        return completion;
    }
    private boolean readyDecided;
    private final Object launchDecision = new Object();
    public void cancel() {
        synchronized (launchDecision) {
            if (ended.get() || readyDecided) return;
            if (cancelled.compareAndSet(false, true)) steps.invalidateLaunch();
        }
    }
    private static long deadline(long millis) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }
    private <T> T await(CompletableFuture<T> task, long end, boolean beforeCommit) throws IOException {
        return await(task, end, beforeCommit, beforeCommit);
    }
    private <T> T await(CompletableFuture<T> task, long end, boolean beforeCommit, boolean localCancel) throws IOException {
        try {
            while (true) {
                if ((localCancel && cancelled.get()) || (beforeCommit && !steps.sourceAlive())) throw new IOException("Source unavailable or attempt cancelled");
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
            outcome = Outcome.STOP_FAILED;
        }
        try { steps.terminal(outcome, room); }
        catch (RuntimeException callbackFailure) { org.slf4j.LoggerFactory.getLogger("peercraft").warn("[Handoff] Terminal callback failed", callbackFailure); }
        finally { completion.complete(outcome); }
    }
    private void run() {
        boolean committed = false, startRequested = false;
        try {
            await(steps.prepared(), deadline(limits.preparation), true);
            byte[] archiveHash = await(steps.receivedArchive(), deadline(limits.transfer), true);
            Staged staged = await(steps.verifyStaging(), deadline(limits.preparation), true);
            if (!MessageDigest.isEqual(archiveHash, staged.digest)) throw new IOException("Staged snapshot differs");
            operation.verified(staged.digest); steps.stagingRecorded();
            long commitDeadline = deadline(limits.preparation);
            while (true) {
                Message answer = operation.resolve();
                if (answer.state == ABORTED) throw new IOException("Authority cancelled attempt");
                if (answer.state == COMMITTED || answer.state == ROOM_READY) {
                    operation.requireCommitted(); committed = true; break;
                }
                if (cancelled.get() || !steps.sourceAlive() || System.nanoTime() >= commitDeadline) {
                    if (operation.abort()) throw new IOException("Attempt cancelled before COMMIT");
                    // ABORT lost the race; only a fresh confirmed query can grant launch.
                    operation.requireCommitted(); committed = true; break;
                }
                if (answer.state == UNKNOWN || answer.state == DENIED) { finish(Outcome.UNKNOWN, ""); return; }
                Thread.sleep(1000);
            }
            if (cancelled.get()) throw new IOException("Committed handoff launch cancelled");
            operation.requireCommitted();
            await(steps.installCommittedWorld(), deadline(limits.preparation), false); operation.installed(); steps.installationRecorded();
            if (cancelled.get()) throw new IOException("Committed handoff launch cancelled");
            startRequested = true;
            RegisteredRoom room = await(steps.startAndRegister(), deadline(limits.startup), false, true);
            synchronized (launchDecision) {
                if (cancelled.get()) throw new IOException("Committed launch cancelled");
                operation.ready(room.code, room.proof); steps.readyRecorded(room); readyDecided = true;
            }
            finish(Outcome.READY, room.code);
        } catch (IOException | RuntimeException | InterruptedException failed) {
            org.slf4j.LoggerFactory.getLogger("peercraft").warn("[Handoff] Successor failed before/after COMMIT (committed={}, startRequested={})", committed, startRequested, failed);
            if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
            try {
                if (!committed) {
                    if (!operation.abort()) {
                        Message answer = operation.resolve();
                        committed = answer.state == COMMITTED || answer.state == ROOM_READY;
                        if (!committed) { finish(Outcome.UNKNOWN, ""); return; }
                    } else {
                        stopWorkers();
                        await(steps.cleanupConfirmedAbort(), deadline(limits.preparation), false);
                        finish(Outcome.ABORTED, ""); return;
                    }
                }
                // A failed new host retains ownership and the world. Never restore the source here.
                if (startRequested) {
                    steps.invalidateLaunch();
                    try { await(steps.stopNewServer(), deadline(limits.startup), false); }
                    catch (IOException stopFailure) { finish(Outcome.STOP_FAILED, ""); return; }
                }
                stopWorkers();
                finish(Outcome.FAILED_AFTER_COMMIT, "");
            } catch (IOException | RuntimeException unresolved) { finish(committed ? Outcome.FAILED_AFTER_COMMIT : Outcome.UNKNOWN, ""); }
        }
    }
}
