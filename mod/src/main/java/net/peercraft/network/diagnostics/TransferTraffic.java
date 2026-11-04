package net.peercraft.network.diagnostics;

/** Shared aggregate budget for file payloads; game and control traffic never wait for it. */
public final class TransferTraffic {
    private TransferTraffic() { }
    private static volatile int limitKiB;
    private static long nextSlot;
    public static int limitKiB() { return limitKiB; }
    public static synchronized void setLimitKiB(int value) { limitKiB = Math.max(0, Math.min(16384, value)); nextSlot = 0; }
    public static void acquire(int bytes) throws InterruptedException {
        long wait;
        synchronized (TransferTraffic.class) {
            int limit = limitKiB;
            if (limit == 0) return;
            long now = System.nanoTime();
            long slot = Math.max(now, nextSlot);
            nextSlot = slot + Math.max(0, bytes) * 1_000_000_000L / (limit * 1024L);
            wait = slot - now;
        }
        if (wait > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);
    }
}
