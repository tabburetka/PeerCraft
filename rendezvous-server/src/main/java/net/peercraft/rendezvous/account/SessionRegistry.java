package net.peercraft.rendezvous.account;

import net.peercraft.rendezvous.AccountProtocol;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Live session-token bookkeeping — issued on successful login, required on every
 * friends/presence/rename request thereafter. Not persisted (sessions are meant to be
 * short-lived, unlike accounts themselves — a restarted server simply makes every client
 * silently relogin via {@code TYPE_ACCOUNT_LOGIN_REMEMBER}). Sliding TTL: any validated use
 * refreshes it, same idea as a room's keepalive in {@code RoomRegistry}.
 *
 * Keyed by the token's Base64 encoding rather than the raw {@code byte[]} — a {@code byte[]}
 * used directly as a Map key compares by reference, not content, which would make every
 * lookup miss.
 */
final class SessionRegistry {

    static final long SESSION_TTL_MILLIS = 30 * 60_000L;

    private static final class Entry {
        final UUID accountId;
        volatile long lastUsedAt;

        Entry(UUID accountId, long lastUsedAt) {
            this.accountId = accountId;
            this.lastUsedAt = lastUsedAt;
        }
    }

    private final Map<String, Entry> byToken = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    SessionRegistry() {
        this(System::currentTimeMillis);
    }

    /** Package-private seam so tests can control TTL expiry deterministically. */
    SessionRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    byte[] issue(UUID accountId) {
        byte[] token = randomToken();
        byToken.put(key(token), new Entry(accountId, clock.getAsLong()));
        return token;
    }

    Optional<UUID> validate(byte[] token) {
        Entry entry = byToken.get(key(token));
        if (entry == null) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        if (now - entry.lastUsedAt > SESSION_TTL_MILLIS) {
            byToken.remove(key(token));
            return Optional.empty();
        }
        entry.lastUsedAt = now;
        return Optional.of(entry.accountId);
    }

    void invalidate(byte[] token) {
        byToken.remove(key(token));
    }

    /** Called periodically to bound memory — not on the request path. */
    void sweepExpired() {
        long now = clock.getAsLong();
        byToken.values().removeIf(entry -> now - entry.lastUsedAt > SESSION_TTL_MILLIS);
    }

    int activeSessionCount() {
        return byToken.size();
    }

    private static String key(byte[] token) {
        return Base64.getEncoder().encodeToString(token);
    }

    private static byte[] randomToken() {
        byte[] token = new byte[AccountProtocol.TOKEN_LENGTH];
        new SecureRandom().nextBytes(token);
        return token;
    }
}
