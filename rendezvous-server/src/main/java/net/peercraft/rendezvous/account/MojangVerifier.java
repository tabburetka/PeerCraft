package net.peercraft.rendezvous.account;

import java.util.Optional;
import java.util.UUID;

/**
 * Test seam for verifying that a client actually owns the licensed Mojang account it claims —
 * same mechanism vanilla online-mode servers use (client calls {@code joinServer} against
 * Mojang, server confirms via {@code hasJoined}), just without a real Minecraft protocol
 * handshake: {@code serverId} here is an opaque nonce the server picked for this attempt, and
 * Mojang's API only cares that both calls used the same string.
 */
interface MojangVerifier {

    record MojangProfile(UUID id, String name) {
    }

    /** @return the confirmed profile, or empty if Mojang didn't confirm (wrong/no session, network failure, timeout, ...). */
    Optional<MojangProfile> hasJoined(String username, String serverId);
}
