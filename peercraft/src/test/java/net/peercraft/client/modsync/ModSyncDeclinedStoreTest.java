package net.peercraft.client.modsync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModSyncDeclinedStoreTest {

    @Test
    void roundTripsThroughSaveAndLoad(@TempDir Path dir) {
        Path file = dir.resolve("nested").resolve("modsync-declined.json");

        ModSyncDeclinedStore.save(file, new LinkedHashSet<>(List.of("xaeros_minimap", "sodium")));
        Set<String> loaded = ModSyncDeclinedStore.load(file);

        assertEquals(Set.of("xaeros_minimap", "sodium"), loaded);
    }

    @Test
    void savingAnEmptySetClearsTheList(@TempDir Path dir) {
        Path file = dir.resolve("modsync-declined.json");
        ModSyncDeclinedStore.save(file, new LinkedHashSet<>(List.of("a")));
        ModSyncDeclinedStore.save(file, new LinkedHashSet<>());

        assertTrue(ModSyncDeclinedStore.load(file).isEmpty());
    }

    @Test
    void missingFileReadsAsEmpty(@TempDir Path dir) {
        assertTrue(ModSyncDeclinedStore.load(dir.resolve("nope.json")).isEmpty());
    }

    @Test
    void corruptFileReadsAsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("modsync-declined.json");
        Files.writeString(file, "not json {{");
        assertDoesNotThrow(() -> assertTrue(ModSyncDeclinedStore.load(file).isEmpty()));
    }
}
