package net.peercraft.rendezvous.account;

import net.peercraft.rendezvous.AccountProtocol;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PresenceRegistryTest {

    @Test
    void unknownAccountIsOffline() {
        PresenceRegistry registry = new PresenceRegistry();
        assertEquals(AccountProtocol.STATUS_OFFLINE, registry.statusOf(UUID.randomUUID()).statusCode());
    }

    @Test
    void heartbeatMakesAccountOnline() {
        PresenceRegistry registry = new PresenceRegistry();
        UUID accountId = UUID.randomUUID();

        registry.heartbeat(accountId);

        assertEquals(AccountProtocol.STATUS_ONLINE, registry.statusOf(accountId).statusCode());
    }

    @Test
    void onlineExpiresToOfflineAfterPresenceTtlWithoutHeartbeat() {
        AtomicLong clock = new AtomicLong(0);
        PresenceRegistry registry = new PresenceRegistry(clock::get);
        UUID accountId = UUID.randomUUID();
        registry.heartbeat(accountId);

        clock.set(PresenceRegistry.PRESENCE_TTL_MILLIS + 1);

        assertEquals(AccountProtocol.STATUS_OFFLINE, registry.statusOf(accountId).statusCode());
    }

    @Test
    void setHostingReportsHostingWithRoomCode() {
        PresenceRegistry registry = new PresenceRegistry();
        UUID accountId = UUID.randomUUID();

        registry.setHosting(accountId, "ABCDEF");

        PresenceRegistry.Status status = registry.statusOf(accountId);
        assertEquals(AccountProtocol.STATUS_HOSTING, status.statusCode());
        assertEquals("ABCDEF", status.roomCode());
    }

    @Test
    void hostingDecaysToOnlineAfterHostTtlEvenIfGenericHeartbeatKeepsComing() {
        // The whole point of tracking two separate clocks: a host who closes their world but
        // keeps playing (so generic heartbeats keep arriving) must stop showing as "hosting"
        // to friends within one host-keepalive-TTL window, not linger forever just because
        // lastSeenAt keeps getting refreshed by unrelated heartbeats.
        AtomicLong clock = new AtomicLong(0);
        PresenceRegistry registry = new PresenceRegistry(clock::get);
        UUID accountId = UUID.randomUUID();
        registry.setHosting(accountId, "ABCDEF");

        clock.set(PresenceRegistry.HOST_TTL_MILLIS + 1);
        registry.heartbeat(accountId); // generic "still online" heartbeat, NOT a room REGISTER

        PresenceRegistry.Status status = registry.statusOf(accountId);
        assertEquals(AccountProtocol.STATUS_ONLINE, status.statusCode());
    }

    @Test
    void repeatedSetHostingWithinHostTtlKeepsHostingStatusFresh() {
        AtomicLong clock = new AtomicLong(0);
        PresenceRegistry registry = new PresenceRegistry(clock::get);
        UUID accountId = UUID.randomUUID();
        registry.setHosting(accountId, "ABCDEF");

        clock.set(PresenceRegistry.HOST_TTL_MILLIS - 1);
        registry.setHosting(accountId, "ABCDEF"); // simulates the 15s REGISTER keepalive refreshing it

        clock.set(PresenceRegistry.HOST_TTL_MILLIS - 1 + PresenceRegistry.HOST_TTL_MILLIS - 1);
        assertEquals(AccountProtocol.STATUS_HOSTING, registry.statusOf(accountId).statusCode());
    }

    @Test
    void stopImmediatelyMakesAccountOffline() {
        PresenceRegistry registry = new PresenceRegistry();
        UUID accountId = UUID.randomUUID();
        registry.setHosting(accountId, "ABCDEF");

        registry.stop(accountId);

        assertEquals(AccountProtocol.STATUS_OFFLINE, registry.statusOf(accountId).statusCode());
    }

    @Test
    void sweepExpiredRemovesOnlyStaleEntries() {
        AtomicLong clock = new AtomicLong(0);
        PresenceRegistry registry = new PresenceRegistry(clock::get);
        UUID stale = UUID.randomUUID();
        registry.heartbeat(stale);

        clock.set(PresenceRegistry.PRESENCE_TTL_MILLIS + 1);
        UUID fresh = UUID.randomUUID();
        registry.heartbeat(fresh);

        registry.sweepExpired();

        assertEquals(AccountProtocol.STATUS_OFFLINE, registry.statusOf(stale).statusCode());
        assertEquals(AccountProtocol.STATUS_ONLINE, registry.statusOf(fresh).statusCode());
    }
}
