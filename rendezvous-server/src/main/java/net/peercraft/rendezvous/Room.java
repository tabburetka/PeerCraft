package net.peercraft.rendezvous;

import java.util.LinkedHashMap;
import java.util.Map;

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

    // Self-reported by the host on every REGISTER (including the 15s keepalive) — this is
    // how a slot freed up by a player leaving becomes joinable again within one keepalive
    // interval, without a dedicated "player left" wire message. Advisory only: P2PBridge on
    // the host is the actual authority on who's connected; this just avoids wasted punch
    // attempts in the common case (see RoomRegistry.join()).
    int maxPlayers = 1;
    int currentPlayerCount = 0;

    // Guarded by synchronizing on the Room instance itself (see RoomRegistry.join). Keyed by
    // joiner address so multiple joiners can hold independent per-address debounce/rematch
    // state instead of one room-global slot.
    final Map<RendezvousProtocol.Address, JoinerSlot> joiners = new LinkedHashMap<>();

    Room(String code, RendezvousProtocol.Address hostAddress, long createdAt) {
        this.code = code;
        this.hostAddress = hostAddress;
        this.createdAt = createdAt;
        this.lastSeenAt = createdAt;
    }

    /** Per-joiner pairing state: last issued token and when it was (re)issued. */
    static final class JoinerSlot {
        final RendezvousProtocol.Address address;
        long token;
        long lastMatchedAt;

        JoinerSlot(RendezvousProtocol.Address address, long token, long lastMatchedAt) {
            this.address = address;
            this.token = token;
            this.lastMatchedAt = lastMatchedAt;
        }
    }
}
