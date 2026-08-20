package net.peercraft.client.account;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;

/**
 * The mod's first-ever client-side persistent storage — remembers the logged-in account
 * across game launches so the player doesn't have to log in every single time. Same
 * tolerant-of-missing-or-corrupt-file philosophy as {@code PeerCraftConfig}: a bad file here
 * must never crash the game, worst case is the player just has to log in again this launch.
 */
public final class AccountStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(UUID.class, new UuidAdapter())
            .create();

    private AccountStorage() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("peercraft").resolve("account.json");
    }

    public static Optional<AccountState> load() {
        return load(file());
    }

    public static void save(AccountState state) {
        save(file(), state);
    }

    public static void clear() {
        clear(file());
    }

    /** Path-explicit seam — lets tests exercise the real read/write/error-handling logic without needing a bootstrapped FabricLoader. */
    static Optional<AccountState> load(Path path) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return Optional.ofNullable(GSON.fromJson(reader, AccountState.class));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[AccountStorage] Не удалось прочитать {} — начинаем без сохранённого аккаунта: {}", path, e.toString());
            return Optional.empty();
        }
    }

    static void save(Path path, AccountState state) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Write to a temp file then atomically move — a crash mid-write must never leave
            // a half-written account.json that fails to parse on next launch.
            Path tmp = Files.createTempFile(parent, "account", ".json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) {
                GSON.toJson(state, writer);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("[AccountStorage] Не удалось сохранить {}", path, e);
        }
    }

    static void clear(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("[AccountStorage] Не удалось удалить {}: {}", path, e.toString());
        }
    }

    private static final class UuidAdapter implements JsonSerializer<UUID>, JsonDeserializer<UUID> {
        @Override
        public JsonElement serialize(UUID src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.toString());
        }

        @Override
        public UUID deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return UUID.fromString(json.getAsString());
        }
    }
}
