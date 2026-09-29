package net.peercraft.rendezvous;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only dashboard served by the rendezvous process; it has no public UDP protocol changes. */
final class AnalyticsDashboard {
    private final HttpServer server;
    private final AnalyticsStore analytics;
    private final RoomRegistry rooms;
    private static final Gson GSON = new Gson();

    AnalyticsDashboard(AnalyticsStore analytics, RoomRegistry rooms, String host, int port) throws IOException {
        this.analytics = analytics;
        this.rooms = rooms;
        server = HttpServer.create(new InetSocketAddress(host, port), 16);
        server.createContext("/", this::page);
        server.createContext("/api/analytics", this::data);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "analytics-http");
            thread.setDaemon(true);
            return thread;
        }));
    }

    void start() { server.start(); }

    void stop() { server.stop(0); }

    int port() { return server.getAddress().getPort(); }

    private void page(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath())) { reply(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8)); return; }
        if (!"GET".equals(exchange.getRequestMethod())) { reply(exchange, 405, "text/plain; charset=utf-8", new byte[0]); return; }
        try (var stream = AnalyticsDashboard.class.getResourceAsStream("/analytics/index.html")) {
            if (stream == null) throw new IOException("Missing analytics dashboard resource");
            reply(exchange, 200, "text/html; charset=utf-8", stream.readAllBytes());
        }
    }

    private void data(HttpExchange exchange) throws IOException {
        if (!"/api/analytics".equals(exchange.getRequestURI().getPath())) { reply(exchange, 404, "text/plain; charset=utf-8", new byte[0]); return; }
        if (!"GET".equals(exchange.getRequestMethod())) { reply(exchange, 405, "text/plain; charset=utf-8", new byte[0]); return; }
        Map<String, Object> snapshot = new LinkedHashMap<>(analytics.snapshot());
        snapshot.put("live", rooms.analyticsSnapshot());
        reply(exchange, 200, "application/json; charset=utf-8", GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
    }
}
