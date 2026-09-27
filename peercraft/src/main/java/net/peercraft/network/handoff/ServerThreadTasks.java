package net.peercraft.network.handoff;

import java.io.IOException;
import java.util.concurrent.*;

/** Server tick task queue for Minecraft 1.7.10, which predates IThreadListener. */
public final class ServerThreadTasks {
    private static final ConcurrentMap<Object, ConcurrentLinkedQueue<FutureTask<Void>>> queues = new ConcurrentHashMap<>();
    private ServerThreadTasks() { }
    public static void execute(Object server, Runnable task) throws IOException {
        FutureTask<Void> result = new FutureTask<>(task, null);
        queues.compute(server, (key, queue) -> {
            if (queue == null) queue = new ConcurrentLinkedQueue<>();
            queue.add(result); return queue;
        });
        try { result.get(30, TimeUnit.SECONDS); }
        catch (InterruptedException e) { result.cancel(false); Thread.currentThread().interrupt(); throw new IOException(e); }
        catch (ExecutionException | TimeoutException e) { result.cancel(false); throw new IOException("Server-thread save failed", e); }
        finally {
            queues.computeIfPresent(server, (key, queue) -> { queue.remove(result); return queue.isEmpty() ? null : queue; });
        }
    }
    public static void drain(Object server) {
        ConcurrentLinkedQueue<FutureTask<Void>> queue = queues.get(server);
        if (queue == null) return;
        FutureTask<Void> task; while ((task = queue.poll()) != null) task.run();
        queues.computeIfPresent(server, (key, current) -> current.isEmpty() ? null : current);
    }
}
