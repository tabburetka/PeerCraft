package net.peercraft.rendezvous.account;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;

/** Independent of TURN admission. Plain HTTP is allowed only behind an explicit loopback TLS proxy. */
public final class EmailHttpServer implements AutoCloseable {
    private static final String BASE = "/v1/account/email";
    private final HttpServer server;
    private final EmailRecoveryService recovery;
    private final boolean proxy;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final ExecutorService requests = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), task -> { Thread t = new Thread(task, "peercraft-email-http"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread t = new Thread(task, "peercraft-email-maintenance"); t.setDaemon(true); return t;
    });
    public EmailHttpServer(EmailConfig config, EmailRecoveryService recovery) throws IOException {
        this.recovery = recovery; this.proxy = config.keyStore() == null;
        InetAddress bind = InetAddress.getByName(config.bindHost());
        if (proxy) {
            if (!bind.isLoopbackAddress() || !config.tlsReverseProxy()) throw new IOException("Email recovery requires HTTPS");
            server = HttpServer.create(new InetSocketAddress(bind, config.port()), 32);
        } else {
            try {
                if (config.keyStorePassword() == null) throw new IOException("Missing email TLS password");
                char[] password = config.keyStorePassword().toCharArray();
                KeyStore store = KeyStore.getInstance("PKCS12");
                try (InputStream input = Files.newInputStream(config.keyStore())) { store.load(input, password); }
                KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keys.init(store, password); Arrays.fill(password, '\0');
                SSLContext context = SSLContext.getInstance("TLS"); context.init(keys.getKeyManagers(), null, null);
                HttpsServer https = HttpsServer.create(new InetSocketAddress(bind, config.port()), 32);
                https.setHttpsConfigurator(new HttpsConfigurator(context) {
                    @Override public void configure(HttpsParameters params) {
                        SSLParameters tls = context.getDefaultSSLParameters(); tls.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                        params.setSSLParameters(tls);
                    }
                }); server = https;
            } catch (IOException failure) { throw failure; }
            catch (Exception invalid) { throw new IOException("Invalid email TLS configuration"); }
        }
        server.createContext(BASE, this::handle); server.setExecutor(requests);
    }
    public void start() {
        server.start(); maintenance.scheduleWithFixedDelay(recovery::maintenance, 30, 30, TimeUnit.SECONDS);
    }
    public int port() { return server.getAddress().getPort(); }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (exchange.getRequestURI().getRawQuery() != null) throw new EmailRecoveryService.Failure("invalid_request");
            String path = exchange.getRequestURI().getRawPath();
            String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
            if (proxy) {
                if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) throw new EmailRecoveryService.Failure("unauthorized");
                String forwarded = exchange.getRequestHeaders().getFirst("X-PeerCraft-Client-IP");
                if (forwarded == null || !forwarded.matches("[0-9a-fA-F:.]{3,45}")) throw new EmailRecoveryService.Failure("invalid_request");
                ip = InetAddress.getByName(forwarded).getHostAddress();
            }
            if (path.equals(BASE) && exchange.getRequestMethod().equals("GET")) {
                send(exchange, 200, gson.toJsonTree(recovery.profile(session(exchange)))); return;
            }
            if (!exchange.getRequestMethod().equals("POST")) { send(exchange, 405, gson.toJsonTree(Map.of("error", "method_not_allowed"))); return; }
            byte[] bytes = exchange.getRequestBody().readNBytes(4097);
            if (bytes.length > 4096) { send(exchange, 413, gson.toJsonTree(Map.of("error", "request_too_large"))); return; }
            JsonObject body = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            switch (path) {
                case BASE + "/bind" -> send(exchange, 200, gson.toJsonTree(recovery.beginBinding(session(exchange),
                        binary(body, "passwordHash", 32), body.get("email").getAsString(), ip)));
                case BASE + "/confirm" -> {
                    recovery.confirmBinding(session(exchange), body.get("requestId").getAsString(), body.get("code").getAsString());
                    send(exchange, 200, gson.toJsonTree(Map.of("status", "confirmed")));
                }
                case BASE + "/reset" -> send(exchange, 200, gson.toJsonTree(recovery.beginReset(body.get("email").getAsString(), ip)));
                case BASE + "/finish" -> {
                    var restored = recovery.finishReset(body.get("requestId").getAsString(), body.get("code").getAsString(),
                            binary(body, "salt", 16), binary(body, "passwordHash", 32));
                    send(exchange, 200, gson.toJsonTree(restored));
                }
                default -> send(exchange, 404, gson.toJsonTree(Map.of("error", "not_found")));
            }
        } catch (EmailRecoveryService.Failure failure) {
            int status = failure.code.equals("rate_limited") ? 429 : failure.code.equals("unauthorized") ? 401
                    : failure.code.equals("storage_unavailable") ? 503 : 400;
            send(exchange, status, gson.toJsonTree(Map.of("error", failure.code)));
        } catch (RuntimeException | IOException invalid) {
            send(exchange, 400, gson.toJsonTree(Map.of("error", "invalid_request")));
        } finally { exchange.close(); }
    }
    private static byte[] binary(JsonObject body, String key, int length) {
        String value = body.get(key).getAsString();
        if (value.length() > 64) throw new EmailRecoveryService.Failure("invalid_request");
        byte[] bytes = Base64.getDecoder().decode(value);
        if (bytes.length != length) throw new EmailRecoveryService.Failure("invalid_request");
        return bytes;
    }
    private static byte[] session(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ") || auth.length() > 64) throw new EmailRecoveryService.Failure("unauthorized");
        byte[] token = Base64.getDecoder().decode(auth.substring(7));
        if (token.length != 16) throw new EmailRecoveryService.Failure("unauthorized");
        return token;
    }
    private void send(HttpExchange exchange, int status, JsonElement value) throws IOException {
        byte[] bytes = gson.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(0); requests.shutdownNow(); maintenance.shutdownNow(); }
}
