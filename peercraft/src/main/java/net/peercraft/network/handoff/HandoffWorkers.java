package net.peercraft.network.handoff;
import java.io.IOException;
import java.util.concurrent.*;
/** Bounded attempt-local I/O, quiesced before removing scratch paths. */
public final class HandoffWorkers {
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), r -> { Thread t = new Thread(r, "PeerCraft-Handoff-IO"); t.setDaemon(true); return t; });
    private final java.util.Set<CompletableFuture<?>> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>(); pending.add(result);
        result.whenComplete((value, failure) -> pending.remove(result));
        try { executor.execute(() -> {
            if (result.isCancelled()) return;
            try { result.complete(task.call()); } catch (Exception failed) { result.completeExceptionally(failed); }
        }); } catch (RejectedExecutionException closed) { result.completeExceptionally(closed); }
        return result;
    }
    public void stopAndAwait(long timeoutMillis) throws IOException {
        executor.shutdownNow();
        for (CompletableFuture<?> task : pending) task.cancel(false);
        try { if (!executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) throw new IOException("Attempt I/O still owns files"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    public void finish() { executor.shutdown(); }
}
