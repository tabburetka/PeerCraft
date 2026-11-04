package net.peercraft.relay;

/** Byte budget per session: a one-second burst refilled at a steady rate. Not thread-safe. */
final class TokenBucket {
    private final long ratePerSecond;
    private double tokens;
    private long lastNanos;

    TokenBucket(long ratePerSecond, long nowNanos) {
        if (ratePerSecond <= 0) throw new IllegalArgumentException("Rate must be positive");
        this.ratePerSecond = ratePerSecond;
        this.tokens = ratePerSecond;
        this.lastNanos = nowNanos;
    }

    boolean take(int bytes, long nowNanos) {
        long elapsed = Math.max(0, nowNanos - lastNanos);
        lastNanos = nowNanos;
        tokens = Math.min(ratePerSecond, tokens + elapsed * (ratePerSecond / 1_000_000_000.0));
        if (tokens < bytes) return false;
        tokens -= bytes;
        return true;
    }
}
