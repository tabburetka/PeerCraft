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

        RoomRegistry.Registered a = (RoomRegistry.Registered) registry.register(addr("10.0.0.1", 1000), 4, 0, java.util.Optional.empty(), false);
        RoomRegistry.Registered b = (RoomRegistry.Registered) registry.register(addr("10.0.0.2", 2000), 4, 0, java.util.Optional.empty(), false);

        assertNotEquals(a.code(), b.code());
    }

    @Test
    void registerIsIdempotentForSameUnclaimedSource() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);

        RoomRegistry.Registered first = (RoomRegistry.Registered) registry.register(host, 4, 0, java.util.Optional.empty(), false);
        RoomRegistry.Registered second = (RoomRegistry.Registered) registry.register(host, 4, 0, java.util.Optional.empty(), false);

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
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host, 4, 0, java.util.Optional.empty(), false);
        registry.join(registered.code(), addr("10.0.0.2", 2000), java.util.Optional.empty(), (a, b) -> false);

        RoomRegistry.Registered afterMatch = (RoomRegistry.Registered) registry.register(host, 4, 1, java.util.Optional.empty(), false);

        assertEquals(registered.code(), afterMatch.code());
        assertEquals(1, registry.roomCount());
    }

    @Test
    void joinWithValidCodeMatchesAndIssuesToken() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        RendezvousProtocol.Address joiner = addr("10.0.0.2", 2000);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host, 4, 0, java.util.Optional.empty(), false);

        RoomRegistry.Matched matched = (RoomRegistry.Matched) registry.join(registered.code(), joiner, java.util.Optional.empty(), (a, b) -> false);

        assertEquals(host, matched.hostAddress());
        assertEquals(joiner, matched.joinerAddress());
    }

    @Test
    void joinWithInvalidCodeIsRejected() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();

        RoomRegistry.JoinRejected rejected = (RoomRegistry.JoinRejected) registry.join("NOPE12", addr("10.0.0.2", 2000), java.util.Optional.empty(), (a, b) -> false);

        assertEquals(RendezvousProtocol.REASON_INVALID_CODE, rejected.reason());
    }

    @Test
    void repeatedJoinFromSameJoinerWithinDebounceWindowIsIdempotent() throws UnknownHostException {
        // Absorbs the joiner's own ~500ms-interval UDP retries of a single connection
        // attempt — they must all resolve to the same token, not each mint a new one.
        RoomRegistry registry = new RoomRegistry();
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        RendezvousProtocol.Address joiner = addr("10.0.0.2", 2000);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host, 4, 0, java.util.Optional.empty(), false);

        RoomRegistry.Matched first = (RoomRegistry.Matched) registry.join(registered.code(), joiner, java.util.Optional.empty(), (a, b) -> false);
        RoomRegistry.Matched second = (RoomRegistry.Matched) registry.join(registered.code(), joiner, java.util.Optional.empty(), (a, b) -> false);

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
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host, 1, 0, java.util.Optional.empty(), false);

        RoomRegistry.Matched first = (RoomRegistry.Matched) registry.join(registered.code(), joiner, java.util.Optional.empty(), (a, b) -> false);

        clock.set(RoomRegistry.REMATCH_DEBOUNCE_MILLIS + 1);
        // Host keeps the room alive via its own keepalive in the meantime; still reports
        // the departed joiner as connected since it hasn't seen the disconnect yet (only
        // matters here in that it must NOT block the same joiner's own reconnect below).
        registry.register(host, 1, 1, java.util.Optional.empty(), false);

        RoomRegistry.Matched second = (RoomRegistry.Matched) registry.join(registered.code(), joiner, java.util.Optional.empty(), (a, b) -> false);

        assertNotEquals(first.token(), second.token());
    }

    @Test
    void joinFromDifferentJoinerAfterClaimedReMatchesWithNewTokenWhenCapacityAllows() throws UnknownHostException {
        // A room code behaves like a reusable address for the hosted world, not a
        // single-claim pairing token — anyone who has it can (re)join while it's alive, as
        // long as maxPlayers isn't exceeded.
        RoomRegistry registry = new RoomRegistry();
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host, 2, 0, java.util.Optional.empty(), false);
        RoomRegistry.Matched first = (RoomRegistry.Matched) registry.join(registered.code(), addr("10.0.0.2", 2000), java.util.Optional.empty(), (a, b) -> false);

        RoomRegistry.Matched second = (RoomRegistry.Matched) registry.join(registered.code(), addr("10.0.0.3", 3000), java.util.Optional.empty(), (a, b) -> false);

        assertNotEquals(first.token(), second.token());
        assertEquals(addr("10.0.0.3", 3000), second.joinerAddress());
    }

    @Test
    void joinFromNewJoinerBeyondMaxPlayersIsRejectedAsRoomFull() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        // maxPlayers=1, host has already reported 1 connected player (the first joiner).
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host, 1, 1, java.util.Optional.empty(), false);

        RoomRegistry.JoinRejected rejected = (RoomRegistry.JoinRejected) registry.join(registered.code(), addr("10.0.0.3", 3000), java.util.Optional.empty(), (a, b) -> false);

        assertEquals(RendezvousProtocol.REASON_ALREADY_CLAIMED, rejected.reason());
    }

    @Test
    void aKnownJoinerCanRejoinEvenWhenRoomIsReportedFull() throws UnknownHostException {
        // Capacity only gates genuinely NEW addresses — a joiner this room has already
        // matched isn't a new admission, so it must be let back in regardless of maxPlayers
        // (e.g. its own brief disconnect/reconnect shouldn't get treated as someone else's
        // slot).
        AtomicLong clock = new AtomicLong(0);
        RoomRegistry registry = new RoomRegistry(clock::get);
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        RendezvousProtocol.Address joiner = addr("10.0.0.2", 2000);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host, 1, 0, java.util.Optional.empty(), false);
        registry.join(registered.code(), joiner, java.util.Optional.empty(), (a, b) -> false);

        clock.set(RoomRegistry.REMATCH_DEBOUNCE_MILLIS + 1);
        // Host still reports the room as full (hasn't observed the brief disconnect yet).
        registry.register(host, 1, 1, java.util.Optional.empty(), false);

        RoomRegistry.JoinResult result = registry.join(registered.code(), joiner, java.util.Optional.empty(), (a, b) -> false);

        assertInstanceOf(RoomRegistry.Matched.class, result);
    }

    @Test
    void slotFreedByLeavingPlayerBecomesJoinableAfterHostReportsLowerCount() throws UnknownHostException {
        // The live-capacity mechanism this feature relies on: the host self-reports its
        // current player count on every keepalive REGISTER, so a slot freed up by a
        // departing player becomes available again without any dedicated "player left"
        // message.
        RoomRegistry registry = new RoomRegistry();
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host, 1, 1, java.util.Optional.empty(), false);

        RoomRegistry.JoinRejected full = (RoomRegistry.JoinRejected) registry.join(registered.code(), addr("10.0.0.3", 3000), java.util.Optional.empty(), (a, b) -> false);
        assertEquals(RendezvousProtocol.REASON_ALREADY_CLAIMED, full.reason());

        // The original joiner left; host's next keepalive reflects that.
        registry.register(host, 1, 0, java.util.Optional.empty(), false);

        RoomRegistry.JoinResult result = registry.join(registered.code(), addr("10.0.0.3", 3000), java.util.Optional.empty(), (a, b) -> false);
        assertInstanceOf(RoomRegistry.Matched.class, result);
    }

    @Test
    void registerRateLimitTriggersServerBusy() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        InetAddress ip = InetAddress.getByName("10.0.0.9");

        for (int i = 0; i < 5; i++) {
            RoomRegistry.RegisterResult result = registry.register(new RendezvousProtocol.Address(ip, 1000 + i), 4, 0, java.util.Optional.empty(), false);
            assertInstanceOf(RoomRegistry.Registered.class, result);
        }

        RoomRegistry.RegisterRejected sixth = (RoomRegistry.RegisterRejected) registry.register(new RendezvousProtocol.Address(ip, 2000), 4, 0, java.util.Optional.empty(), false);
        assertEquals(RendezvousProtocol.REASON_SERVER_BUSY, sixth.reason());
    }

    @Test
    void sweepDoesNotRemoveFreshRoom() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        registry.register(addr("10.0.0.1", 1000), 4, 0, java.util.Optional.empty(), false);

        registry.sweepExpired();

        assertEquals(1, registry.roomCount());
    }

    @Test
    void sweepRemovesRoomOnceHostStopsRefreshingIt() throws UnknownHostException {
        AtomicLong clock = new AtomicLong(0);
        RoomRegistry registry = new RoomRegistry(clock::get);
        registry.register(addr("10.0.0.1", 1000), 4, 0, java.util.Optional.empty(), false);

        clock.set(RoomRegistry.ROOM_TTL_MILLIS + 1);
        registry.sweepExpired();

        assertEquals(0, registry.roomCount());
    }

    @Test
    void keepaliveRegisterKeepsClaimedRoomAliveBeyondWhatWouldOtherwiseExpireIt() throws UnknownHostException {
        AtomicLong clock = new AtomicLong(0);
        RoomRegistry registry = new RoomRegistry(clock::get);
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(host, 4, 0, java.util.Optional.empty(), false);
        registry.join(registered.code(), addr("10.0.0.2", 2000), java.util.Optional.empty(), (a, b) -> false);

        // Host's keepalive refreshes the room well before it would expire.
        clock.set(RoomRegistry.ROOM_TTL_MILLIS - 1000);
        registry.register(host, 4, 1, java.util.Optional.empty(), false);

        clock.set(RoomRegistry.ROOM_TTL_MILLIS + 500);
        registry.sweepExpired();

        assertEquals(1, registry.roomCount(), "keepalive should have kept the room from expiring");
    }

    @Test
    void joinAfterRoomExpiredIsRejected() throws UnknownHostException {
        AtomicLong clock = new AtomicLong(0);
        RoomRegistry registry = new RoomRegistry(clock::get);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(addr("10.0.0.1", 1000), 4, 0, java.util.Optional.empty(), false);

        clock.set(RoomRegistry.ROOM_TTL_MILLIS + 1);
        RoomRegistry.JoinRejected rejected = (RoomRegistry.JoinRejected) registry.join(registered.code(), addr("10.0.0.2", 2000), java.util.Optional.empty(), (a, b) -> false);

        assertEquals(RendezvousProtocol.REASON_EXPIRED, rejected.reason());
    }

    // ---- Phase 6: friends-only rooms ----

    @Test
    void friendsOnlyRoomAdmitsAKnownFriend() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        java.util.UUID hostAccountId = java.util.UUID.randomUUID();
        java.util.UUID joinerAccountId = java.util.UUID.randomUUID();
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(
                addr("10.0.0.1", 1000), 4, 0, java.util.Optional.of(hostAccountId), true);

        RoomRegistry.JoinResult result = registry.join(registered.code(), addr("10.0.0.2", 2000),
                java.util.Optional.of(joinerAccountId), (a, b) -> a.equals(hostAccountId) && b.equals(joinerAccountId));

        assertInstanceOf(RoomRegistry.Matched.class, result);
    }

    @Test
    void friendsOnlyRoomRejectsANonFriendEvenWithAValidCode() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        java.util.UUID hostAccountId = java.util.UUID.randomUUID();
        java.util.UUID strangerAccountId = java.util.UUID.randomUUID();
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(
                addr("10.0.0.1", 1000), 4, 0, java.util.Optional.of(hostAccountId), true);

        RoomRegistry.JoinRejected rejected = (RoomRegistry.JoinRejected) registry.join(registered.code(), addr("10.0.0.2", 2000),
                java.util.Optional.of(strangerAccountId), (a, b) -> false);

        assertEquals(RendezvousProtocol.REASON_NOT_FRIEND, rejected.reason());
    }

    @Test
    void friendsOnlyRoomRejectsAnAnonymousJoinerWithNoAccount() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        java.util.UUID hostAccountId = java.util.UUID.randomUUID();
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(
                addr("10.0.0.1", 1000), 4, 0, java.util.Optional.of(hostAccountId), true);

        RoomRegistry.JoinRejected rejected = (RoomRegistry.JoinRejected) registry.join(registered.code(), addr("10.0.0.2", 2000),
                java.util.Optional.empty(), (a, b) -> true);

        assertEquals(RendezvousProtocol.REASON_NOT_FRIEND, rejected.reason());
    }

    @Test
    void rejectedNonFriendDoesNotConsumeARoomSlot() throws UnknownHostException {
        // The friend check must run before capacity/debounce bookkeeping — otherwise a
        // stranger who merely knows the code could exhaust a friends-only room's capacity.
        RoomRegistry registry = new RoomRegistry();
        java.util.UUID hostAccountId = java.util.UUID.randomUUID();
        java.util.UUID friendAccountId = java.util.UUID.randomUUID();
        java.util.UUID strangerAccountId = java.util.UUID.randomUUID();
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(
                addr("10.0.0.1", 1000), 1, 0, java.util.Optional.of(hostAccountId), true);
        java.util.function.BiPredicate<java.util.UUID, java.util.UUID> onlyFriendAccountId =
                (a, b) -> b.equals(friendAccountId);

        RoomRegistry.JoinRejected strangerRejected = (RoomRegistry.JoinRejected) registry.join(registered.code(), addr("10.0.0.2", 2000),
                java.util.Optional.of(strangerAccountId), onlyFriendAccountId);
        assertEquals(RendezvousProtocol.REASON_NOT_FRIEND, strangerRejected.reason());

        RoomRegistry.JoinResult friendResult = registry.join(registered.code(), addr("10.0.0.3", 3000),
                java.util.Optional.of(friendAccountId), onlyFriendAccountId);
        assertInstanceOf(RoomRegistry.Matched.class, friendResult);
    }

    @Test
    void friendsOnlyFalseIsUnaffectedByAbsentHostAccountId() throws UnknownHostException {
        // friendsOnly=false is the overwhelmingly common case (anonymous hosting) — must
        // behave exactly as before regardless of hostAccountId.
        RoomRegistry registry = new RoomRegistry();
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(
                addr("10.0.0.1", 1000), 4, 0, java.util.Optional.empty(), false);

        RoomRegistry.JoinResult result = registry.join(registered.code(), addr("10.0.0.2", 2000),
                java.util.Optional.empty(), (a, b) -> false);

        assertInstanceOf(RoomRegistry.Matched.class, result);
    }

    // ---- Phase 7: public game browser ----

    @Test
    void listPublicRoomsOnlyReturnsRoomsMarkedPublic() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        registry.register(addr("10.0.0.1", 1000), 4, 0, java.util.Optional.empty(), false, true, "Steve's SMP");
        registry.register(addr("10.0.0.2", 2000), 4, 0, java.util.Optional.empty(), false, false, "");

        java.util.List<RoomRegistry.PublicRoomInfo> publicRooms = registry.listPublicRooms();

        assertEquals(1, publicRooms.size());
        assertEquals("Steve's SMP", publicRooms.get(0).worldName());
    }

    @Test
    void listPublicRoomsCarriesTheHostsMcVersion() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        registry.register(addr("10.0.0.1", 1000), 4, 0, java.util.Optional.empty(), false, true, "Steve's SMP", "1.21.1");

        assertEquals("1.21.1", registry.listPublicRooms().get(0).mcVersion());
    }

    @Test
    void friendsOnlyForcesPublicRoomFalseRegardlessOfWhatTheClientSent() throws UnknownHostException {
        // Defense in depth — a room gated to friends must never also be broadcast to every
        // anonymous player, even if a buggy/malicious client sends both flags true.
        RoomRegistry registry = new RoomRegistry();
        java.util.UUID hostAccountId = java.util.UUID.randomUUID();

        registry.register(addr("10.0.0.1", 1000), 4, 0, java.util.Optional.of(hostAccountId), true, true, "Sneaky");

        assertTrue(registry.listPublicRooms().isEmpty());
    }

    @Test
    void listPublicRoomsExcludesExpiredRooms() throws UnknownHostException {
        AtomicLong clock = new AtomicLong(0);
        RoomRegistry registry = new RoomRegistry(clock::get);
        registry.register(addr("10.0.0.1", 1000), 4, 0, java.util.Optional.empty(), false, true, "Old world");

        clock.set(RoomRegistry.ROOM_TTL_MILLIS + 1);

        assertTrue(registry.listPublicRooms().isEmpty());
    }

    @Test
    void listPublicRoomsHidesAStoppedHostLongBeforeItsFullRoomTtlExpires() throws UnknownHostException {
        // The actual bug this guards against: a host that stops hosting (closes their world)
        // must disappear from the PUBLIC BROWSER within roughly one missed keepalive, not
        // linger for the full 10-minute ROOM_TTL_MILLIS a private room code stays reclaimable
        // for. The room itself (join-by-code) is still alive/unexpired at this point — only
        // its public visibility is gated more tightly, see listPublicRooms()'s doc comment.
        AtomicLong clock = new AtomicLong(0);
        RoomRegistry registry = new RoomRegistry(clock::get);
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        RoomRegistry.Registered registered = (RoomRegistry.Registered) registry.register(
                host, 4, 0, java.util.Optional.empty(), false, true, "Stopped hosting");

        clock.set(RoomRegistry.PUBLIC_LISTING_STALE_MILLIS + 1);

        assertTrue(registry.listPublicRooms().isEmpty(), "a stale room must vanish from the public browser well before ROOM_TTL_MILLIS");
        assertEquals(1, registry.roomCount(), "the room itself must still exist/be joinable by code at this point");
        assertInstanceOf(RoomRegistry.Matched.class,
                registry.join(registered.code(), addr("10.0.0.2", 2000), java.util.Optional.empty(), (a, b) -> false));
    }

    @Test
    void listPublicRoomsShowsARoomRefreshedWithinOneKeepaliveInterval() throws UnknownHostException {
        // The other side of the same fix: a room that's still actively keeping alive (host's
        // keepalive is every 15s, RendezvousClient.KEEPALIVE_INTERVAL_MILLIS) must not
        // flicker out of the browser between keepalives.
        AtomicLong clock = new AtomicLong(0);
        RoomRegistry registry = new RoomRegistry(clock::get);
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        registry.register(host, 4, 0, java.util.Optional.empty(), false, true, "Still hosting");

        clock.set(15_000L);
        registry.register(host, 4, 0, java.util.Optional.empty(), false, true, "Still hosting");
        clock.set(15_000L + RoomRegistry.PUBLIC_LISTING_STALE_MILLIS - 1_000L);

        assertEquals(1, registry.listPublicRooms().size());
    }

    @Test
    void publicRoomKeepaliveSelfCorrectsWorldNameAndFlag() throws UnknownHostException {
        RoomRegistry registry = new RoomRegistry();
        RendezvousProtocol.Address host = addr("10.0.0.1", 1000);
        registry.register(host, 4, 0, java.util.Optional.empty(), false, true, "First name");

        // Same self-correcting keepalive pattern as maxPlayers/friendsOnly — the label can
        // change (or the room can stop being public) on any later REGISTER, not just the first.
        registry.register(host, 4, 1, java.util.Optional.empty(), false, true, "Renamed world");
        assertEquals("Renamed world", registry.listPublicRooms().get(0).worldName());

        registry.register(host, 4, 1, java.util.Optional.empty(), false, false, "");
        assertTrue(registry.listPublicRooms().isEmpty());
    }

    @Test
    void anonymousHostCanListPublicly() throws UnknownHostException {
        // The whole point of Phase 7 — unlike friendsOnly, publicRoom needs no account.
        RoomRegistry registry = new RoomRegistry();
        registry.register(addr("10.0.0.1", 1000), 4, 0, java.util.Optional.empty(), false, true, "No account here");

        java.util.List<RoomRegistry.PublicRoomInfo> publicRooms = registry.listPublicRooms();

        assertEquals(1, publicRooms.size());
        assertTrue(publicRooms.get(0).hostAccountId().isEmpty());
    }
    @Test
    void anonymousHandoffNeedsRoomChallengeNotJustAddress() throws Exception {
        RoomRegistry r = new RoomRegistry(); RendezvousProtocol.Address host = addr("127.0.0.1", 12000);
        RoomRegistry.Registered room = (RoomRegistry.Registered) r.register(host, 4, 0, java.util.Optional.empty(), false);
        assertFalse(r.authorizesHandoff(room.code(), host, new byte[32]));
        byte[] proof = r.handoffChallenge(room.code(), host);
        assertTrue(r.authorizesHandoff(room.code(), host, proof));
        assertFalse(r.authorizesHandoff(room.code(), addr("127.0.0.1", 12001), proof));
    }

    @Test void lateAbortCannotUnlockRoomDuringANewAttempt() throws Exception {
        RoomRegistry r = new RoomRegistry();
        RoomRegistry.Registered room = (RoomRegistry.Registered) r.register(addr("127.0.0.1", 1000), 4, 0, java.util.Optional.empty(), false);
        r.suspendForHandoff(room.code(), "old"); r.resumeAfterHandoff(room.code(), "old");
        r.suspendForHandoff(room.code(), "new"); r.resumeAfterHandoff(room.code(), "old");
        assertInstanceOf(RoomRegistry.JoinRejected.class,
                r.join(room.code(), addr("127.0.0.1", 2000), java.util.Optional.empty(), (a,b) -> false));
    }

}
