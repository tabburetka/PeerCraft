package net.peercraft.rendezvous.account;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SessionRegistryTest {

    @Test
    void issuedTokenValidatesToTheIssuingAccount() {
        SessionRegistry registry = new SessionRegistry();
        UUID accountId = UUID.randomUUID();

        byte[] token = registry.issue(accountId);

        assertEquals(Optional.of(accountId), registry.validate(token));
    }

    @Test
    void unknownTokenDoesNotValidate() {
        SessionRegistry registry = new SessionRegistry();

        assertTrue(registry.validate(new byte[16]).isEmpty());
    }

    @Test
    void twoIssuedTokensAreDistinctAndBothValidateIndependently() {
        SessionRegistry registry = new SessionRegistry();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        byte[] tokenA = registry.issue(a);
        byte[] tokenB = registry.issue(b);

        assertFalse(java.util.Arrays.equals(tokenA, tokenB));
        assertEquals(Optional.of(a), registry.validate(tokenA));
        assertEquals(Optional.of(b), registry.validate(tokenB));
    }

    @Test
    void tokenExpiresAfterTtlOfInactivity() {
        AtomicLong clock = new AtomicLong(0);
        SessionRegistry registry = new SessionRegistry(clock::get);
        UUID accountId = UUID.randomUUID();
        byte[] token = registry.issue(accountId);

        clock.set(SessionRegistry.SESSION_TTL_MILLIS + 1);

        assertTrue(registry.validate(token).isEmpty());
    }

    @Test
    void validatingBeforeExpiryExtendsTheTtlSlidingWindow() {
        // Mirrors the room keepalive pattern — using a session must keep it alive, not just
        // issuing it once.
        AtomicLong clock = new AtomicLong(0);
        SessionRegistry registry = new SessionRegistry(clock::get);
        UUID accountId = UUID.randomUUID();
        byte[] token = registry.issue(accountId);

        clock.set(SessionRegistry.SESSION_TTL_MILLIS - 1);
        assertTrue(registry.validate(token).isPresent()); // still valid, and refreshes lastUsedAt

        clock.set(SessionRegistry.SESSION_TTL_MILLIS - 1 + SessionRegistry.SESSION_TTL_MILLIS - 1);
        assertTrue(registry.validate(token).isPresent()); // would have expired if the first validate hadn't refreshed it
    }

    @Test
    void invalidateRemovesTheTokenImmediately() {
        SessionRegistry registry = new SessionRegistry();
        UUID accountId = UUID.randomUUID();
        byte[] token = registry.issue(accountId);

        registry.invalidate(token);

        assertTrue(registry.validate(token).isEmpty());
    }

    @Test
    void sweepExpiredRemovesOnlyStaleEntries() {
        AtomicLong clock = new AtomicLong(0);
        SessionRegistry registry = new SessionRegistry(clock::get);
        byte[] staleToken = registry.issue(UUID.randomUUID());

        clock.set(SessionRegistry.SESSION_TTL_MILLIS + 1);
        byte[] freshToken = registry.issue(UUID.randomUUID());

        registry.sweepExpired();

        assertTrue(registry.validate(staleToken).isEmpty());
        assertTrue(registry.validate(freshToken).isPresent());
    }
}
