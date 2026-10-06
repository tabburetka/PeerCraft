package net.peercraft.network.relay;

import java.io.IOException;
import java.util.concurrent.locks.LockSupport;

/** Only file protocols consume this budget; Minecraft and route control retain priority. */
final class BulkPacer {
    private long nextNanos;
    private volatile int bytesPerSecond = 2 * 1024 * 1024;
    void setRate(int rate) {
        if (rate < 65536 || rate > 2 * 1024 * 1024) throw new IllegalArgumentException("Invalid file transfer rate");
        bytesPerSecond = rate;
    }
    void await(byte[] bytes) throws IOException {
        if (bytes.length == 0 || (bytes[0] != (byte) 0xE2 && bytes[0] != (byte) 0xE4)) return;
        long now = System.nanoTime();
        long target;
        synchronized (this) {
            target = Math.max(now, nextNanos);
            nextNanos = target + bytes.length * 1_000_000_000L / bytesPerSecond;
        }
        while ((now = System.nanoTime()) < target) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("transfer_cancelled");
            LockSupport.parkNanos(Math.min(target - now, 10_000_000L));
        }
    }
}
