package net.peercraft.rendezvous;

/** Mutable pairing state for one room code. Package-private — only {@link RoomRegistry} touches it. */
final class Room {

    final String code;
    final RendezvousProtocol.Address hostAddress;
    final long createdAt;

    // Guarded by synchronizing on the Room instance itself (see RoomRegistry.join).
    boolean claimed = false;
    RendezvousProtocol.Address joinerAddress;
    long token;
    long claimedAt;

    Room(String code, RendezvousProtocol.Address hostAddress, long createdAt) {
        this.code = code;
        this.hostAddress = hostAddress;
        this.createdAt = createdAt;
    }
}
