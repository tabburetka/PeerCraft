package net.peercraft.config;

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
 * Persistence for {@link PeerCraftSettings} at {@code config/peercraft/settings.json}. Same
 * tolerant-of-missing-or-corrupt-file philosophy as {@code AccountStorage}: a bad file here
 * must never crash the game — worst case the player's saved flags are ignored this launch and
 * the launch-flag / built-in defaults apply.
 *
 * <p>Java-8 clean and {@code net.minecraft}-free — synced verbatim into the Forge backports.
 */
public final class PeerCraftSettingsStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PeerCraftSettingsStore() {
    }

    private static Path file() {
        return Services.PLATFORM.getConfigDir().resolve("peercraft").resolve("settings.json");
    }

    /** Never returns {@code null}: a missing or unreadable file yields a fresh (all-default) instance. */
    public static PeerCraftSettings load() {
        return load(file());
    }

    public static void save(PeerCraftSettings settings) {
        save(file(), settings);
    }

    public static void clear() {
        clear(file());
    }

    /** Path-explicit seam so tests exercise the real read/write/error handling without a bootstrapped loader. */
    static PeerCraftSettings load(Path path) {
        if (!Files.exists(path)) {
            return new PeerCraftSettings();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            PeerCraftSettings parsed = GSON.fromJson(reader, PeerCraftSettings.class);
            // Older settings screens saved the public IP even when the player kept the
            // default. Drop only that legacy value so it follows the domain (or DEVELOP
            // defaults); preserve custom servers and explicit launch flags.
            if (parsed != null && parsed.rendezvousHost != null
                    && "91.146.31.165".equals(parsed.rendezvousHost.trim())) {
                parsed.rendezvousHost = null;
            }
            return parsed != null ? parsed : new PeerCraftSettings();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[PeerCraftSettings] Не удалось прочитать {} — использую настройки по умолчанию: {}", path, e.toString());
            return new PeerCraftSettings();
        }
    }

    static void save(Path path, PeerCraftSettings settings) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Write to a temp file then atomically move — a crash mid-write must never leave a
            // half-written settings.json that fails to parse on next launch.
            Path tmp = Files.createTempFile(parent, "settings", ".json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) {
                GSON.toJson(settings, writer);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("[PeerCraftSettings] Не удалось сохранить {}", path, e);
        }
    }

    static void clear(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("[PeerCraftSettings] Не удалось удалить {}: {}", path, e.toString());
        }
    }
}
