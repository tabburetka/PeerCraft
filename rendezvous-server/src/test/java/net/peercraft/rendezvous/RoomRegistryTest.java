package net.peercraft.rendezvous;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RoomRegistryTest {

    private static RendezvousProtocol.Address addr(String ip, int port) throws UnknownHostException {
        return new RendezvousProtocol.Address(InetAddress.getByName(ip), port);
    }

    @Test
    void registerReturnsUniqueCodesForDifferentSources() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();

        RoomRegistry.Registered a = (RoomRegistry.Registered) registry.register(addr("10.0.0.1", 1000));
        RoomRegistry.Registered b = (RoomRegistry.Registered) registry.register(addr("10.0.0.2", 2000));

        assertNotEquals(a.code(), b.code());
    }

    @Test
    void registerIsIdempotentForSameUnclaimedSource() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);

        RoomRegistry.Registered first = (RoomRegistry.Registered) registry.register(host);
        RoomRegistry.Registered second = (RoomRegistry.Registered) registry.register(host);

        assertEquals(first.code(), second.code());
        assertEquals(1, registry.roomCount());
    }

    @Test
    void registerAfterClaimedStillReturnsSameCode() throws UnknownHostException {
        // A room's code must stay valid for as long as the host keeps hosting (repeated
        // REGISTER/keepalive), not just until the first successful match — otherwise the
        // host's keepalive would silently start minting brand new codes once claimed.
        RoomRegistry registry = new RoomRegistry();
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host);
        registry.join(registered.code(), addr("10.0.0.2", 2000));

        RoomRegistry.Registered afterMatch = (RoomRegistry.Registered) registry.register(host);

        assertEquals(registered.code(), afterMatch.code());
        assertEquals(1, registry.roomCount());
    }

    @Test
    void joinWithValidCodeMatchesAndIssuesToken() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        RendezvousProtocol.Address joiner = addr("10.0.0.2", 2000);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host);

        RoomRegistry.Matched matched = (RoomRegistry.Matched) registry.join(registered.code(), joiner);

        assertEquals(host, matched.hostAddress());
        assertEquals(joiner, matched.joinerAddress());
    }

    @Test
    void joinWithInvalidCodeIsRejected() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();

        RoomRegistry.JoinRejected rejected = (RoomRegistry.JoinRejected) registry.join("NOPE12", addr("10.0.0.2", 2000));

        assertEquals(RendezvousProtocol.REASON_INVALID_CODE, rejected.reason());
    }

    @Test
    void repeatedJoinFromSameJoinerWithinDebounceWindowIsIdempotent() throws UnknownHostException {
        // Absorbs the joiner's own ~500ms-interval UDP retries of a single connection
        // attempt — they must all resolve to the same token, not each mint a new one.
        RoomRegistry registry = new RoomRegistry();
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        RendezvousProtocol.Address joiner = addr("10.0.0.2", 2000);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host);

        RoomRegistry.Matched first = (RoomRegistry.Matched) registry.join(registered.code(), joiner);
        RoomRegistry.Matched second = (RoomRegistry.Matched) registry.join(registered.code(), joiner);

        assertEquals(first.token(), second.token());
    }

    @Test
    void rejoinWithSameCodeAfterDebounceWindowSucceedsWithFreshToken() throws UnknownHostException {
        // The actual bug fix: a joiner disconnecting and reconnecting later with the same
        // room code must succeed again, not be rejected as ALREADY_CLAIMED/INVALID_CODE.
        AtomicLong clock = new AtomicLong(0);
        RoomRegistry registry = new RoomRegistry(clock::get);
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        RendezvousProtocol.Address joiner = addr("10.0.0.2", 2000);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host);

        RoomRegistry.Matched first = (RoomRegistry.Matched) registry.join(registered.code(), joiner);

        clock.set(RoomRegistry.REMATCH_DEBOUNCE_MILLIS + 1);
        // Host keeps the room alive via its own keepalive in the meantime.
        registry.register(host);

        RoomRegistry.Matched second = (RoomRegistry.Matched) registry.join(registered.code(), joiner);

        assertNotEquals(first.token(), second.token());
    }

    @Test
    void joinFromDifferentJoinerAfterClaimedReMatchesWithNewToken() throws UnknownHostException {
        // A room code behaves like a reusable address for the hosted world, not a
        // single-claim pairing token — anyone who has it can (re)join while it's alive.
        RoomRegistry registry = new RoomRegistry();
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host);
        RoomRegistry.Matched first = (RoomRegistry.Matched) registry.join(registered.code(), addr("10.0.0.2", 2000));

        RoomRegistry.Matched second = (RoomRegistry.Matched) registry.join(registered.code(), addr("10.0.0.3", 3000));

        assertNotEquals(first.token(), second.token());
        assertEquals(addr("10.0.0.3", 3000), second.joinerAddress());
    }

    @Test
    void registerRateLimitTriggersServerBusy() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        InetAddress ip = InetAddress.getByName("10.0.0.9");

        for (int i = 0; i < 5; i++) {
            RoomRegistry.RegisterResult result = registry.register(new RendezvousProtocol.Address(ip, 1000 + i));
            assertInstanceOf(RoomRegistry.Registered.class, result);
        }

        RoomRegistry.RegisterRejected sixth = (RoomRegistry.RegisterRejected) registry.register(new RendezvousProtocol.Address(ip, 2000));
        assertEquals(RendezvousProtocol.REASON_SERVER_BUSY, sixth.reason());
    }

    @Test
    void sweepDoesNotRemoveFreshRoom() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        registry.register(addr("10.0.0.1", 1000));

        registry.sweepExpired();

        assertEquals(1, registry.roomCount());
    }

    @Test
    void sweepRemovesRoomOnceHostStopsRefreshingIt() throws UnknownHostException {
        AtomicLong clock = new AtomicLong(0);
        RoomRegistry registry = new RoomRegistry(clock::get);
        registry.register(addr("10.0.0.1", 1000));

        clock.set(RoomRegistry.ROOM_TTL_MILLIS + 1);
        registry.sweepExpired();

        assertEquals(0, registry.roomCount());
    }

    @Test
    void keepaliveRegisterKeepsClaimedRoomAliveBeyondWhatWouldOtherwiseExpireIt() throws UnknownHostException {
        AtomicLong clock = new AtomicLong(0);
        RoomRegistry registry = new RoomRegistry(clock::get);
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host);
        registry.join(registered.code(), addr("10.0.0.2", 2000));

        // Host's keepalive refreshes the room well before it would expire.
        clock.set(RoomRegistry.ROOM_TTL_MILLIS - 1000);
        registry.register(host);

        clock.set(RoomRegistry.ROOM_TTL_MILLIS + 500);
        registry.sweepExpired();

        assertEquals(1, registry.roomCount(), "keepalive should have kept the room from expiring");
    }

    @Test
    void joinAfterRoomExpiredIsRejected() throws UnknownHostException {
        AtomicLong clock = new AtomicLong(0);
        RoomRegistry registry = new RoomRegistry(clock::get);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(addr("10.0.0.1", 1000));

        clock.set(RoomRegistry.ROOM_TTL_MILLIS + 1);
        RoomRegistry.JoinRejected rejected = (RoomRegistry.JoinRejected) registry.join(registered.code(), addr("10.0.0.2", 2000));

        assertEquals(RendezvousProtocol.REASON_EXPIRED, rejected.reason());
    }
}
