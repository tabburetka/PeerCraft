package net.peercraft.rendezvous.account;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Real {@link MojangVerifier} — calls Mojang's session server over HTTPS. */
final class HttpMojangVerifier implements MojangVerifier {

    private static final String HAS_JOINED_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    @Override
    public Optional<MojangProfile> hasJoined(String username, String serverId) {
        try {
            String url = HAS_JOINED_URL + "?username=" + encode(username) + "&serverId=" + encode(serverId);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // Mojang replies 200 + profile JSON on success, 204/empty on "not joined" — anything
            // else (rate limit, outage, ...) is treated the same as "couldn't verify" rather than
            // crashing the login attempt.
            if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                return Optional.empty();
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            UUID id = parseUndashedUuid(json.get("id").getAsString());
            String name = json.get("name").getAsString();
            return Optional.of(new MojangProfile(id, name));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RuntimeException e) {
            // Malformed/unexpected JSON from Mojang — treat like any other verification failure
            // rather than propagating an unchecked exception into the packet-handling loop.
            return Optional.empty();
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Mojang's {@code id} field is a 32-hex-digit UUID with the dashes stripped. */
    private static UUID parseUndashedUuid(String undashed) {
        String dashed = undashed.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                "$1-$2-$3-$4-$5");
        return UUID.fromString(dashed);
    }
}
