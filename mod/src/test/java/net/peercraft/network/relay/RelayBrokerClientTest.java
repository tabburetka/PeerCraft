package net.peercraft.network.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RelayBrokerClientTest {
    @Test void onlyConfiguredHttpsOriginCanReceiveAccountToken() throws Exception {
        byte[] token = new byte[16];
        for (String url : new String[] { "http://broker.test", "https://user@broker.test", "https://broker.test?secret=1", "https://broker.test#secret" })
            assertEquals("broker_not_configured", assertThrows(RelayBrokerClient.Failure.class, () -> new RelayBrokerClient(url, token)).reason);
        assertEquals("account_required", assertThrows(RelayBrokerClient.Failure.class, () -> new RelayBrokerClient("https://broker.test", new byte[15])).reason);
        assertNotNull(new RelayBrokerClient("https://broker.test/v1/relay", token));
    }
    @Test void arbitraryErrorTextAndLowercaseSecretsCannotReachGameLogs() {
        for (String secret : new String[] { "secret_password", "username=secret", "password", "<token>", "unknown_error" }) {
            RelayBrokerClient.Failure failure = new RelayBrokerClient.Failure(secret);
            assertEquals("broker_unavailable", failure.reason); assertEquals("broker_unavailable", failure.getMessage());
        }
        assertEquals("budget_exhausted", new RelayBrokerClient.Failure("budget_exhausted").reason);
    }
    @Test void credentialUrlsAllowBrokerSelectedCoturnButRequireUdp3478Or443() throws Exception {
        List<Integer> ports = new ArrayList<Integer>();
        RelayBrokerClient.Endpoints endpoints = new RelayBrokerClient.Endpoints() {
            public InetSocketAddress turn(String host, int port) throws java.io.IOException {
                assertTrue(host.equals("turn.cloudflare.com") || host.equals("relay.peercraft.test")); ports.add(port); return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
            }
            public InetSocketAddress peer(String host, int port) { fail("No peer is present"); return null; }
        };
        JsonObject json = lease(UUID.randomUUID(), "host"); JsonObject credentials = credentials();
        JsonArray urls = new JsonArray();
        for (String url : new String[] { "turn:relay.peercraft.test:5349?transport=udp", "turn:turn.cloudflare.com:5349?transport=udp",
                "turn:turn.cloudflare.com:3478?transport=tcp", "turns:turn.cloudflare.com:443?transport=udp",
                "turn:turn.cloudflare.com:3478?transport=udp", "turn:turn.cloudflare.com:443?transport=udp" }) urls.add(url);
        credentials.add("urls", urls); json.add("credentials", credentials);
        RelayBrokerClient client = new RelayBrokerClient((method, path, body) -> json, endpoints);
        assertEquals(2, client.create(1, "ABCDEF", UUID.fromString(json.get("attemptId").getAsString()), true).credentials.servers.size());
        assertEquals(java.util.Arrays.asList(3478, 443), ports);
        urls = new JsonArray(); urls.add("turn:relay.peercraft.test:3478?transport=udp"); credentials.add("urls", urls);
        assertEquals(1, client.create(1, "ABCDEF", UUID.randomUUID(), true).credentials.servers.size());
        credentials.addProperty("bulkBytesPerSecond",524288);
        assertEquals(524288,client.create(1,"ABCDEF",UUID.randomUUID(),true).credentials.bulkBytesPerSecond);
        credentials.addProperty("bulkBytesPerSecond",0);
        assertEquals("credentials_invalid",assertThrows(RelayBrokerClient.Failure.class,
                () -> client.create(1,"ABCDEF",UUID.randomUUID(),true)).reason);
    }
    @Test void productionResolverRejectsLocalAndPrivateTurnTargets() {
        JsonObject json = lease(UUID.randomUUID(), "host"), credentials = credentials();
        json.add("credentials", credentials);
        RelayBrokerClient client = new RelayBrokerClient((method, path, body) -> json);
        for (String host : new String[] { "127.0.0.1", "192.168.68.110", "10.0.0.1", "169.254.169.254", "100.64.0.1", "[::1]", "[fd00::1]" }) {
            JsonArray urls = new JsonArray(); urls.add("turn:" + host + ":3478?transport=udp"); credentials.add("urls", urls);
            assertEquals("credentials_invalid", assertThrows(RelayBrokerClient.Failure.class,
                    () -> client.state(json.get("leaseId").getAsString())).reason);
        }
    }
    @Test void leaseRequiresUuidRoleDirectionalKeysAndPublicLiteralEndpoint() throws Exception {
        JsonObject json = lease(UUID.randomUUID(), "host");
        RelayBrokerClient client = new RelayBrokerClient((method, path, body) -> json);
        json.addProperty("role", "other"); assertThrows(RelayBrokerClient.Failure.class, () -> client.state(json.get("leaseId").getAsString()));
        json.addProperty("role", "host"); json.addProperty("receiveKey", json.get("sendKey").getAsString());
        assertThrows(RelayBrokerClient.Failure.class, () -> client.state(json.get("leaseId").getAsString()));
        json.addProperty("receiveKey", key(9));
        JsonObject endpoint = new JsonObject(); endpoint.addProperty("port", 34567); json.add("peerEndpoint", endpoint);
        for (String host : new String[] { "127.0.0.1", "192.168.0.5", "::1", "evil.test" }) {
            endpoint.addProperty("host", host); assertEquals("lease_invalid", assertThrows(RelayBrokerClient.Failure.class,
                    () -> client.state(json.get("leaseId").getAsString())).reason);
        }
        endpoint.addProperty("host", "203.0.113.22"); assertNotNull(client.state(json.get("leaseId").getAsString()).peerEndpoint);
    }
    static JsonObject lease(UUID attempt, String role) {
        JsonObject json = new JsonObject(); json.addProperty("leaseId", UUID.randomUUID().toString());
        json.addProperty("linkId", UUID.randomUUID().toString()); json.addProperty("attemptId", attempt.toString());
        json.addProperty("role", role); json.addProperty("state", "waiting"); json.addProperty("generation", 1);
        json.addProperty("sendKey", key(3)); json.addProperty("receiveKey", key(9)); return json;
    }
    static JsonObject credentials() {
        JsonObject json = new JsonObject(); json.addProperty("username", "test-user"); json.addProperty("password", "password");
        json.addProperty("expiresAt", System.currentTimeMillis() + 900000);
        JsonArray urls = new JsonArray(); urls.add("turn:turn.cloudflare.com:3478?transport=udp"); json.add("urls", urls); return json;
    }
    static String key(int marker) { byte[] bytes = new byte[16]; java.util.Arrays.fill(bytes, (byte) marker); return Base64.getEncoder().encodeToString(bytes); }
}
