package net.peercraft.client.modsync;

// Forge 1.7.10 backport of src/main/.../client/modsync/ModSyncManifestStore.java — verbatim
// (Gson + java.nio.file only). Twin only because client/modsync is excluded from the shared
// tree in peercraft-forge-1710/build.gradle. Gson comes from Forge/MC at runtime.

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.peercraft.platform.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Reads/writes {@link ModSyncManifest}. Gson, temp file + atomic move, tolerant of a missing
 * or corrupt file (returns a fresh empty manifest), never throws out to the caller.
 */
public final class ModSyncManifestStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ModSyncManifestStore() {
    }

    private static Path file() {
        return Services.PLATFORM.getConfigDir().resolve("peercraft").resolve("modsync-installed.json");
    }

    public static ModSyncManifest load() {
        return load(file());
    }

    public static void save(ModSyncManifest manifest) {
        save(file(), manifest);
    }

    static ModSyncManifest load(Path path) {
        if (!Files.exists(path)) {
            return new ModSyncManifest();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            ModSyncManifest m = GSON.fromJson(reader, ModSyncManifest.class);
            return m != null ? m : new ModSyncManifest();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[ModSync] Не удалось прочитать {} — начинаем с пустого манифеста: {}", path, e.toString());
            return new ModSyncManifest();
        }
    }

    static void save(Path path, ModSyncManifest manifest) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(parent, "modsync", ".json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) {
                GSON.toJson(manifest, writer);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("[ModSync] Не удалось сохранить {}", path, e);
        }
    }
}
