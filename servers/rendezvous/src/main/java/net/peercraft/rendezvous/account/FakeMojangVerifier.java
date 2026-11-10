package net.peercraft.rendezvous.account;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Test/dev {@link MojangVerifier} that never calls the real Mojang API — deterministically
 * "confirms" any username. Enabled via {@code -Dpeercraft.rendezvous.fakeMojang=true} (see
 * RendezvousServer.main). Needed for Level-2/3 automated tests of the licensed login path,
 * which would otherwise either not exist or flakily depend on a real Mojang account/network
 * access — see AccountEndToEndTest.
 */
final class FakeMojangVerifier implements MojangVerifier {

    @Override
    public Optional<MojangProfile> hasJoined(String username, String serverId) {
        UUID id = UUID.nameUUIDFromBytes(("fake-mojang:" + username).getBytes(StandardCharsets.UTF_8));
        return Optional.of(new MojangProfile(id, username));
    }
}
