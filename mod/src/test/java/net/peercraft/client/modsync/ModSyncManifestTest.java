package net.peercraft.client.modsync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ModSyncManifestTest {

    private static ModSyncManifest.Install install(String id, String version) {
        ModSyncManifest.Install i = new ModSyncManifest.Install();
        i.modId = id;
        i.modVersion = version;
        i.fileName = id + "-" + version + ".jar";
        i.sha512 = "deadbeef";
        i.source = "p2p";
        i.sizeBytes = 42;
        i.installedAt = 1234;
        return i;
    }

    @Test
    void roundTripsThroughSaveAndLoad(@TempDir Path dir) {
        Path file = dir.resolve("nested").resolve("modsync-installed.json");
        ModSyncManifest m = new ModSyncManifest();
        m.record(install("aaa", "1.0"));
        m.record(install("bbb", "2.0"));

        ModSyncManifestStore.save(file, m);
        ModSyncManifest loaded = ModSyncManifestStore.load(file);

        assertEquals(2, loaded.installs.size());
        assertEquals("aaa", loaded.installs.get(0).modId);
        assertEquals("bbb-2.0.jar", loaded.installs.get(1).fileName);
    }

    @Test
    void recordReplacesAnEarlierEntryForTheSameModId() {
        ModSyncManifest m = new ModSyncManifest();
        m.record(install("aaa", "1.0"));
        m.record(install("aaa", "1.1"));

        assertEquals(1, m.installs.size());
        assertEquals("1.1", m.installs.get(0).modVersion);
    }

    @Test
    void loadReturnsEmptyManifestForMissingFile(@TempDir Path dir) {
        ModSyncManifest m = ModSyncManifestStore.load(dir.resolve("nope.json"));
        assertNotNull(m);
        assertTrue(m.installs.isEmpty());
    }

    @Test
    void loadToleratesCorruptFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("modsync-installed.json");
        Files.writeString(file, "}{ not json");
        assertDoesNotThrow(() -> {
            ModSyncManifest m = ModSyncManifestStore.load(file);
            assertTrue(m.installs.isEmpty());
        });
    }
}
