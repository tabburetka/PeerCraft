package net.peercraft.rendezvous;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Mutable pairing state for one room code. Package-private — only {@link RoomRegistry} touches it. */
final class Room {

    final String code;
    final RendezvousProtocol.Address hostAddress;
    final long createdAt;

    // Self-reported (and server-verified against the REGISTER's sessionToken — see
    // RendezvousServer.handleRegister) on every REGISTER, same self-correcting keepalive
    // pattern as maxPlayers/currentPlayerCount below. Empty for an anonymous host.
    Optional<UUID> hostAccountId = Optional.empty();
    // Phase 6: when true, JOIN is gated to accounts on hostAccountId's friends list — see
    // RoomRegistry.join(). Only ever true together with a present hostAccountId (enforced by
    // RendezvousServer.handleRegister, not here).
    volatile boolean friendsOnly = false;
    // Phase 7: when true, this room is listed in the public game browser (anyone with the mod
    // can see and join it — see RoomRegistry.listPublicRooms()), no account/friendship needed.
    // Mutually exclusive with friendsOnly — RoomRegistry.register() forces this false whenever
    // friendsOnly is true, regardless of what the host's client sent.
    volatile boolean publicRoom = false;
    // Free-text label the host chose for the public browser row (Phase 7) — only meaningful
    // while publicRoom is true; "" otherwise.
    volatile String worldName = "";
    // Host's running Minecraft version (e.g. "1.21.1"), self-reported on REGISTER — same
    // publicRoom-only lifetime as worldName. Lets the browser warn about (or filter out)
    // rooms a joiner's vanilla client protocol can't actually connect to.
    volatile String mcVersion = "";

    // Refreshed on every REGISTER (host keepalive) or JOIN — drives RoomRegistry's
    // sweepExpired(). A room stays alive indefinitely, claimed or not, as long as the
    // host keeps hosting; it's only reclaimed once nothing has touched it for a while
    // (host crashed, closed the world, quit the mod).
    volatile long lastSeenAt;

    // Self-reported by the host on every REGISTER (including the 15s keepalive) — this is
    // how a slot freed up by a player leaving becomes joinable again within one keepalive
    // interval, without a dedicated "player left" wire message. Advisory only: P2PBridge on
    // the host is the actual authority on who's connected; this just avoids wasted punch
    // attempts in the common case (see RoomRegistry.join()).
    int maxPlayers = 1;
    volatile int currentPlayerCount = 0;

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
