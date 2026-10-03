package net.peercraft.network.handoff;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.peercraft.platform.services.PlatformMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/** Classifies whole client-side JARs before the hosting manifest is compared. Java 8 compatible. */
public final class HandoffClientOnlyMods {
    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final String API = "https://api.modrinth.com/v2/";
    private static final Set<String> OFFLINE_CLIENT_ONLY = Collections.singleton("appleskin");
    private static final ConcurrentMap<String, Boolean> SIDES = new ConcurrentHashMap<>();

    interface SideLookup { boolean optionalForServer(Path jar) throws IOException; }
    private HandoffClientOnlyMods() { }

    public static Set<String> classify(Collection<PlatformMod> installed) {
        return classify(installed, HandoffClientOnlyMods::modrinthOptionalForServer);
    }

    static Set<String> classify(Collection<PlatformMod> installed, SideLookup lookup) {
        Set<String> clientOnly = ConcurrentHashMap.newKeySet();
        List<Callable<Void>> lookups = new ArrayList<>();
        for (PlatformMod mod : installed) {
            if (mod.id() == null || mod.id().isEmpty() || mod.parentId() != null && !mod.parentId().isEmpty()) continue;
            if ("client".equalsIgnoreCase(mod.environment()) || OFFLINE_CLIENT_ONLY.contains(mod.id())) {
                clientOnly.add(mod.id());
            } else if (mod.jarPath() != null && !"peercraft".equals(mod.id())) {
                lookups.add(() -> {
                    try { if (lookup.optionalForServer(mod.jarPath())) clientOnly.add(mod.id()); }
                    catch (IOException failure) { LOGGER.debug("[Handoff] No client-side verdict for {}: {}", mod.id(), failure.toString()); }
                    return null;
                });
            }
        }
        if (!lookups.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, lookups.size()), r -> {
                Thread thread = new Thread(r, "PeerCraft-Handoff-Mod-Side"); thread.setDaemon(true); return thread;
            });
            try { pool.invokeAll(lookups, 20, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { pool.shutdownNow(); }
        }
        if (!clientOnly.isEmpty()) LOGGER.info("[Handoff] Client-side mods omitted from hosting manifest: {}", clientOnly);
        return Collections.unmodifiableSet(new HashSet<>(clientOnly));
    }

    private static boolean modrinthOptionalForServer(Path jar) throws IOException {
        return modrinthOptionalForServer(jar, API);
    }

    static boolean modrinthOptionalForServer(Path jar, String api) throws IOException {
        byte[] digest = HostExecutionManifest.hash(jar);
        StringBuilder hex = new StringBuilder(128);
        for (byte value : digest) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        String key = api + hex; Boolean cached = SIDES.get(key);
        if (cached != null) return cached;
        JsonObject version = get(api + "version_file/" + hex + "?algorithm=sha512");
        String project = string(version, "project_id");
        if (!project.matches("[A-Za-z0-9_-]{1,64}")) return false;
        String side = string(get(api + "project/" + project), "server_side");
        if (!"required".equalsIgnoreCase(side) && !"optional".equalsIgnoreCase(side)
                && !"unsupported".equalsIgnoreCase(side)) return false;
        boolean optional = !"required".equalsIgnoreCase(side);
        SIDES.putIfAbsent(key, optional);
        return optional;
    }

    private static JsonObject get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(4000); connection.setReadTimeout(4000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "PeerCraft/handoff (github.com/tabburetka/PeerCraft)");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Modrinth HTTP " + connection.getResponseCode());
            StringBuilder body = new StringBuilder(); char[] chars = new char[4096]; int count;
            try (InputStream in = connection.getInputStream();
                 java.io.Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                while ((count = reader.read(chars)) >= 0) {
                    if (count == 0) continue;
                    if (body.length() > 1024 * 1024 - count) throw new IOException("Oversized Modrinth reply");
                    body.append(chars, 0, count);
                }
            }
            JsonElement parsed = new JsonParser().parse(body.toString());
            if (!parsed.isJsonObject()) throw new IOException("Invalid Modrinth reply");
            return parsed.getAsJsonObject();
        } catch (RuntimeException invalid) { throw new IOException("Invalid Modrinth reply", invalid); }
        finally { connection.disconnect(); }
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
