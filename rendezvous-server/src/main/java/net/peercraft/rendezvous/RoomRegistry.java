package net.peercraft.rendezvous;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * In-memory room bookkeeping. No persistence — rooms are short-lived pairing
 * handshakes, not accounts (accounts are explicit future work, not this version).
 */
final class RoomRegistry {

    private static final int CODE_LENGTH = 6;

    // A room (claimed or not) stays alive as long as the host keeps sending
    // REGISTER/keepalive within this window — it behaves like a persistent "address" for
    // the hosted world, not a single-use pairing token. Reclaimed once the host stops
    // refreshing it for this long (closed the world, crashed, quit the mod).
    static final long ROOM_TTL_MILLIS = 10 * 60_000L;
    // A JOIN from the SAME address within this window of the room's last match is treated
    // as an in-flight retry of that same connection attempt (same token reused) — this
    // absorbs the joiner's own ~500ms-interval UDP retries. A JOIN arriving later — even
    // from the same address — is a genuinely new attempt (e.g. reconnecting after a
    // disconnect) and gets a fresh token and a fresh match instead of being rejected.
    static final long REMATCH_DEBOUNCE_MILLIS = 3_000L;

    private static final int MAX_ROOMS = 1000;
    private static final int REGISTER_RATE_LIMIT = 5;
    private static final long REGISTER_RATE_WINDOW_MILLIS = 60_000L;
    private static final int MIN_MAX_PLAYERS = 1;
    private static final int MAX_MAX_PLAYERS = 32;

    private final Map<String, Room> roomsByCode = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final CodeGenerator codeGenerator = new CodeGenerator(CODE_LENGTH);
    private final RateLimiter<InetAddress> registerRateLimiter;

    RoomRegistry() {
        this(System::currentTimeMillis);
    }

    /** Package-private seam so tests can control TTL/grace-window behavior deterministically. */
    RoomRegistry(LongSupplier clock) {
        this.clock = clock;
        this.registerRateLimiter = new RateLimiter<>(REGISTER_RATE_LIMIT, REGISTER_RATE_WINDOW_MILLIS, clock);
    }

    interface RegisterResult {
    }

    /** {@code reused} distinguishes a genuinely new room from an idempotent resend of an existing one — diagnostic only. */
    record Registered(String code, boolean reused) implements RegisterResult {
    }

    record RegisterRejected(byte reason) implements RegisterResult {
    }

    interface JoinResult {
    }

    record Matched(RendezvousProtocol.Address hostAddress, RendezvousProtocol.Address joinerAddress, long token) implements JoinResult {
    }

    record JoinRejected(byte reason) implements JoinResult {
    }

    RegisterResult register(RendezvousProtocol.Address hostAddress, int maxPlayers, int currentPlayerCount) {
        long now = clock.getAsLong();
        int clampedMaxPlayers = clamp(maxPlayers, MIN_MAX_PLAYERS, MAX_MAX_PLAYERS);

        // Idempotent retry/keepalive: this host already has a room — claimed or not —
        // refresh its lifetime and hand back the same code instead of minting a new one.
        // This is what lets a room code stay valid (and rejoinable) for as long as the
        // host keeps hosting, rather than being replaced the moment it's first claimed.
        // Also self-corrects maxPlayers/currentPlayerCount on every keepalive — this is
        // what lets a slot freed up by a leaving player become joinable again within one
        // keepalive interval, without a dedicated "player left" message.
        for (Room existing : roomsByCode.values()) {
            if (existing.hostAddress.equals(hostAddress)) {
                existing.lastSeenAt = now;
                existing.maxPlayers = clampedMaxPlayers;
                existing.currentPlayerCount = currentPlayerCount;
                return new Registered(existing.code, true);
            }
        }

        if (!registerRateLimiter.allow(hostAddress.host())) {
            return new RegisterRejected(RendezvousProtocol.REASON_SERVER_BUSY);
        }
        if (roomsByCode.size() >= MAX_ROOMS) {
            return new RegisterRejected(RendezvousProtocol.REASON_SERVER_BUSY);
        }

        String code = codeGenerator.generateUnique(roomsByCode::containsKey);
        Room room = new Room(code, hostAddress, now);
        room.maxPlayers = clampedMaxPlayers;
        room.currentPlayerCount = currentPlayerCount;
        roomsByCode.put(code, room);
        return new Registered(code, false);
    }

    JoinResult join(String code, RendezvousProtocol.Address joinerAddress) {
        Room room = roomsByCode.get(code);
        if (room == null) {
            return new JoinRejected(RendezvousProtocol.REASON_INVALID_CODE);
        }

        long now = clock.getAsLong();
        synchronized (room) {
            if (now - room.lastSeenAt > ROOM_TTL_MILLIS) {
                roomsByCode.remove(code, room);
                return new JoinRejected(RendezvousProtocol.REASON_EXPIRED);
            }

            room.lastSeenAt = now;

            Room.JoinerSlot slot = room.joiners.get(joinerAddress);
            boolean sameRecentMatch = slot != null && (now - slot.lastMatchedAt) <= REMATCH_DEBOUNCE_MILLIS;
            if (sameRecentMatch) {
                return new Matched(room.hostAddress, joinerAddress, slot.token);
            }

            // A genuinely new address counts against the room's capacity — a joiner this
            // room has seen before (reconnecting after a gap longer than the debounce
            // window) is always let back in regardless of maxPlayers, since it isn't a new
            // admission. currentPlayerCount is self-reported by the host (see register())
            // and only advisory here — P2PBridge on the host is the real authority and
            // enforces this again once actual relay traffic arrives.
            if (slot == null && room.currentPlayerCount >= room.maxPlayers) {
                return new JoinRejected(RendezvousProtocol.REASON_ALREADY_CLAIMED);
            }

            long token = ThreadLocalRandom.current().nextLong();
            room.joiners.put(joinerAddress, new Room.JoinerSlot(joinerAddress, token, now));

            return new Matched(room.hostAddress, joinerAddress, token);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Called periodically (see RendezvousServer) to bound memory — not on the request path. */
    void sweepExpired() {
        long now = clock.getAsLong();
        roomsByCode.values().removeIf(room -> now - room.lastSeenAt > ROOM_TTL_MILLIS);
    }

    int roomCount() {
        return roomsByCode.size();
    }

    /** Diagnostic snapshot — code plus a short description of each room's state, for debug logging. */
    java.util.List<String> describeAllRooms() {
        long now = clock.getAsLong();
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (Room room : roomsByCode.values()) {
            String state = room.joiners.isEmpty()
                    ? "unclaimed"
                    : "joined by " + room.joiners.size() + "/" + room.maxPlayers + " (self-reported: " + room.currentPlayerCount + ")";
            lines.add(room.code + " (host=" + room.hostAddress.host().getHostAddress() + ":" + room.hostAddress.port()
                    + ", " + state + ", last seen " + (now - room.lastSeenAt) + "ms ago)");
        }
        return lines;
    }
}
