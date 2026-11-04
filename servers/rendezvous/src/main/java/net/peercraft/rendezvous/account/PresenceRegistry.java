package net.peercraft.rendezvous.account;

import net.peercraft.rendezvous.AccountProtocol;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Live "is this account online / hosting a room" bookkeeping — not persisted (like
 * {@link SessionRegistry}, presence is meant to be short-lived; a restarted server just
 * starts everyone as offline until their next heartbeat).
 *
 * Online-ness and hosting-ness are tracked with two SEPARATE staleness clocks on purpose:
 * {@code lastSeenAt} is refreshed by the generic presence heartbeat (sent by every logged-in
 * client, hosting or not — see AccountClient's plan), while {@code lastHostRegisterAt} is
 * refreshed only by the host's own room REGISTER keepalive (every ~15s, see RendezvousClient).
 * A single shared timestamp would be wrong: a host who closes their world but keeps playing
 * would still be sending generic heartbeats, which would keep "hosting" looking fresh forever
 * even though REGISTER stopped — using two clocks lets HOSTING correctly decay to ONLINE
 * (not OFFLINE) within one host-keepalive-TTL window of them actually stopping.
 */
final class PresenceRegistry {

    static final long PRESENCE_TTL_MILLIS = 45_000L;
    // ~2x RendezvousClient's 15s host REGISTER keepalive interval — tolerates one missed tick.
    static final long HOST_TTL_MILLIS = 30_000L;

    record Status(int statusCode, String roomCode) {
    }

    private static final Status OFFLINE = new Status(AccountProtocol.STATUS_OFFLINE, "");

    private static final class Entry {
        volatile long lastSeenAt;
        volatile long lastHostRegisterAt;
        volatile String hostingRoomCode;

        Entry(long now) {
            this.lastSeenAt = now;
        }
    }

    private final Map<UUID, Entry> byAccount = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    PresenceRegistry() {
        this(System::currentTimeMillis);
    }

    PresenceRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    void heartbeat(UUID accountId) {
        long now = clock.getAsLong();
        byAccount.compute(accountId, (id, existing) -> {
            if (existing == null) {
                return new Entry(now);
            }
            existing.lastSeenAt = now;
            return existing;
        });
    }

    void setHosting(UUID accountId, String roomCode) {
        long now = clock.getAsLong();
        Entry entry = byAccount.computeIfAbsent(accountId, id -> new Entry(now));
        entry.lastSeenAt = now;
        entry.lastHostRegisterAt = now;
        entry.hostingRoomCode = roomCode;
    }

    void stop(UUID accountId) {
        byAccount.remove(accountId);
    }

    Status statusOf(UUID accountId) {
        Entry entry = byAccount.get(accountId);
        if (entry == null) {
            return OFFLINE;
        }
        long now = clock.getAsLong();
        if (now - entry.lastSeenAt > PRESENCE_TTL_MILLIS) {
            byAccount.remove(accountId, entry);
            return OFFLINE;
        }
        if (entry.hostingRoomCode != null && now - entry.lastHostRegisterAt <= HOST_TTL_MILLIS) {
            return new Status(AccountProtocol.STATUS_HOSTING, entry.hostingRoomCode);
        }
        return new Status(AccountProtocol.STATUS_ONLINE, "");
    }

    /** Called periodically to bound memory — not on the request path. */
    void sweepExpired() {
        long now = clock.getAsLong();
        byAccount.values().removeIf(entry -> now - entry.lastSeenAt > PRESENCE_TTL_MILLIS);
    }
}
