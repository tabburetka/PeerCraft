package net.peercraft.network.relay;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BulkPacerTest {
    @Test void queuedFileTransferNeverHoldsUpGamePackets() throws Exception {
        BulkPacer pacer=new BulkPacer(); pacer.setRate(65536);
        byte[] file=new byte[131072]; file[0]=(byte)0xe4; pacer.await(file);
        ExecutorService io=Executors.newFixedThreadPool(2); CountDownLatch entered=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> worker=new java.util.concurrent.atomic.AtomicReference<>();
        try {
            Future<?> bulk=io.submit(() -> { worker.set(Thread.currentThread()); entered.countDown(); try { pacer.await(file); } catch (java.io.IOException interrupted) { } });
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(worker.get().getState()!=Thread.State.TIMED_WAITING && System.nanoTime()<deadline) Thread.yield();
            assertEquals(Thread.State.TIMED_WAITING,worker.get().getState());
            Future<?> game=io.submit(() -> { try { pacer.await(new byte[]{1,2,3}); } catch (java.io.IOException e) { throw new RuntimeException(e); } });
            game.get(500,TimeUnit.MILLISECONDS);
            assertFalse(bulk.isDone(),"The bulk transfer should still be rate-limited");
        } finally { io.shutdownNow(); assertTrue(io.awaitTermination(2,TimeUnit.SECONDS)); }
    }
}
