package net.peercraft.network.handoff;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static net.peercraft.network.handoff.HandoffAuthorityProtocol.*;

/** Waiting is independent of room lookup: reconnect starts only after authoritative READY or source recovery. */
public final class HandoffObserverFlow {
    public enum Outcome { RECONNECTED, ERROR, UNKNOWN }
    public interface Steps {
        void waiting();
        /** Implement its own three-minute connection deadline; no lookup during archive transfer. */
        CompletableFuture<Void> reconnect(String registeredRoom, long timeoutMillis);
        /** Invalidate queued native connection callbacks before displaying an error. */
        default void stopReconnect() { }
        void terminal(Outcome outcome);
    }
    private final HandoffOperation operation;
    private final Steps steps;
    private final HandoffSourceFlow.Limits limits;
    private final AtomicBoolean started = new AtomicBoolean(), cancelled = new AtomicBoolean();
    private final CompletableFuture<Outcome> completion = new CompletableFuture<>();
    public HandoffObserverFlow(HandoffOperation operation, Steps steps, HandoffSourceFlow.Limits limits) {
        this.operation = operation; this.steps = steps; this.limits = limits;
    }
    public CompletableFuture<Outcome> start() {
        if (started.compareAndSet(false, true)) {
            Thread worker = new Thread(this::run, "PeerCraft-Handoff-Observer"); worker.setDaemon(true); worker.start();
        }
        return completion;
    }
    public void cancel() { cancelled.set(true); }
    private static long deadline(long millis) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }
    private void awaitReconnect(CompletableFuture<Void> connection) throws IOException, InterruptedException, ExecutionException {
        long end = deadline(limits.startup);
        try {
            while (!cancelled.get()) {
                long left = end - System.nanoTime();
                if (left <= 0) throw new IOException("Reconnect timed out");
                try { connection.get(Math.min(left, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS); return; }
                catch (TimeoutException retry) { }
            }
            throw new IOException("Reconnect cancelled");
        } finally { if (!connection.isDone()) connection.cancel(false); }
    }
    private void run() {
        Outcome outcome = Outcome.ERROR;
        try {
            steps.waiting();
            long end = deadline(limits.preparation + limits.transfer + limits.preparation);
            boolean committedSeen = false, installedSeen = false, abortedSeen = false;
            while (!cancelled.get()) {
                Message answer = operation.query();
                if (answer.state == UNKNOWN || answer.state == DENIED) { outcome = Outcome.UNKNOWN; break; }
                boolean ready = answer.state == ROOM_READY || (answer.state == ABORTED && answer.sourceRestored);
                if (ready) {
                    if (answer.room.isEmpty() || !answer.isCurrentAttempt || answer.currentEpoch != answer.epoch)
                        throw new IOException("Room belongs to a superseded handoff");
                    awaitReconnect(steps.reconnect(answer.room, limits.startup));
                    outcome = Outcome.RECONNECTED; break;
                }
                if (answer.state == COMMITTED && !committedSeen) {
                    committedSeen = true; end = deadline(limits.preparation);
                }
                if (answer.state == COMMITTED && answer.installed && !installedSeen) {
                    installedSeen = true; end = deadline(limits.startup);
                }
                if (answer.state == ABORTED && !abortedSeen) {
                    abortedSeen = true; end = deadline(limits.startup);
                }
                if (System.nanoTime() >= end) break;
                Thread.sleep(Math.min(3_000, Math.max(1, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()))));
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (IOException | ExecutionException | RuntimeException failed) { }
        finally {
            try {
                if (outcome != Outcome.RECONNECTED) steps.stopReconnect();
                steps.terminal(outcome);
            }
            catch (RuntimeException callbackFailure) { org.slf4j.LoggerFactory.getLogger("peercraft").warn("[Handoff] Observer callback failed", callbackFailure); }
            finally { completion.complete(outcome); }
        }
    }
}
