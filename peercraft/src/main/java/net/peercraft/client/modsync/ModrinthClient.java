package net.peercraft.client.modsync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.peercraft.network.modsync.ModEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Resolves a canonical download URL for a mod by looking its jar's SHA-512 up on Modrinth
 * ({@code GET /v2/version_file/{hash}?algorithm=sha512}). The joiner only ever downloads
 * over HTTP from a {@code cdn.modrinth.com} URL it got back here — never from a URL the host
 * supplied. Any non-200 / parse failure returns empty and the caller falls back to P2P.
 *
 * <p>Style reference: {@code rendezvous-server}'s {@code HttpMojangVerifier} — this is the
 * mod's first use of {@link HttpClient}.
 */
public final class ModrinthClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final String API = "https://api.modrinth.com/v2/version_file/";
    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    private final HttpClient http;
    private final String userAgent;

    public ModrinthClient(String modVersion) {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build();
        this.userAgent = "PeerCraft/" + modVersion + " (github.com/tabburetka/PeerCraft)";
    }

    public record Resolved(String url, String sha512Hex, long size, String fileName) {
    }

    /** Looks {@code entry}'s jar up by hash; empty when Modrinth doesn't have it or anything goes wrong. */
    public Optional<Resolved> resolve(ModEntry entry) {
        String hashHex = entry.sha512Hex();
        if (hashHex.length() != 128) {
            return Optional.empty();
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + hashHex + "?algorithm=sha512"))
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
        JsonElement filesEl = root.getAsJsonObject().get("files");
        if (filesEl == null || !filesEl.isJsonArray()) {
            return Optional.empty();
        }
        JsonArray files = filesEl.getAsJsonArray();
        for (JsonElement fe : files) {
            if (!fe.isJsonObject()) {
                continue;
            }
            JsonObject f = fe.getAsJsonObject();
            JsonObject hashes = f.has("hashes") && f.get("hashes").isJsonObject() ? f.getAsJsonObject("hashes") : null;
            String sha = hashes != null && hashes.has("sha512") ? hashes.get("sha512").getAsString() : "";
            if (!sha.equalsIgnoreCase(wantHashHex)) {
                continue;
            }
            String url = f.has("url") ? f.get("url").getAsString() : "";
            if (!url.startsWith("https://cdn.modrinth.com/")) {
                return Optional.empty();
            }
            long size = f.has("size") ? f.get("size").getAsLong() : 0L;
            String fileName = f.has("filename") ? f.get("filename").getAsString() : "";
            return Optional.of(new Resolved(url, sha, size, fileName));
        }
        return Optional.empty();
    }
}
