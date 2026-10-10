package net.peercraft.rendezvous.account;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory account bookkeeping with JSON persistence (via Gson) to disk — this is the point
 * where the rendezvous server stops being purely stateless (see FOR_CLAUDE.txt/README's old
 * "no accounts" disclaimer, now superseded). Dirty-flag + periodic flush, same idea as
 * {@code RendezvousServer}'s existing {@code sweeper} for room expiry, plus a shutdown hook so
 * a clean server stop doesn't lose the last few seconds of changes.
 */
final class AccountStore {

    private final Path dataFile;
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(UUID.class, new UuidAdapter())
            .create();
    private final Map<UUID, Account> byId = new ConcurrentHashMap<>();
    private final Map<String, UUID> idByFriendCode = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    AccountStore(Path dataFile) {
        this.dataFile = dataFile;
        load();
    }

    synchronized void add(Account account) {
        byId.put(account.accountId, account);
        idByFriendCode.put(account.friendCode, account.accountId);
        markDirty();
    }

    /** Never acknowledge a new UUID before the account can survive process loss. */
    synchronized void addDurably(Account account) throws IOException {
        if (byId.containsKey(account.accountId) || idByFriendCode.containsKey(account.friendCode))
            throw new IOException("Account identity collision");
        add(account);
        try { saveNow(); }
        catch (IOException | RuntimeException failure) {
            byId.remove(account.accountId); idByFriendCode.remove(account.friendCode);
            throw new IOException("Cannot persist new account", failure);
        }
    }

    Optional<Account> byId(UUID accountId) {
        return Optional.ofNullable(byId.get(accountId));
    }

    Optional<Account> byFriendCode(String friendCode) {
        UUID id = idByFriendCode.get(friendCode);
        return id == null ? Optional.empty() : byId(id);
    }

    boolean friendCodeTaken(String friendCode) {
        return idByFriendCode.containsKey(friendCode);
    }

    /** Live view (weakly-consistent iteration, never throws ConcurrentModificationException) — used by search. */
    java.util.Collection<Account> all() {
        return byId.values();
    }

    /** Call after mutating a returned {@link Account} in place (e.g. rename, rotate remember token). */
    void markDirty() {
        dirty.set(true);
    }

    /** Idempotent — only actually writes if something changed since the last successful save. */
    void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
        }
    }

    int accountCount() {
        return byId.size();
    }

    private synchronized void save() {
        try {
            saveNow();
        } catch (IOException | RuntimeException e) {
            // RuntimeException (not just IOException) matters here: this runs on a
            // ScheduledExecutorService tick, which silently stops rescheduling forever if a
            // task ever throws uncaught — an unexpected Gson serialization hiccup must not
            // permanently kill all future persistence for the rest of the server's uptime.
            System.err.println("[AccountStore] Failed to save accounts to " + dataFile + ": " + e);
            dirty.set(true); // retry on the next scheduled tick rather than silently losing the change
        }
    }

    /** Security-sensitive changes must reach disk before recovery reports success. */
    synchronized void saveNow() throws IOException {
        Path parent = dataFile.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, "accounts", ".json.tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) {
                gson.toJson(new ArrayList<>(byId.values()), writer);
            }
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void load() {
        if (!Files.exists(dataFile)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            Type listType = new TypeToken<List<Account>>() {
            }.getType();
            List<Account> loaded = gson.fromJson(reader, listType);
            if (loaded == null) throw new IOException("Empty account database");
            java.util.Set<String> emails = new java.util.HashSet<>();
            if (loaded != null) {
                for (Account account : loaded) {
                    if (account == null || account.accountId == null || account.friendCode == null
                            || account.displayName == null || byId.containsKey(account.accountId)
                            || idByFriendCode.containsKey(account.friendCode))
                        throw new IOException("Invalid or duplicate account record");
                    if (account.verifiedEmail != null) {
                        account.verifiedEmail = EmailRecoveryService.normalize(account.verifiedEmail);
                        if (account.licensed || !emails.add(account.verifiedEmail))
                            throw new IOException("Invalid recovery email ownership");
                    }
                    account.normalizeCollectionsAfterDeserialization();
                    byId.put(account.accountId, account);
                    idByFriendCode.put(account.friendCode, account.accountId);
                }
            }
            System.out.println("[AccountStore] Loaded " + byId.size() + " account(s) from " + dataFile);
        } catch (IOException | RuntimeException e) {
            // Starting empty would let later registrations replace the only account database.
            // Refuse startup and preserve the file for explicit restoration instead.
            throw new IllegalStateException("Cannot load account database; existing file was preserved");
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
