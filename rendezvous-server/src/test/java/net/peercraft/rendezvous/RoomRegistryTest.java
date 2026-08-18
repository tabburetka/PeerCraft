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
    void repeatedJoinFromSameJoinerIsIdempotent() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        RendezvousProtocol.Address joiner = addr("10.0.0.2", 2000);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host);

        RoomRegistry.Matched first = (RoomRegistry.Matched) registry.join(registered.code(), joiner);
        RoomRegistry.Matched second = (RoomRegistry.Matched) registry.join(registered.code(), joiner);

        assertEquals(first.token(), second.token());
    }

    @Test
    void joinFromDifferentJoinerAfterClaimedIsRejected() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host);
        registry.join(registered.code(), addr("10.0.0.2", 2000));

        RoomRegistry.JoinRejected rejected = (RoomRegistry.JoinRejected) registry.join(registered.code(), addr("10.0.0.3", 3000));

        assertEquals(RendezvousProtocol.REASON_ALREADY_CLAIMED, rejected.reason());
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
    void sweepRemovesExpiredUnclaimedRoom() throws UnknownHostException {
        AtomicLong clock = new AtomicLong(0);
        RoomRegistry registry = new RoomRegistry(clock::get);
        registry.register(addr("10.0.0.1", 1000));

        clock.set(RoomRegistry.UNCLAIMED_TTL_MILLIS + 1);
        registry.sweepExpired();

        assertEquals(0, registry.roomCount());
    }

    @Test
    void sweepRemovesExpiredClaimedRoomAfterLongerGracePeriod() throws UnknownHostException {
        AtomicLong clock = new AtomicLong(0);
        RoomRegistry registry = new RoomRegistry(clock::get);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(addr("10.0.0.1", 1000));
        registry.join(registered.code(), addr("10.0.0.2", 2000));

        // Claimed room should still answer a slightly-late retry within its own TTL.
        clock.set(RoomRegistry.CLAIMED_TTL_MILLIS - 1);
        assertInstanceOf(RoomRegistry.Matched.class, registry.join(registered.code(), addr("10.0.0.2", 2000)));

        clock.set(RoomRegistry.CLAIMED_TTL_MILLIS + 1);
        registry.sweepExpired();

        assertEquals(0, registry.roomCount());
    }
}
