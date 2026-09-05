package net.peercraft.client.modsync;

// Forge 1.12.2 backport of src/main/.../client/modsync/ModSyncDeclinedStore.java —
// String.isBlank() -> trim().isEmpty(); otherwise verbatim. Keep in sync with the original.

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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The client-side mod ids the player chose NOT to download on the mod-sync confirm screen,
 * persisted to {@code config/peercraft/modsync-declined.json}. Read back on the next join so
 * the screen doesn't nag on every re-connect.
 */
public final class ModSyncDeclinedStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ModSyncDeclinedStore() {
    }

    /** Gson shape of the file. */
    private static final class Doc {
        int version = 1;
        List<String> declined = new ArrayList<>();
    }

    private static Path file() {
        return Services.PLATFORM.getConfigDir().resolve("peercraft").resolve("modsync-declined.json");
    }

    public static Set<String> load() {
        return load(file());
    }

    public static void save(Set<String> modIds) {
        save(file(), modIds);
    }

    static Set<String> load(Path path) {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.exists(path)) {
            return out;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Doc doc = GSON.fromJson(reader, Doc.class);
            if (doc != null && doc.declined != null) {
                for (String id : doc.declined) {
                    if (id != null && !id.trim().isEmpty()) {
                        out.add(id);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[ModSync] Не удалось прочитать {} — считаем список отклонённых модов пустым: {}", path, e.toString());
        }
        return out;
    }

    static void save(Path path, Set<String> modIds) {
        Doc doc = new Doc();
        doc.declined = new ArrayList<>(modIds);
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(parent, "modsync-declined", ".json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) {
                GSON.toJson(doc, writer);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("[ModSync] Не удалось сохранить {}", path, e);
        }
    }
}
