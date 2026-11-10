package net.peercraft.rendezvous.relay;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.util.*;

/** Bounded, non-redirecting HTTPS requests. Provider bodies and bearer secrets never enter errors. */
public final class CloudflareTurnProvider implements TurnProvider {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String keyId, turnToken, analyticsToken, accountId, query;
    private final boolean exclusiveTurnAccount;
    private final String subscriptionId, billingToken;
    public CloudflareTurnProvider(RelayConfig config) throws IOException {
        keyId = env("CF_TURN_KEY_ID"); turnToken = env("CF_TURN_KEY_TOKEN");
        analyticsToken = env("CF_ANALYTICS_TOKEN"); accountId = env("CF_ACCOUNT_ID");
        exclusiveTurnAccount = config.exclusiveTurnAccount();
        subscriptionId = System.getenv("CF_REALTIME_SUBSCRIPTION_ID");
        billingToken = subscriptionId == null || subscriptionId.isBlank() ? null : env("CF_BILLING_TOKEN");
        if (billingToken != null && !subscriptionId.matches("[a-fA-F0-9]{32}"))
            throw new IOException("Invalid pinned Realtime subscription identifier");
        if (config.usageQuery() != null) query = Files.readString(config.usageQuery());
        else if (exclusiveTurnAccount) query = "query($accountId: String!, $dateFrom: Date!, $dateTo: Date!) { viewer { accounts(filter: {accountTag: $accountId}) { turn: callsTurnUsageAdaptiveGroups(limit: 2, filter: {date_geq: $dateFrom, date_leq: $dateTo}) { sum { egressBytes } } } } }";
        else throw new IOException("Full account TURN plus SFU usage query is required");
    }
    private static String env(String name) throws IOException {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IOException("Missing relay environment setting " + name);
        return value;
    }
    @Override public boolean usesBillingPeriodApi() { return billingToken != null; }
    @Override public BillingPeriod billingPeriod() throws IOException {
        if (!usesBillingPeriodApi()) return TurnProvider.super.billingPeriod();
        JsonObject response = request("https://api.cloudflare.com/client/v4/accounts/" + segment(accountId)
                + "/subscriptions/" + segment(subscriptionId), billingToken, null, 200);
        return parseBillingPeriod(response, subscriptionId);
    }
    static BillingPeriod parseBillingPeriod(JsonObject response, String expectedId) throws IOException {
        try {
            if (!response.get("success").getAsBoolean()) throw new IOException("Subscription unavailable");
            JsonElement errors = response.get("errors");
            if (errors != null && !errors.isJsonNull() && (!errors.isJsonArray() || !errors.getAsJsonArray().isEmpty()))
                throw new IOException("Subscription response contains errors");
            JsonObject subscription = response.getAsJsonObject("result");
            if (!expectedId.equals(subscription.get("id").getAsString())
                    || !"monthly".equals(subscription.get("frequency").getAsString())
                    || !Set.of("Paid", "Provisioned", "Trial").contains(subscription.get("state").getAsString()))
                throw new IOException("Pinned Realtime subscription is not active monthly billing");
            Instant start = Instant.parse(subscription.get("current_period_start").getAsString());
            Instant end = Instant.parse(subscription.get("current_period_end").getAsString());
            if (!start.isBefore(end)) throw new IOException("Invalid subscription period");
            return new BillingPeriod(start, end);
        } catch (RuntimeException invalid) { throw new IOException("Invalid subscription period response"); }
    }
    @Override public Credentials issue(int ttlSeconds, String identifier) throws IOException {
        long expiry = System.currentTimeMillis() + ttlSeconds * 1_000L;
        JsonObject body = new JsonObject(); body.addProperty("ttl", ttlSeconds);
        body.addProperty("customIdentifier", identifier);
        JsonObject response = post("https://rtc.live.cloudflare.com/v1/turn/keys/" + segment(keyId)
                + "/credentials/generate-ice-servers", turnToken, body, 201);
        return parseCredentials(response, expiry);
    }
    static Credentials parseCredentials(JsonObject response, long expiry) throws IOException {
        JsonElement servers = response.get("iceServers");
        JsonObject ice = servers != null && servers.isJsonObject() ? servers.getAsJsonObject() : null;
        if (ice == null && servers instanceof JsonArray array) for (JsonElement entry : array) {
            if (entry.isJsonObject() && entry.getAsJsonObject().has("username") && entry.getAsJsonObject().has("credential")) {
                ice = entry.getAsJsonObject(); break;
            }
        }
        if (ice == null) throw new IOException("Invalid TURN credential response");
        boolean udpSupported = false;
        for (JsonElement value : ice.getAsJsonArray("urls")) {
            String url = value.getAsString();
            if (url.equals("turn:turn.cloudflare.com:3478?transport=udp")
                    || url.equals("turn:turn.cloudflare.com:443?transport=udp")) udpSupported = true;
        }
        if (!udpSupported || ice.get("username").getAsString().length() > 512
                || ice.get("credential").getAsString().length() > 1024) throw new IOException("No supported Cloudflare TURN credentials");
        // The same credential works on both documented UDP ports, even when an older
        // generate-ice-servers response lists UDP 53 instead of the alternate UDP 443.
        List<String> urls = List.of("turn:turn.cloudflare.com:3478?transport=udp",
                "turn:turn.cloudflare.com:443?transport=udp");
        return new Credentials(urls, ice.get("username").getAsString(), ice.get("credential").getAsString(), expiry);
    }
    @Override public void revoke(String username) throws IOException {
        post("https://rtc.live.cloudflare.com/v1/turn/keys/" + segment(keyId) + "/credentials/"
                + segment(username) + "/revoke", turnToken, new JsonObject(), 204);
    }
    @Override public Usage usage(Instant start, Instant end) throws IOException {
        JsonObject variables = new JsonObject(); variables.addProperty("accountId", accountId);
        variables.addProperty("dateFrom", start.atZone(ZoneOffset.UTC).toLocalDate().toString());
        variables.addProperty("dateTo", end.atZone(ZoneOffset.UTC).toLocalDate().toString());
        variables.addProperty("datetimeStart", start.toString()); variables.addProperty("datetimeEnd", end.toString());
        JsonObject body = new JsonObject(); body.addProperty("query", query); body.add("variables", variables);
        JsonObject result = post("https://api.cloudflare.com/client/v4/graphql", analyticsToken, body, 200);
        return parseUsage(result, exclusiveTurnAccount);
    }
    static Usage parseUsage(JsonObject result, boolean exclusive) throws IOException {
        try {
            JsonElement errors = result.get("errors");
            if (errors != null && !errors.isJsonNull() && (!errors.isJsonArray() || !errors.getAsJsonArray().isEmpty()))
                throw new IOException("Account analytics returned errors");
            JsonArray accounts = result.getAsJsonObject("data").getAsJsonObject("viewer").getAsJsonArray("accounts");
            if (accounts.size() != 1) throw new IOException("Account analytics missing");
            JsonObject account = accounts.get(0).getAsJsonObject();
            long turn = aggregate(account, "turn");
            long sfu = exclusive ? 0 : aggregate(account, "sfu");
            return new Usage(turn, sfu, true);
        } catch (RuntimeException invalid) { throw new IOException("Invalid complete account analytics response"); }
    }
    private static long aggregate(JsonObject account, String alias) throws IOException {
        JsonArray rows = account.getAsJsonArray(alias);
        if (rows == null || rows.size() > 1) throw new IOException("Unaggregated or missing account usage");
        if (rows.isEmpty()) return 0;
        long bytes = rows.get(0).getAsJsonObject().getAsJsonObject("sum").get("egressBytes").getAsBigDecimal().longValueExact();
        if (bytes < 0) throw new IOException("Invalid account usage");
        return bytes;
    }
    private JsonObject post(String url, String token, JsonObject body, int status) throws IOException {
        return request(url, token, body, status);
    }
    private JsonObject request(String url, String token, JsonObject body, int status) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        HttpRequest request = body == null ? builder.GET().build()
                : builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                byte[] bytes = input.readNBytes(1_048_577);
                if (response.statusCode() != status || bytes.length > 1_048_576) throw new IOException("Cloudflare relay request failed");
                if (status == 204) return new JsonObject();
                return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Relay request interrupted"); }
        catch (RuntimeException malformed) { throw new IOException("Invalid Cloudflare relay response"); }
    }
    private static String segment(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
