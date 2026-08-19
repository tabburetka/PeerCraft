package net.peercraft.rendezvous;

/** Mutable pairing state for one room code. Package-private — only {@link RoomRegistry} touches it. */
final class Room {

    final String code;
    final RendezvousProtocol.Address hostAddress;
    final long createdAt;

    // Refreshed on every REGISTER (host keepalive) or JOIN — drives RoomRegistry's
    // sweepExpired(). A room stays alive indefinitely, claimed or not, as long as the
    // host keeps hosting; it's only reclaimed once nothing has touched it for a while
    // (host crashed, closed the world, quit the mod).
    long lastSeenAt;

    // Guarded by synchronizing on the Room instance itself (see RoomRegistry.join).
    boolean claimed = false;
    RendezvousProtocol.Address joinerAddress;
    long token;
    // When token was last (re)issued — used for RoomRegistry.REMATCH_DEBOUNCE_MILLIS so a
    // joiner's own rapid UDP retries of one connection attempt reuse the same token,
    // while a genuinely later JOIN (e.g. reconnecting with the same code) gets a fresh one.
    long lastMatchedAt;

    Room(String code, RendezvousProtocol.Address hostAddress, long createdAt) {
        this.code = code;
        this.hostAddress = hostAddress;
        this.createdAt = createdAt;
        this.lastSeenAt = createdAt;
    }
}
