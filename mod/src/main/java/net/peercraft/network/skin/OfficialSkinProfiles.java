package net.peercraft.network.skin;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Official UUID lookup only. Never resolves a nickname or changes player identity. */
public final class OfficialSkinProfiles {
    private static final int MAX_RESPONSE = 65536;
    private static final Map<UUID, Entry> CACHE = new LinkedHashMap<UUID, Entry>();
    private static final ExecutorService WORKERS = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(64), runnable -> {
                Thread thread = new Thread(runnable, "PeerCraft-OfficialSkins");
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private OfficialSkinProfiles() { }
    public static final class Texture {
        public final String value, signature;
        Texture(String value, String signature) { this.value = value; this.signature = signature; }
    }
    private static final class Entry {
        final CompletableFuture<Optional<Texture>> result = new CompletableFuture<Optional<Texture>>();
        long expires = Long.MAX_VALUE;
    }
    public static synchronized CompletableFuture<Optional<Texture>> lookup(UUID id) {
        // Vanilla nickname hashes are v3; official Minecraft account UUIDs are v4.
        if (id == null || id.version() != 4) return CompletableFuture.completedFuture(Optional.empty());
        long now = System.nanoTime();
        Entry previous = CACHE.get(id);
        if (previous != null && now < previous.expires) return previous.result;
        CACHE.entrySet().removeIf(entry -> now >= entry.getValue().expires);
        if (CACHE.size() >= 512) return CompletableFuture.completedFuture(Optional.empty());
        Entry entry = new Entry(); CACHE.put(id, entry);
        try {
            WORKERS.execute(() -> {
                Optional<Texture> texture;
                try { texture = fetch(id); } catch (Exception unavailable) { texture = Optional.empty(); }
                synchronized (OfficialSkinProfiles.class) {
                    entry.expires = System.nanoTime() + TimeUnit.MINUTES.toNanos(texture.isPresent() ? 15 : 1);
                }
                entry.result.complete(texture);
            });
        } catch (RejectedExecutionException busy) {
            CACHE.remove(id); entry.result.complete(Optional.empty());
        }
        return entry.result;
    }
    private static Optional<Texture> fetch(UUID id) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("https://sessionserver.mojang.com/session/minecraft/profile/"
                + id.toString().replace("-", "") + "?unsigned=false").openConnection();
        connection.setConnectTimeout(2500); connection.setReadTimeout(2500);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) return Optional.empty();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream stream = connection.getInputStream()) {
                byte[] buffer = new byte[2048]; int length;
                while ((length = stream.read(buffer)) != -1) {
                    if (bytes.size() + length > MAX_RESPONSE) return Optional.empty();
                    bytes.write(buffer, 0, length);
                }
            }
            return parse(id, new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }
    static Optional<Texture> parse(UUID id, String json) {
        try {
            JsonObject profile = new JsonParser().parse(json).getAsJsonObject();
            String expected = id.toString().replace("-", "");
            if (!expected.equalsIgnoreCase(profile.get("id").getAsString())) return Optional.empty();
            for (JsonElement element : profile.getAsJsonArray("properties")) {
                JsonObject property = element.getAsJsonObject();
                if (!"textures".equals(property.get("name").getAsString()) || !property.has("signature")) continue;
                String value = property.get("value").getAsString(), signature = property.get("signature").getAsString();
                JsonObject payload = new JsonParser().parse(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)).getAsJsonObject();
                if (!expected.equalsIgnoreCase(payload.get("profileId").getAsString()) || signature.isEmpty()) return Optional.empty();
                return Optional.of(new Texture(value, signature));
            }
        } catch (RuntimeException invalid) { /* Missing/malformed profiles use vanilla defaults. */ }
        return Optional.empty();
    }
}
