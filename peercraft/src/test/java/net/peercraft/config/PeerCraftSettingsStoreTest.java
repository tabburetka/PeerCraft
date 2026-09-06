package net.peercraft.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PeerCraftSettingsStoreTest {

    @Test
    void roundTripsThroughSaveAndLoad(@TempDir Path dir) {
        Path file = dir.resolve("nested").resolve("settings.json");

        PeerCraftSettings s = new PeerCraftSettings();
        s.modSyncHost = "required";
        s.modSyncClient = "off";
        s.maxPlayers = "6";
        s.showDeveloperSection = true;
        PeerCraftSettingsStore.save(file, s);

        PeerCraftSettings loaded = PeerCraftSettingsStore.load(file);
        assertEquals("required", loaded.modSyncHost);
        assertEquals("off", loaded.modSyncClient);
        assertEquals("6", loaded.maxPlayers);
        assertTrue(loaded.showDeveloperSection);
    }

    @Test
    void toOverrideMapEmitsOnlySetFlagsAndNeverTheUiState(@TempDir Path dir) {
        PeerCraftSettings s = new PeerCraftSettings();
        s.modSyncHost = "required";
        s.rendezvousPort = "  ";       // blank -> dropped
        s.showDeveloperSection = true; // not a flag

        Map<String, String> map = s.toOverrideMap();
        assertEquals("required", map.get("modSync.host"));
        assertFalse(map.containsKey("rendezvousPort"));
        assertFalse(map.containsKey("_showDeveloper"));
        assertEquals(1, map.size());
    }

    @Test
    void missingFileLoadsDefaults(@TempDir Path dir) {
        PeerCraftSettings loaded = PeerCraftSettingsStore.load(dir.resolve("nope.json"));
        assertNotNull(loaded);
        assertNull(loaded.modSyncHost);
        assertTrue(loaded.toOverrideMap().isEmpty());
    }

    @Test
    void corruptFileLoadsDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "not json {{");
        assertDoesNotThrow(() -> {
            PeerCraftSettings loaded = PeerCraftSettingsStore.load(file);
            assertTrue(loaded.toOverrideMap().isEmpty());
        });
    }
}
