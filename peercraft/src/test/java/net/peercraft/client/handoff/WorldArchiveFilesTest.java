package net.peercraft.client.handoff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

class WorldArchiveFilesTest {
    @TempDir Path world;

    @Test void preservesExtensionDataAndEmptyDirectories() throws Exception {
        String[] files = {"level.dat", "plugins/example/plugin.jar", "plugins/example/config.yml",
                "datapacks/example/pack.mcmeta", "serverconfig/mod.toml", "DIM-1/region/r.0.0.mca",
                ".peercraft-extension/data", "plugins/.peercraft-custom/data", "plugins/session.lock"};
        for (String file : files) put(file);
        Files.createDirectories(world.resolve("plugins/empty"));
        Map<String, byte[]> entries = archive();
        for (String file : files) assertArrayEquals(new byte[]{1, 2, 3}, entries.get(file), file);
        assertTrue(entries.containsKey("plugins/empty/"));
        assertEquals(files.length * 3L, WorldArchiveFiles.estimateSize(world));
    }

    @Test void excludesOnlyKnownWorldScratch() throws Exception {
        put("session.lock");
        put(".peercraft-player-migration/backup.dat");
        put(".peercraft-handoff-tmp/archive.zip");
        put(".peercraft-handoff-staging-42/level.dat");
        put("peercraft-world.json");
        Map<String, byte[]> entries = archive();
        assertEquals(1, entries.size());
        assertTrue(entries.containsKey("peercraft-world.json"));
        assertEquals(3L, WorldArchiveFiles.estimateSize(world));
    }

    @Test void refusesLinkedDataInsteadOfDroppingOrLeakingIt() throws Exception {
        Path external = Files.createTempFile("handoff-external", ".dat");
        try {
            Files.createSymbolicLink(world.resolve("plugin-data"), external);
            assertThrows(IOException.class, this::archive);
            assertThrows(IOException.class, () -> WorldArchiveFiles.estimateSize(world));
        } finally {
            Files.deleteIfExists(external);
        }
    }

    private void put(String name) throws IOException {
        Path file = world.resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3});
    }

    private Map<String, byte[]> archive() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            WorldArchiveFiles.write(world, zip);
        }
        Map<String, byte[]> result = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) result.put(entry.getName(), zip.readAllBytes());
        }
        return result;
    }
}
