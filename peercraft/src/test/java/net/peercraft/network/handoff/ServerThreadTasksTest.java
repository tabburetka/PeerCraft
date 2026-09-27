package net.peercraft.network.handoff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.IOException;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class ServerThreadTasksTest {
    @Test @Timeout(5)
    void executesOnTickThreadAndPropagatesSaveFailure() throws Exception {
        Object server = new Object();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Thread> result = worker.submit(() -> {
                java.util.concurrent.atomic.AtomicReference<Thread> actual = new java.util.concurrent.atomic.AtomicReference<>();
                ServerThreadTasks.execute(server, () -> actual.set(Thread.currentThread())); return actual.get();
            });
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!result.isDone() && System.nanoTime() < end) { ServerThreadTasks.drain(server); Thread.sleep(1); }
            assertSame(Thread.currentThread(), result.get(1, TimeUnit.SECONDS));
            Future<?> failed = worker.submit(() -> assertThrows(IOException.class,
                    () -> ServerThreadTasks.execute(server, () -> { throw new IllegalStateException("save failed"); })));
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!failed.isDone() && System.nanoTime() < end) { ServerThreadTasks.drain(server); Thread.sleep(1); }
            failed.get(1, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }
}
