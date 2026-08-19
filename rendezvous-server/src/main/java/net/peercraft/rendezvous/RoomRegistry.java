package net.peercraft.rendezvous;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * In-memory room bookkeeping. No persistence — rooms are short-lived pairing
 * handshakes, not accounts (accounts are explicit future work, not this version).
 */
final class RoomRegistry {

    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
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

    private final Map<String, Room> roomsByCode = new ConcurrentHashMap<>();
    private final Map<InetAddress, Deque<Long>> registerTimestampsByIp = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    RoomRegistry() {
        this(System::currentTimeMillis);
    }

    /** Package-private seam so tests can control TTL/grace-window behavior deterministically. */
    RoomRegistry(LongSupplier clock) {
        this.clock = clock;
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

    RegisterResult register(RendezvousProtocol.Address hostAddress) {
        long now = clock.getAsLong();

        // Idempotent retry/keepalive: this host already has a room — claimed or not —
        // refresh its lifetime and hand back the same code instead of minting a new one.
        // This is what lets a room code stay valid (and rejoinable) for as long as the
        // host keeps hosting, rather than being replaced the moment it's first claimed.
        for (Room existing : roomsByCode.values()) {
            if (existing.hostAddress.equals(hostAddress)) {
                existing.lastSeenAt = now;
                return new Registered(existing.code, true);
            }
        }

        if (!allowRegister(hostAddress.host())) {
            return new RegisterRejected(RendezvousProtocol.REASON_SERVER_BUSY);
        }
        if (roomsByCode.size() >= MAX_ROOMS) {
            return new RegisterRejected(RendezvousProtocol.REASON_SERVER_BUSY);
        }

        String code = generateUniqueCode();
        roomsByCode.put(code, new Room(code, hostAddress, now));
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

            boolean sameRecentMatch = room.claimed
                    && joinerAddress.equals(room.joinerAddress)
                    && (now - room.lastMatchedAt) <= REMATCH_DEBOUNCE_MILLIS;
            if (!sameRecentMatch) {
                // Either the first-ever claim, or a genuinely new join (different address,
                // or the same one reconnecting well after its last match) — issue a fresh
                // token and re-match, rather than rejecting as ALREADY_CLAIMED.
                room.token = ThreadLocalRandom.current().nextLong();
                room.joinerAddress = joinerAddress;
                room.claimed = true;
                room.lastMatchedAt = now;
            }

            return new Matched(room.hostAddress, room.joinerAddress, room.token);
        }
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
            String state = room.claimed
                    ? "claimed, last matched " + (now - room.lastMatchedAt) + "ms ago"
                    : "unclaimed";
            lines.add(room.code + " (host=" + room.hostAddress.host().getHostAddress() + ":" + room.hostAddress.port()
                    + ", " + state + ", last seen " + (now - room.lastSeenAt) + "ms ago)");
        }
        return lines;
    }

    private boolean allowRegister(InetAddress ip) {
        Deque<Long> timestamps = registerTimestampsByIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        long now = clock.getAsLong();
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > REGISTER_RATE_WINDOW_MILLIS) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= REGISTER_RATE_LIMIT) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    private String generateUniqueCode() {
        String code;
        do {
            code = randomCode();
        } while (roomsByCode.containsKey(code));
        return code;
    }

    private static String randomCode() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
