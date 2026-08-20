package net.peercraft.rendezvous;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Generic sliding-window rate limiter keyed by an arbitrary key (source IP, session
 * token, ...). Extracted from {@link RoomRegistry}'s original REGISTER-by-IP limiter so
 * account login/search/friend-request endpoints can reuse the same sliding-window logic
 * instead of re-implementing it.
 */
public final class RateLimiter<K> {

    private final int limit;
    private final long windowMillis;
    private final LongSupplier clock;
    private final Map<K, Deque<Long>> timestampsByKey = new ConcurrentHashMap<>();

    public RateLimiter(int limit, long windowMillis, LongSupplier clock) {
        this.limit = limit;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /** @return true if this call is within the allowed rate and should proceed. */
    public boolean allow(K key) {
        Deque<Long> timestamps = timestampsByKey.computeIfAbsent(key, k -> new ArrayDeque<>());
        long now = clock.getAsLong();
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMillis) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= limit) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }
}
