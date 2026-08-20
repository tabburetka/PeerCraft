package net.peercraft.rendezvous.account;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Short-lived state for the two multi-step handshakes (licensed Mojang verification, and
 * password-challenge login) — keyed by a random {@code requestId} the server hands out at the
 * start of each attempt and the client echoes back on the final step. Entries are consumed
 * (removed) on first successful completion — a lost reply means the client must restart the
 * whole handshake from BEGIN with a fresh requestId rather than retry the final step, which is
 * an accepted simplification (see Фаза 1 plan notes).
 */
final class PendingAuthRegistry {

    static final long ATTEMPT_TTL_MILLIS = 30_000L;

    sealed interface PendingAttempt permits LicensedAttempt, PasswordLoginAttempt {
        long createdAt();
    }

    record LicensedAttempt(String username, String serverId, long createdAt) implements PendingAttempt {
    }

    record PasswordLoginAttempt(UUID accountId, byte[] challenge, long createdAt) implements PendingAttempt {
    }

    private final Map<Long, PendingAttempt> attempts = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    PendingAuthRegistry() {
        this(System::currentTimeMillis);
    }

    PendingAuthRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    long beginLicensed(String username, String serverId) {
        long requestId = randomRequestId();
        attempts.put(requestId, new LicensedAttempt(username, serverId, clock.getAsLong()));
        return requestId;
    }

    long beginPasswordLogin(UUID accountId, byte[] challenge) {
        long requestId = randomRequestId();
        attempts.put(requestId, new PasswordLoginAttempt(accountId, challenge, clock.getAsLong()));
        return requestId;
    }

    Optional<LicensedAttempt> takeLicensed(long requestId) {
        PendingAttempt attempt = attempts.remove(requestId);
        if (attempt instanceof LicensedAttempt licensed && !expired(licensed.createdAt())) {
            return Optional.of(licensed);
        }
        return Optional.empty();
    }

    Optional<PasswordLoginAttempt> takePasswordLogin(long requestId) {
        PendingAttempt attempt = attempts.remove(requestId);
        if (attempt instanceof PasswordLoginAttempt login && !expired(login.createdAt())) {
            return Optional.of(login);
        }
        return Optional.empty();
    }

    /** Called periodically to bound memory for handshakes that were started but never finished. */
    void sweepExpired() {
        long now = clock.getAsLong();
        attempts.values().removeIf(attempt -> now - attempt.createdAt() > ATTEMPT_TTL_MILLIS);
    }

    private boolean expired(long createdAt) {
        return clock.getAsLong() - createdAt > ATTEMPT_TTL_MILLIS;
    }

    private static long randomRequestId() {
        return ThreadLocalRandom.current().nextLong();
    }
}
