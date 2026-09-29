package net.peercraft.rendezvous;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AnalyticsStoreTest {
    @TempDir Path temp;

    @SuppressWarnings("unchecked")
    @Test
    void countsUniqueVisitorsAndAccountsAcrossDaysAndPersistsWithoutRawIdentifiers() throws Exception {
        AtomicLong clock = new AtomicLong(Instant.parse("2026-09-29T12:00:00Z").toEpochMilli());
        AnalyticsStore store = new AnalyticsStore(temp, clock::get);
        InetAddress first = InetAddress.getByName("198.51.100.10");
        InetAddress second = InetAddress.getByName("198.51.100.11");
        UUID account = UUID.randomUUID();
        store.request("register", first, 20);
        store.request("register", first, 20);
        store.request("join", second, 10);
        store.account(account);
        store.event("room.created");
        clock.set(Instant.parse("2026-09-30T12:00:00Z").toEpochMilli());
        store.request("join", first, 10);
        store.account(account);
        store.flush();

        String persisted = Files.readString(temp.resolve("analytics.json"));
        assertFalse(persisted.contains("198.51.100"));
        assertFalse(persisted.contains(account.toString()));
        AnalyticsStore loaded = new AnalyticsStore(temp, clock::get);
        Map<String, Object> snapshot = loaded.snapshot();
        List<Map<String, Object>> days = (List<Map<String, Object>>) snapshot.get("days");
        List<Map<String, Object>> months = (List<Map<String, Object>>) snapshot.get("months");
        assertEquals(2, days.size());
        assertEquals(2, days.get(0).get("visitors"));
        assertEquals(1, days.get(1).get("visitors"));
        assertEquals(2, months.get(0).get("visitors"));
        assertEquals(1, months.get(0).get("accounts"));
        assertEquals(4L, ((Map<String, Long>) months.get(0).get("counts")).get("requests"));
    }

    @Test
    void dashboardServesLocalPageAndReadOnlyJson() throws Exception {
        AtomicLong clock = new AtomicLong(Instant.parse("2026-09-29T12:00:00Z").toEpochMilli());
        AnalyticsStore store = new AnalyticsStore(temp, clock::get);
        store.request("register", InetAddress.getLoopbackAddress(), 10);
        AnalyticsDashboard dashboard = new AnalyticsDashboard(store, new RoomRegistry(clock::get), "127.0.0.1", 0);
        dashboard.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + dashboard.port();
            HttpResponse<String> page = client.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> api = client.send(HttpRequest.newBuilder(URI.create(base + "/api/analytics")).build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> blocked = client.send(HttpRequest.newBuilder(URI.create(base + "/api/analytics")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("Статистика сервера"));
            assertEquals(200, api.statusCode());
            assertTrue(api.body().contains("\"visitors\":1"));
            assertEquals(405, blocked.statusCode());
        } finally {
            dashboard.stop();
        }
    }
}
