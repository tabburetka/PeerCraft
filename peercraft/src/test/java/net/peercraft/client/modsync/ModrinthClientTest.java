package net.peercraft.client.modsync;

import net.peercraft.network.modsync.ModEntry;
import net.peercraft.network.modsync.ModSyncPlan;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ModrinthClientTest {

    @Test
    void serverRequiredOnBothSidesIsBoth() {
        assertEquals(Optional.of(ModEntry.Env.BOTH), ModrinthClient.sideToEnv("required", "required"));
        assertEquals(Optional.of(ModEntry.Env.BOTH), ModrinthClient.sideToEnv("optional", "required"));
    }

    @Test
    void serverNotRequiredMeansClientSkippable() {
        // AppleSkin: client required, server optional — still safe to skip when joining.
        assertEquals(Optional.of(ModEntry.Env.CLIENT), ModrinthClient.sideToEnv("required", "optional"));
        // Sodium: client required, server unsupported.
        assertEquals(Optional.of(ModEntry.Env.CLIENT), ModrinthClient.sideToEnv("required", "unsupported"));
    }

    @Test
    void clientUnsupportedButServerRequiredIsServerOnly() {
        assertEquals(Optional.of(ModEntry.Env.SERVER), ModrinthClient.sideToEnv("unsupported", "required"));
    }

    @Test
    void unknownServerSideYieldsNoVerdict() {
        assertTrue(ModrinthClient.sideToEnv("required", "unknown").isEmpty());
        assertTrue(ModrinthClient.sideToEnv("required", null).isEmpty());
    }

    @Test
    void caseInsensitive() {
        assertEquals(Optional.of(ModEntry.Env.BOTH), ModrinthClient.sideToEnv("REQUIRED", "REQUIRED"));
        assertEquals(Optional.of(ModEntry.Env.CLIENT), ModrinthClient.sideToEnv("Required", "Optional"));
    }

    @Test
    void onlyListedVersionWithMatchingFileHashGetsPublishedStatus() {
        String hash = "a".repeat(128);
        String files = "\"files\":[{\"hashes\":{\"sha512\":\"" + hash
                + "\"},\"url\":\"https://cdn.modrinth.com/file.jar\"}]";
        assertEquals(ModSyncPlan.CatalogStatus.PUBLISHED,
                ModrinthClient.parseLookup("{\"status\":\"listed\"," + files + "}", hash).status());
        assertEquals(ModSyncPlan.CatalogStatus.NOT_FOUND,
                ModrinthClient.parseLookup("{\"status\":\"unlisted\"," + files + "}", hash).status());
        assertEquals(ModSyncPlan.CatalogStatus.UNAVAILABLE,
                ModrinthClient.parseLookup("{\"status\":\"listed\",\"files\":[]}", hash).status());
    }
}
