package net.peercraft.rendezvous;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RoomHostingSessionTest {
    private static RendezvousProtocol.Address addr(String ip, int port) throws Exception {
        return new RendezvousProtocol.Address(InetAddress.getByName(ip), port);
    }

    private static RendezvousProtocol.ConnectivityAdvertisement session() {
        return new RendezvousProtocol.ConnectivityAdvertisement(false, false, List.of());
    }

    private static RoomRegistry.Registered register(RoomRegistry registry, RendezvousProtocol.Address host,
                                                     Optional<UUID> account,
                                                     RendezvousProtocol.ConnectivityAdvertisement identity) {
        return (RoomRegistry.Registered) registry.register(host, 4, 0, account, false,
                true, "World", "26.2", Optional.of(identity));
    }

    @Test
    void repeatedPortChangesReuseTheRoomWithoutConsumingNewRegistrationQuota() throws Exception {
        RoomRegistry registry = new RoomRegistry();
        var identity = session();
        String code = register(registry, addr("10.0.0.1", 1000), Optional.empty(), identity).code();
        for (int port = 1001; port <= 1010; port++) {
            var refresh = register(registry, addr("10.0.0.1", port), Optional.empty(), identity);
            assertEquals(code, refresh.code());
            assertTrue(refresh.reused());
        }
        assertEquals(1, registry.roomCount());
        assertEquals(1, registry.listPublicRooms().size());
    }

    @Test
    void sameSessionIdFromADifferentIpCannotMoveAnExistingRoom() throws Exception {
        RoomRegistry registry = new RoomRegistry();
        var identity = session();
        var first = register(registry, addr("10.0.0.1", 1000), Optional.empty(), identity);
        var other = register(registry, addr("10.0.0.2", 2000), Optional.empty(), identity);
        assertNotEquals(first.code(), other.code());
        assertTrue(registry.ownsRoom(first.code(), addr("10.0.0.1", 1000)));
    }

    @Test
    void sameSessionIdWithADifferentVerifiedAccountCannotMoveAnExistingRoom() throws Exception {
        RoomRegistry registry = new RoomRegistry();
        var identity = session();
        var first = register(registry, addr("10.0.0.1", 1000), Optional.of(UUID.randomUUID()), identity);
        var other = register(registry, addr("10.0.0.1", 2000), Optional.of(UUID.randomUUID()), identity);
        assertNotEquals(first.code(), other.code());
        assertTrue(registry.ownsRoom(first.code(), addr("10.0.0.1", 1000)));
    }

    @Test
    void rebindingUpdatesOwnershipAndInvalidatesCachedMatchesWithoutLosingKnownJoinerCapacity() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        RoomRegistry registry = new RoomRegistry(clock::get);
        var oldHost = addr("10.0.0.1", 1000);
        var newHost = addr("10.0.0.1", 2000);
        var joiner = addr("10.0.0.2", 3000);
        var identity = session();
        var joinIdentity = session();
        String code = register(registry, oldHost, Optional.empty(), identity).code();
        byte[] handoffProof = registry.handoffChallenge(code, oldHost);
        var firstMatch = (RoomRegistry.Matched) registry.join(code, joiner, Optional.empty(), (a, b) -> false,
                joinIdentity.clientAttemptId());
        var firstOffers = registry.networkOffers(code, firstMatch, Optional.empty(), Optional.of(joinIdentity), "").orElseThrow();

        registry.register(newHost, 1, 1, Optional.empty(), false, true, "World", "26.2", Optional.of(identity));
        assertFalse(registry.ownsRoom(code, oldHost));
        assertFalse(registry.authorizesHandoff(code, oldHost, handoffProof));
        assertTrue(registry.authorizesHandoff(code, newHost, handoffProof));
        var nextMatch = (RoomRegistry.Matched) registry.join(code, joiner, Optional.empty(), (a, b) -> false,
                joinIdentity.clientAttemptId());
        assertEquals(newHost, nextMatch.hostAddress());
        assertNotEquals(firstMatch.token(), nextMatch.token());
        var nextOffers = registry.networkOffers(code, nextMatch, Optional.empty(), Optional.of(joinIdentity), "").orElseThrow();
        assertNotEquals(firstOffers.host().attemptId(), nextOffers.host().attemptId());
        assertInstanceOf(RoomRegistry.JoinRejected.class,
                registry.join(code, addr("10.0.0.3", 4000), Optional.empty(), (a, b) -> false));
    }

    @Test
    void joinsCannotKeepAStoppedHostVisibleInThePublicBrowser() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        RoomRegistry registry = new RoomRegistry(clock::get);
        var host = addr("10.0.0.1", 1000);
        var identity = session();
        String code = register(registry, host, Optional.empty(), identity).code();
        clock.set(RoomRegistry.PUBLIC_LISTING_STALE_MILLIS - 1);
        registry.join(code, addr("10.0.0.2", 2000), Optional.empty(), (a, b) -> false);
        clock.set(RoomRegistry.PUBLIC_LISTING_STALE_MILLIS + 1);
        assertTrue(registry.listPublicRooms().isEmpty());
        registry.join(code, addr("10.0.0.3", 3000), Optional.empty(), (a, b) -> false);
        assertTrue(registry.listPublicRooms().isEmpty());
        register(registry, host, Optional.empty(), identity);
        assertEquals(1, registry.listPublicRooms().size());
    }

    @Test
    void anExpiredHostSessionDoesNotReclaimItsRoomAtANewPortEvenIfJoinersTouchedIt() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        RoomRegistry registry = new RoomRegistry(clock::get);
        var identity = session();
        String code = register(registry, addr("10.0.0.1", 1000), Optional.empty(), identity).code();
        clock.set(RoomRegistry.ROOM_TTL_MILLIS - 1);
        registry.join(code, addr("10.0.0.2", 3000), Optional.empty(), (a, b) -> false);
        clock.set(RoomRegistry.ROOM_TTL_MILLIS + 1);
        var next = register(registry, addr("10.0.0.1", 2000), Optional.empty(), identity);
        assertNotEquals(code, next.code());
        assertFalse(next.reused());
        assertEquals(1, registry.listPublicRooms().size());
    }
}
