package net.peercraft.network.handoff;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HandoffLaunchGuardTest {
    @Test void queuedPublicationAfterFailureDoesNotRun() {
        HandoffLaunchGuard attempt = new HandoffLaunchGuard();
        AtomicInteger publications = new AtomicInteger();
        Runnable queued = () -> { if (attempt.active()) publications.incrementAndGet(); };
        assertTrue(attempt.finish());
        queued.run();
        assertEquals(0, publications.get());
    }

    @Test void ConcurrentFailureAndRegistrationHaveOneTerminalCallback() throws Exception {
        HandoffLaunchGuard attempt = new HandoffLaunchGuard();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        Callable<Void> complete = () -> {
            start.await();
            if (attempt.finish()) callbacks.incrementAndGet();
            return null;
        };
        try {
            Future<Void> failure = workers.submit(complete);
            Future<Void> registration = workers.submit(complete);
            start.countDown();
            failure.get(5, TimeUnit.SECONDS);
            registration.get(5, TimeUnit.SECONDS);
            assertEquals(1, callbacks.get());
            assertFalse(attempt.active());
        } finally { workers.shutdownNow(); }
    }
}
