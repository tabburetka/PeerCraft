package net.peercraft.rendezvous.relay;

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

/** HTTPS in-process or an explicitly enabled TLS reverse proxy on loopback. */
public final class RelayHttpServer implements AutoCloseable {
    private final HttpServer server; private final RelayBroker broker; private final boolean proxy;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final ExecutorService requests = new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), r -> { Thread t = new Thread(r, "relay-http"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "relay-watchdog"); t.setDaemon(true); return t;
    });
    public RelayHttpServer(RelayConfig config, RelayBroker broker) throws IOException {
        this.broker = broker; proxy = config.keyStore() == null;
        InetAddress bind = InetAddress.getByName(config.bindHost());
        if (proxy) {
            if (!bind.isLoopbackAddress() || !config.tlsReverseProxy()) throw new IOException("Relay HTTP requires explicit loopback TLS reverse proxy");
            server = HttpServer.create(new InetSocketAddress(bind, config.port()), 64);
        } else {
            try {
                if (config.keyStorePassword() == null) throw new IOException("Missing relay keystore password environment setting");
                KeyStore store = KeyStore.getInstance("PKCS12"); char[] password = config.keyStorePassword().toCharArray();
                try (InputStream input = Files.newInputStream(config.keyStore())) { store.load(input, password); }
                KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keys.init(store, password);
                Arrays.fill(password, '\0');
                SSLContext context = SSLContext.getInstance("TLS"); context.init(keys.getKeyManagers(), null, null);
                HttpsServer https = HttpsServer.create(new InetSocketAddress(bind, config.port()), 64);
                https.setHttpsConfigurator(new HttpsConfigurator(context) {
                    @Override public void configure(HttpsParameters parameters) {
                        SSLParameters tls = context.getDefaultSSLParameters(); tls.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                        parameters.setSSLParameters(tls);
                    }
                }); server = https;
            } catch (IOException io) { throw io; }
            catch (Exception invalid) { throw new IOException("Relay TLS configuration invalid"); }
        }
        server.createContext("/v1/relay/leases", this::handle); server.setExecutor(requests);
    }
    public void start() {
        server.start(); maintenance.scheduleWithFixedDelay(() -> {
            try { broker.maintenance(); } catch (RuntimeException failure) { /* never print credential-bearing exceptions */ }
        }, 0, 10, TimeUnit.SECONDS);
    }
    public int port() { return server.getAddress().getPort(); }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (proxy && !exchange.getRemoteAddress().getAddress().isLoopbackAddress()) throw new RelayBroker.Failure(403, "proxy_required");
            String path = exchange.getRequestURI().getRawPath();
            if (exchange.getRequestURI().getRawQuery() != null) throw new RelayBroker.Failure(400, "query_forbidden");
            String base = "/v1/relay/leases"; String method = exchange.getRequestMethod();
            if (path.equals(base) && method.equals("POST")) {
                JsonObject body = body(exchange); byte[] session = token(body.get("sessionToken").getAsString());
                String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
                if (proxy) {
                    String forwarded = exchange.getRequestHeaders().getFirst("X-PeerCraft-Client-IP");
                    if (forwarded == null || !forwarded.matches("[0-9a-fA-F:.]{3,45}")) throw new RelayBroker.Failure(400, "client_ip_required");
                    ip = InetAddress.getByName(forwarded).getHostAddress();
                }
                RelayBroker.LeaseView lease = broker.create(body.get("roomCode").getAsString(),
                        Long.parseLong(body.get("pairToken").getAsString()), UUID.fromString(body.get("attemptId").getAsString()),
                        body.get("role").getAsString(), session, ip,
                        body.has("directChecksFailed") && body.get("directChecksFailed").getAsBoolean());
                send(exchange, 200, gson.toJsonTree(lease)); return;
            }
            String[] pieces = path.substring(Math.min(path.length(), base.length())).split("/");
            if (!path.startsWith(base + "/") || pieces.length < 2 || !pieces[1].matches("[a-f0-9-]{36}")) throw new RelayBroker.Failure(404, "not_found");
            String id = pieces[1]; String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) throw new RelayBroker.Failure(401, "unauthorized");
            byte[] session = token(authorization.substring(7));
            if (pieces.length == 2 && method.equals("GET")) send(exchange, 200, gson.toJsonTree(broker.get(id, session)));
            else if (pieces.length == 2 && method.equals("DELETE")) { broker.delete(id, session); send(exchange, 200, gson.toJsonTree(Map.of("state", "closed"))); }
            else if (pieces.length == 3 && pieces[2].equals("renew") && method.equals("POST")) { body(exchange); send(exchange, 200, gson.toJsonTree(broker.renew(id, session))); }
            else if (pieces.length == 3 && (pieces[2].equals("retain") || pieces[2].equals("release")) && method.equals("POST")) {
                long offer = Long.parseLong(body(exchange).get("offerId").getAsString());
                send(exchange, 200, gson.toJsonTree(pieces[2].equals("retain") ? broker.retain(id, session, offer) : broker.release(id, session, offer)));
            }
            else if (pieces.length == 3 && pieces[2].equals("endpoint") && method.equals("PUT")) {
                JsonObject body = body(exchange);
                send(exchange, 200, gson.toJsonTree(broker.endpoint(id, session,
                        new RelayBroker.Endpoint(body.get("host").getAsString(), body.get("port").getAsInt()),
                        body.get("generation").getAsInt(), body.has("confirmed") && body.get("confirmed").getAsBoolean())));
            } else throw new RelayBroker.Failure(405, "method_not_allowed");
        } catch (RelayBroker.Failure failure) { send(exchange, failure.status, gson.toJsonTree(Map.of("error", failure.code))); }
        catch (RuntimeException | IOException invalid) { send(exchange, 400, gson.toJsonTree(Map.of("error", "invalid_request"))); }
        finally { exchange.close(); }
    }
    private static byte[] token(String value) {
        try { if (value.length() > 64) throw new IllegalArgumentException(); return Base64.getDecoder().decode(value); }
        catch (RuntimeException invalid) { throw new RelayBroker.Failure(401, "unauthorized"); }
    }
    private static JsonObject body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(8193);
        if (bytes.length > 8192) throw new RelayBroker.Failure(413, "request_too_large");
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private void send(HttpExchange exchange, int status, JsonElement body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(0); maintenance.shutdownNow(); requests.shutdownNow(); broker.close(); }
}
