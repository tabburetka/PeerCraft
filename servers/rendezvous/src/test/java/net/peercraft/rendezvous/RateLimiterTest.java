package net.peercraft.rendezvous;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    @Test
    void allowsCallsUpToTheLimitWithinTheWindow() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter<String> limiter = new RateLimiter<>(3, 60_000L, clock::get);

        assertTrue(limiter.allow("a"));
        assertTrue(limiter.allow("a"));
        assertTrue(limiter.allow("a"));
        assertFalse(limiter.allow("a"));
    }

    @Test
    void tracksEachKeyIndependently() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter<String> limiter = new RateLimiter<>(1, 60_000L, clock::get);

        assertTrue(limiter.allow("a"));
        assertFalse(limiter.allow("a"));
        assertTrue(limiter.allow("b"));
    }

    @Test
    void expiredTimestampsFallOutOfTheWindowAndFreeUpCapacityAgain() {
        AtomicLong clock = new AtomicLong(0);
        RateLimiter<String> limiter = new RateLimiter<>(1, 60_000L, clock::get);

        assertTrue(limiter.allow("a"));
        assertFalse(limiter.allow("a"));

        clock.set(60_001L);

        assertTrue(limiter.allow("a"));
    }
}
