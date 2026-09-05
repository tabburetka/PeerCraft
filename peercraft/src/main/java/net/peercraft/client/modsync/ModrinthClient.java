package net.peercraft.client.modsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.peercraft.network.modsync.ModEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Talks to the Modrinth API for two things:
 * <ul>
 *   <li>a canonical download URL for a mod, by its jar's SHA-512
 *       ({@code GET /v2/version_file/{hash}?algorithm=sha512}); the joiner only ever downloads
 *       over HTTP from a {@code cdn.modrinth.com} URL it got back here, never one the host
 *       supplied;</li>
 *   <li>the mod's curated client/server classification
 *       ({@code GET /v2/projects?ids=[...]} → {@code client_side}/{@code server_side}), used to
 *       refine {@link ModEntry.Env} on the confirm screen — on NeoForge the jar metadata has no
 *       dependable side field, so this is how "AppleSkin is client-only, don't force it" is known.</li>
 * </ul>
 * Any non-200 / parse failure is swallowed and the caller falls back to its previous answer
 * (P2P transfer / the host's env guess).
 *
 * <p>Style reference: {@code rendezvous-server}'s {@code HttpMojangVerifier}.
 */
public final class ModrinthClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final String VERSION_FILE_API = "https://api.modrinth.com/v2/version_file/";
    private static final String PROJECTS_API = "https://api.modrinth.com/v2/projects?ids=";
    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private static final int PROJECTS_PER_CALL = 80;

    private final HttpClient http;
    private final String userAgent;

    public ModrinthClient(String modVersion) {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build();
        this.userAgent = "PeerCraft/" + modVersion + " (github.com/tabburetka/PeerCraft)";
    }

    /**
     * @param url        a {@code cdn.modrinth.com} download URL, or {@code ""} when the hash is on
     *                   Modrinth but its file URL isn't the CDN (still usable for {@link #projectId})
     * @param sha512Hex  the matched file's SHA-512
     * @param projectId  Modrinth project id — feed to {@link #projectSides}
     */
    public record Resolved(String url, String sha512Hex, long size, String fileName, String projectId) {
        public boolean hasDownloadUrl() {
            return url != null && !url.isEmpty();
        }
    }

    /** Looks {@code entry}'s jar up by hash; empty when Modrinth doesn't have it or anything goes wrong. */
    public Optional<Resolved> resolve(ModEntry entry) {
        String hashHex = entry.sha512Hex();
        if (hashHex.length() != 128) {
            return Optional.empty();
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(VERSION_FILE_API + hashHex + "?algorithm=sha512"))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Optional.empty();
            }
            return parse(resp.body(), hashHex);
        } catch (Exception e) {
            LOGGER.debug("[ModSync] Modrinth-поиск для {} не удался: {}", entry.id(), e.toString());
            return Optional.empty();
        }
    }

    private Optional<Resolved> parse(String body, String wantHashHex) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject version = root.getAsJsonObject();
        String projectId = version.has("project_id") ? version.get("project_id").getAsString() : "";
        JsonElement filesEl = version.get("files");
        if (filesEl == null || !filesEl.isJsonArray()) {
            return Optional.empty();
        }
        for (JsonElement fe : filesEl.getAsJsonArray()) {
            if (!fe.isJsonObject()) {
                continue;
            }
            JsonObject f = fe.getAsJsonObject();
            JsonObject hashes = f.has("hashes") && f.get("hashes").isJsonObject() ? f.getAsJsonObject("hashes") : null;
            String sha = hashes != null && hashes.has("sha512") ? hashes.get("sha512").getAsString() : "";
            if (!sha.equalsIgnoreCase(wantHashHex)) {
                continue;
            }
            String rawUrl = f.has("url") ? f.get("url").getAsString() : "";
            String url = rawUrl.startsWith("https://cdn.modrinth.com/") ? rawUrl : "";
            long size = f.has("size") ? f.get("size").getAsLong() : 0L;
            String fileName = f.has("filename") ? f.get("filename").getAsString() : "";
            return Optional.of(new Resolved(url, sha, size, fileName, projectId));
        }
        return Optional.empty();
    }

    /**
     * Bulk-fetches {@code client_side}/{@code server_side} for the given project ids and maps each
     * to a {@link ModEntry.Env}. Only projects with a definitive verdict are in the result —
     * anything {@code unknown} is left out so the caller keeps whatever it had.
     */
    public Map<String, ModEntry.Env> projectSides(Collection<String> projectIds) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : projectIds) {
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        Map<String, ModEntry.Env> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        List<String> batch = new ArrayList<>(ids);
        for (int from = 0; from < batch.size(); from += PROJECTS_PER_CALL) {
            List<String> slice = batch.subList(from, Math.min(from + PROJECTS_PER_CALL, batch.size()));
            try {
                fetchSideBatch(slice, out);
            } catch (Exception e) {
                LOGGER.debug("[ModSync] Modrinth projects-запрос не удался: {}", e.toString());
            }
        }
        return out;
    }

    private void fetchSideBatch(List<String> ids, Map<String, ModEntry.Env> out) throws Exception {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            arr.append('"').append(ids.get(i)).append('"');
        }
        arr.append(']');
        URI uri = URI.create(PROJECTS_API + URLEncoder.encode(arr.toString(), StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .timeout(TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return;
        }
        JsonElement root = JsonParser.parseString(resp.body());
        if (!root.isJsonArray()) {
            return;
        }
        for (JsonElement pe : root.getAsJsonArray()) {
            if (!pe.isJsonObject()) {
                continue;
            }
            JsonObject p = pe.getAsJsonObject();
            String id = p.has("id") ? p.get("id").getAsString() : "";
            if (id.isEmpty()) {
                continue;
            }
            String clientSide = p.has("client_side") ? p.get("client_side").getAsString() : "unknown";
            String serverSide = p.has("server_side") ? p.get("server_side").getAsString() : "unknown";
            sideToEnv(clientSide, serverSide).ifPresent(env -> out.put(id, env));
        }
    }

    /**
     * Modrinth {@code client_side}/{@code server_side} ∈ {required, optional, unsupported, unknown}.
     * "Can the joiner skip this and still get into the host's world?" — yes unless the server
     * genuinely requires it.
     */
    static Optional<ModEntry.Env> sideToEnv(String clientSide, String serverSide) {
        boolean serverRequired = "required".equalsIgnoreCase(serverSide);
        boolean serverUnknown = serverSide == null || "unknown".equalsIgnoreCase(serverSide);
        boolean clientUnsupported = "unsupported".equalsIgnoreCase(clientSide);

        if (serverRequired) {
            return Optional.of(clientUnsupported ? ModEntry.Env.SERVER : ModEntry.Env.BOTH);
        }
        if (serverUnknown) {
            return Optional.empty(); // no dependable verdict — leave the caller's guess alone
        }
        return Optional.of(ModEntry.Env.CLIENT); // server_side is optional/unsupported → safe to skip
    }
}
