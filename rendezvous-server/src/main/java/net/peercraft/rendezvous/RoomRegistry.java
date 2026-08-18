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

    static final long UNCLAIMED_TTL_MILLIS = 10 * 60_000L;
    static final long CLAIMED_TTL_MILLIS = 60_000L;

    private static final int MAX_UNCLAIMED_ROOMS = 1000;
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
        // Idempotent retry: this exact source already has an unclaimed room — resend it as-is
        // instead of minting a second one (UDP REGISTER has no delivery guarantee, so the host
        // may legitimately retry before it saw the first ROOM_CREATED reply). Note this match is
        // keyed on the full (ip, port) pair — if the host's NAT re-maps its external port between
        // retries/keepalives, this lookup misses and a *second*, independent room gets created
        // for the same host, silently orphaning the first one's code from the host's own point of
        // view (it already stopped listening for a fresh ROOM_CREATED after the first one). The
        // `reused` flag on the result exists so the caller can log this distinction and catch it.
        for (Room existing : roomsByCode.values()) {
            if (!existing.claimed && existing.hostAddress.equals(hostAddress)) {
                return new Registered(existing.code, true);
            }
        }

        if (!allowRegister(hostAddress.host())) {
            return new RegisterRejected(RendezvousProtocol.REASON_SERVER_BUSY);
        }
        if (countUnclaimed() >= MAX_UNCLAIMED_ROOMS) {
            return new RegisterRejected(RendezvousProtocol.REASON_SERVER_BUSY);
        }

        String code = generateUniqueCode();
        roomsByCode.put(code, new Room(code, hostAddress, clock.getAsLong()));
        return new Registered(code, false);
    }

    JoinResult join(String code, RendezvousProtocol.Address joinerAddress) {
        Room room = roomsByCode.get(code);
        if (room == null) {
            return new JoinRejected(RendezvousProtocol.REASON_INVALID_CODE);
        }

        long now = clock.getAsLong();

        synchronized (room) {
            if (!room.claimed) {
                if (now - room.createdAt > UNCLAIMED_TTL_MILLIS) {
                    roomsByCode.remove(code, room);
                    return new JoinRejected(RendezvousProtocol.REASON_EXPIRED);
                }
                room.joinerAddress = joinerAddress;
                room.token = ThreadLocalRandom.current().nextLong();
                room.claimedAt = now;
                room.claimed = true;
                return new Matched(room.hostAddress, room.joinerAddress, room.token);
            }

            if (room.joinerAddress.equals(joinerAddress)) {
                // Same joiner retrying (UDP has no delivery guarantee) — same idempotent answer.
                return new Matched(room.hostAddress, room.joinerAddress, room.token);
            }
        }

        return new JoinRejected(RendezvousProtocol.REASON_ALREADY_CLAIMED);
    }

    /** Called periodically (see RendezvousServer) to bound memory — not on the request path. */
    void sweepExpired() {
        long now = clock.getAsLong();
        roomsByCode.values().removeIf(room -> room.claimed
                ? now - room.claimedAt > CLAIMED_TTL_MILLIS
                : now - room.createdAt > UNCLAIMED_TTL_MILLIS);
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
                    ? "claimed " + (now - room.claimedAt) + "ms ago"
                    : "unclaimed, created " + (now - room.createdAt) + "ms ago";
            lines.add(room.code + " (host=" + room.hostAddress.host().getHostAddress() + ":" + room.hostAddress.port() + ", " + state + ")");
        }
        return lines;
    }

    private long countUnclaimed() {
        return roomsByCode.values().stream().filter(r -> !r.claimed).count();
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
