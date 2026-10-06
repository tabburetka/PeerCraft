package net.peercraft.network.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** HTTPS control only. Never follows a redirect with the account's bearer token. */
public final class RelayBrokerClient {
    public interface Requests {
        JsonObject request(String method, String path, JsonObject body) throws IOException;
    }
    /** Injectable DNS/address boundary; production rejects private and local targets. */
    public interface Endpoints {
        InetSocketAddress turn(String host, int port) throws IOException;
        InetSocketAddress peer(String host, int port) throws IOException;
    }
    private static final Set<String> REASONS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "broker_unavailable", "broker_response_invalid", "broker_not_configured", "account_required", "credentials_invalid",
            "lease_invalid", "relay_disabled", "budget_exhausted", "analytics_unavailable", "billing_period_unverified",
            "unauthorized", "direct_checks_required", "match_not_authorized", "relay_capacity", "relay_rate_limited",
            "lease_not_authorized", "lease_unknown", "match_expired", "peer_not_ready", "generation_mismatch",
            "endpoint_mismatch", "rotation_busy", "provider_unavailable", "credential_expired", "credentials_expired",
            "lease_closed", "lease_expired", "journal_unavailable", "turn_unavailable", "turn_timeout",
            "credential_recovery_pending")));
    public static final class Failure extends IOException {
        public final String reason;
        public Failure(String reason) { super(safeReason(reason)); this.reason = safeReason(reason); }
    }
    public static final class Credentials {
        public final String username, password;
        public final long expiresAt;
        public final int bulkBytesPerSecond;
        public final List<InetSocketAddress> servers;
        private Credentials(JsonObject json, Endpoints endpoints) throws IOException {
            username = required(json, "username"); password = required(json, "password");
            expiresAt = json.get("expiresAt").getAsLong();
            bulkBytesPerSecond = json.has("bulkBytesPerSecond") ? json.get("bulkBytesPerSecond").getAsInt() : 2 * 1024 * 1024;
            if (bulkBytesPerSecond < 65536 || bulkBytesPerSecond > 2 * 1024 * 1024) throw new Failure("credentials_invalid");
            servers = new ArrayList<InetSocketAddress>();
            JsonArray urls = json.getAsJsonArray("urls");
            if (urls == null || urls.size() > 8) throw new Failure("credentials_invalid");
            for (JsonElement value : urls) {
                URI uri = URI.create(value.getAsString());
                if (!"turn".equals(uri.getScheme())) continue;
                String spec = uri.getSchemeSpecificPart();
                if (!spec.endsWith("?transport=udp")) continue;
                URI endpoint = URI.create("udp://" + spec.substring(0, spec.indexOf('?')));
                // The configured HTTPS broker selects the TURN service; never accept local targets.
                if (endpoint.getHost() == null) continue;
                int port = endpoint.getPort();
                if ((port == 3478 || port == 443) && endpoint.getUserInfo() == null && endpoint.getPath().isEmpty()
                        && endpoint.getQuery() == null && endpoint.getFragment() == null) {
                    InetSocketAddress address = endpoints.turn(endpoint.getHost(), port);
                    if (address == null || address.isUnresolved()) throw new Failure("credentials_invalid");
                    if (!servers.contains(address)) servers.add(address);
                }
            }
            if (servers.isEmpty() || expiresAt <= System.currentTimeMillis()) throw new Failure("credentials_invalid");
        }
    }
    public static final class Lease {
        public final String id, state, role;
        public final UUID linkId, attemptId;
        public final int generation, peerGeneration;
        public final byte[] sendKey, receiveKey;
        public final Credentials credentials;
        public final InetSocketAddress peerEndpoint;
        Lease(JsonObject json, Endpoints endpoints) throws IOException {
            try {
                id = required(json, "leaseId"); state = required(json, "state");
                UUID.fromString(id); role = required(json, "role");
                if (!"host".equals(role) && !"joiner".equals(role)) throw new Failure("lease_invalid");
                linkId = UUID.fromString(required(json, "linkId"));
                attemptId = UUID.fromString(required(json, "attemptId"));
                generation = json.get("generation").getAsInt();
                peerGeneration = json.has("peerGeneration") ? json.get("peerGeneration").getAsInt() : 0;
                sendKey = Base64.getDecoder().decode(required(json, "sendKey"));
                receiveKey = Base64.getDecoder().decode(required(json, "receiveKey"));
                if (generation < 1 || peerGeneration < 0 || sendKey.length != 16 || receiveKey.length != 16
                        || java.security.MessageDigest.isEqual(sendKey, receiveKey)) throw new Failure("lease_invalid");
                credentials = json.has("credentials") && !json.get("credentials").isJsonNull()
                        ? new Credentials(json.getAsJsonObject("credentials"), endpoints) : null;
                JsonElement peer = json.get("peerEndpoint");
                if (peer == null || peer.isJsonNull()) peerEndpoint = null;
                else {
                    JsonObject endpoint = peer.getAsJsonObject();
                    int port = endpoint.get("port").getAsInt();
                    if (port < 1 || port > 65535) throw new Failure("lease_invalid");
                    peerEndpoint = endpoints.peer(required(endpoint, "host"), port);
                    if (peerEndpoint == null || peerEndpoint.isUnresolved()) throw new Failure("lease_invalid");
                }
            } catch (IllegalArgumentException | IllegalStateException | NullPointerException e) {
                throw new Failure("lease_invalid");
            }
        }
    }

    private final Requests requests;
    private final Endpoints endpoints;
    public RelayBrokerClient(final String configuredUrl, final byte[] sessionToken) throws IOException {
        this.requests = new HttpRequests(configuredUrl, sessionToken);
        this.endpoints = productionEndpoints();
    }
    public RelayBrokerClient(Requests requests) { this(requests, productionEndpoints()); }
    public RelayBrokerClient(Requests requests, Endpoints endpoints) {
        if (requests == null || endpoints == null) throw new IllegalArgumentException("Missing broker dependencies");
        this.requests = requests; this.endpoints = endpoints;
    }

    public Lease create(long token, String room, UUID attempt, boolean host) throws IOException {
        JsonObject body = new JsonObject(); body.addProperty("pairToken", Long.toString(token));
        body.addProperty("roomCode", room); body.addProperty("attemptId", attempt.toString());
        body.addProperty("role", host ? "host" : "joiner"); body.addProperty("directChecksFailed", true);
        return new Lease(requests.request("POST", "/leases", body), endpoints);
    }
    public Lease state(String id) throws IOException { return new Lease(requests.request("GET", path(id), null), endpoints); }
    public Lease endpoint(String id, int generation, InetSocketAddress endpoint, boolean confirmed) throws IOException {
        JsonObject body = new JsonObject(); body.addProperty("host", endpoint.getAddress().getHostAddress());
        body.addProperty("port", endpoint.getPort()); body.addProperty("generation", generation);
        body.addProperty("confirmed", confirmed);
        return new Lease(requests.request("PUT", path(id) + "/endpoint", body), endpoints);
    }
    public Lease renew(String id) throws IOException {
        return new Lease(requests.request("POST", path(id) + "/renew", new JsonObject()), endpoints);
    }
    public Lease retain(String id, long offer) throws IOException { return retention(id, offer, "retain"); }
    public Lease release(String id, long offer) throws IOException { return retention(id, offer, "release"); }
    private Lease retention(String id, long offer, String action) throws IOException {
        JsonObject body = new JsonObject(); body.addProperty("offerId", Long.toString(offer));
        return new Lease(requests.request("POST", path(id) + "/" + action, body), endpoints);
    }
    public void close(String id) throws IOException { requests.request("DELETE", path(id), null); }
    private static String path(String id) throws Failure {
        try { return "/leases/" + UUID.fromString(id); }
        catch (IllegalArgumentException e) { throw new Failure("lease_invalid"); }
    }
    private static String required(JsonObject object, String field) throws Failure {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) throw new Failure("lease_invalid");
        String text = value.getAsString();
        if (text.isEmpty() || text.length() > 4096) throw new Failure("lease_invalid");
        return text;
    }
    private static String safeReason(String reason) { return REASONS.contains(reason) ? reason : "broker_unavailable"; }
    private static Endpoints productionEndpoints() {
        return new Endpoints() {
            @Override public InetSocketAddress turn(String host, int port) throws IOException {
                if (port != 3478 && port != 443) throw new Failure("credentials_invalid");
                InetAddress[] addresses = InetAddress.getAllByName(host);
                for (InetAddress address : addresses) {
                    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()
                            || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                            || (address.getAddress().length == 16 && (address.getAddress()[0] & 0xfe) == 0xfc)
                            || (address.getAddress().length == 4 && (address.getAddress()[0] & 255) == 100
                                && (address.getAddress()[1] & 255) >= 64 && (address.getAddress()[1] & 255) <= 127))
                        throw new Failure("credentials_invalid");
                }
                if (addresses.length == 0) throw new Failure("credentials_invalid");
                return new InetSocketAddress(addresses[0], port);
            }
            @Override public InetSocketAddress peer(String host, int port) throws IOException {
                // Broker allocation addresses are literals. Never perform arbitrary peer DNS lookups.
                if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") && !(host.contains(":") && host.matches("[0-9a-fA-F:]+")))
                    throw new Failure("lease_invalid");
                InetAddress address = InetAddress.getByName(host);
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()
                        || address.isSiteLocalAddress() || address.isLinkLocalAddress()) throw new Failure("lease_invalid");
                return new InetSocketAddress(address, port);
            }
        };
    }
    private static final class HttpRequests implements Requests {
        private final URL base;
        private final String bearer;
        HttpRequests(String configuredUrl, byte[] token) throws IOException {
            try {
                URI uri = URI.create(configuredUrl);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null) throw new Failure("broker_not_configured");
                String url = configuredUrl.replaceAll("/+$", "");
                base = new URL(url.endsWith("/v1/relay") ? url : url + "/v1/relay");
            } catch (IllegalArgumentException e) { throw new Failure("broker_not_configured"); }
            if (token == null || token.length != 16) throw new Failure("account_required");
            bearer = Base64.getEncoder().encodeToString(token);
        }
        @Override public JsonObject request(String method, String path, JsonObject body) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(base.toString() + path).openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
            connection.setRequestMethod(method); connection.setRequestProperty("Authorization", "Bearer " + bearer);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try {
                if (body != null) {
                    // The create contract includes the token in its body; all operations also authenticate the header.
                    JsonObject copy = new JsonParser().parse(body.toString()).getAsJsonObject();
                    if (path.equals("/leases")) copy.addProperty("sessionToken", bearer);
                    byte[] bytes = copy.toString().getBytes(StandardCharsets.UTF_8);
                    connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                    try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                }
                int status = connection.getResponseCode();
                if (status == 204) return new JsonObject();
                InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
                JsonObject response = new JsonObject();
                if (stream != null) try (InputStream in = stream) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[2048]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (bytes.size() + n > 65536) throw new Failure("broker_response_invalid");
                        bytes.write(buffer, 0, n);
                    }
                    if (bytes.size() != 0) response = new JsonParser().parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
                }
                if (status < 200 || status >= 300) {
                    String reason = response.has("error") ? response.get("error").getAsString() : "broker_unavailable";
                    // Never propagate arbitrary server text (which may include credentials) into game logs.
                    throw new Failure(reason);
                }
                return response;
            } catch (RuntimeException e) { throw new Failure("broker_response_invalid"); }
            finally { connection.disconnect(); }
        }
    }
}
