package net.peercraft.network.handoff;
import java.util.concurrent.TimeUnit;
/** Only authenticated control traffic may call touch; independent of Minecraft ticks. */
public final class HandoffLiveness {
    private volatile long lastSeen = System.nanoTime();
    private final long timeout;
    public HandoffLiveness(long timeoutMillis) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Invalid liveness timeout");
        timeout = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }
    public void touch() { lastSeen = System.nanoTime(); }
    public boolean alive() { return System.nanoTime() - lastSeen < timeout; }
}
