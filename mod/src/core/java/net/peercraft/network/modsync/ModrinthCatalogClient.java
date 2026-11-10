package net.peercraft.network.modsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/** Java 8 exact-file lookup. Publication is not a safety verdict. */
public final class ModrinthCatalogClient {
    private static final String API = "https://api.modrinth.com/v2/version_file/";
    private static final int TIMEOUT_MILLIS = 8000;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private ModrinthCatalogClient() {
    }

    public static ModSyncPlan.PlannedMod[] lookupAll(List<ModEntry> entries, IntConsumer progress) {
        ModSyncPlan.PlannedMod[] statuses = new ModSyncPlan.PlannedMod[entries.size()];
        if (entries.isEmpty()) return statuses;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, entries.size()), r -> {
            Thread thread = new Thread(r, "PeerCraft-Modrinth-Catalog");
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger done = new AtomicInteger();
        try {
            CompletableFuture<?>[] tasks = new CompletableFuture<?>[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                final int index = i;
                tasks[i] = CompletableFuture.runAsync(() -> {
                    statuses[index] = lookup(entries.get(index));
                    progress.accept(done.incrementAndGet());
                }, pool);
            }
            CompletableFuture.allOf(tasks).join();
        } finally {
            pool.shutdownNow();
        }
        return statuses;
    }

    public static ModSyncPlan.CatalogStatus check(ModEntry entry) { return lookup(entry).catalogStatus(); }

    public static ModSyncPlan.PlannedMod lookup(ModEntry entry) {
        String hash = entry.sha512Hex();
        if (hash.length() != 128) return ModSyncPlan.PlannedMod.p2p(entry);
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(API + hash + "?algorithm=sha512").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "PeerCraft (github.com/tabburetka/PeerCraft)");
            int code = connection.getResponseCode();
            if (code == 404) return ModSyncPlan.PlannedMod.p2p(entry).withCatalogStatus(ModSyncPlan.CatalogStatus.NOT_FOUND);
            if (code != 200) return ModSyncPlan.PlannedMod.p2p(entry);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    if (bytes.size() + count > MAX_RESPONSE_BYTES) return ModSyncPlan.PlannedMod.p2p(entry);
                    bytes.write(chunk, 0, count);
                }
                return parsePlan(new String(bytes.toByteArray(), StandardCharsets.UTF_8), entry);
            }
        } catch (Exception ignored) {
            return ModSyncPlan.PlannedMod.p2p(entry);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static ModSyncPlan.PlannedMod parsePlan(String body, ModEntry entry) {
        ModSyncPlan.CatalogStatus status = parse(body, entry.sha512Hex());
        ModSyncPlan.PlannedMod fallback = ModSyncPlan.PlannedMod.p2p(entry).withCatalogStatus(status);
        if (status != ModSyncPlan.CatalogStatus.PUBLISHED) return fallback;
        try {
            JsonArray files = new JsonParser().parse(body).getAsJsonObject().getAsJsonArray("files");
            for (JsonElement element : files) {
                JsonObject file = element.getAsJsonObject();
                if (entry.sha512Hex().equalsIgnoreCase(file.getAsJsonObject("hashes").get("sha512").getAsString())
                        && file.has("url") && ModrinthDownloader.isCdnUrl(file.get("url").getAsString())) {
                    return new ModSyncPlan.PlannedMod(entry, ModSyncPlan.Source.HTTP,
                            file.get("url").getAsString(), status);
                }
            }
        } catch (RuntimeException ignored) { }
        return fallback;
    }

    static ModSyncPlan.CatalogStatus parse(String body, String hash) {
        try {
            JsonElement root = new JsonParser().parse(body);
            if (!root.isJsonObject()) return ModSyncPlan.CatalogStatus.UNAVAILABLE;
            JsonObject version = root.getAsJsonObject();
            JsonArray files = version.getAsJsonArray("files");
            if (files == null) return ModSyncPlan.CatalogStatus.UNAVAILABLE;
            for (JsonElement fileElement : files) {
                if (!fileElement.isJsonObject()) continue;
                JsonObject file = fileElement.getAsJsonObject();
                JsonObject hashes = file.getAsJsonObject("hashes");
                if (hashes == null || !hashes.has("sha512")) continue;
                if (hash.equalsIgnoreCase(hashes.get("sha512").getAsString())) {
                    return version.has("status") && "listed".equals(version.get("status").getAsString())
                            ? ModSyncPlan.CatalogStatus.PUBLISHED : ModSyncPlan.CatalogStatus.NOT_FOUND;
                }
            }
        } catch (RuntimeException ignored) {
            return ModSyncPlan.CatalogStatus.UNAVAILABLE;
        }
        return ModSyncPlan.CatalogStatus.UNAVAILABLE;
    }
}
