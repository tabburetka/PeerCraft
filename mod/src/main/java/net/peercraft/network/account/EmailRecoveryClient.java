package net.peercraft.network.account;

import com.google.gson.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Java 8-compatible recovery transport shared by every loader. Credentials require HTTPS. */
public final class EmailRecoveryClient {
    public static final class Challenge {
        public final String requestId;
        Challenge(String requestId) { this.requestId = requestId; }
    }
    public static final class RecoveredAccount {
        public final UUID accountId; public final String friendCode;
        RecoveredAccount(UUID id, String code) { accountId = id; friendCode = code; }
    }
    public static final class Failure extends RuntimeException {
        public final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    private static final ExecutorService WORK = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(8), task -> {
                Thread thread = new Thread(task, "peercraft-email-client"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final String endpoint;
    public EmailRecoveryClient(String endpoint) { this.endpoint = endpoint; }

    public CompletableFuture<Challenge> beginBinding(final byte[] session, final String email, char[] password) {
        final byte[] token = session.clone(); final char[] secret = password.clone(); Arrays.fill(password, '\0');
        return work(() -> {
            try {
                JsonObject profile = request("", "GET", null, token);
                byte[] salt = Base64.getDecoder().decode(profile.get("salt").getAsString());
                byte[] hash = PasswordCrypto.hash(secret, salt);
                JsonObject body = new JsonObject(); body.addProperty("email", email);
                body.addProperty("passwordHash", Base64.getEncoder().encodeToString(hash)); Arrays.fill(hash, (byte) 0);
                return challenge(request("/bind", "POST", body, token));
            } finally { Arrays.fill(secret, '\0'); Arrays.fill(token, (byte) 0); }
        }, () -> { Arrays.fill(secret, '\0'); Arrays.fill(token, (byte) 0); });
    }
    public CompletableFuture<Void> confirmBinding(final byte[] session, final String id, final String code) {
        final byte[] token = session.clone();
        return work(() -> {
            try { request("/confirm", "POST", confirmation(id, code), token); return null; }
            finally { Arrays.fill(token, (byte) 0); }
        }, () -> Arrays.fill(token, (byte) 0));
    }
    public CompletableFuture<Challenge> beginReset(final String email) {
        return work(() -> {
            JsonObject body = new JsonObject(); body.addProperty("email", email);
            return challenge(request("/reset", "POST", body, null));
        }, () -> { });
    }
    public CompletableFuture<RecoveredAccount> finishReset(final String id, final String code, char[] password) {
        final char[] secret = password.clone(); Arrays.fill(password, '\0');
        return work(() -> {
            try {
                byte[] salt = PasswordCrypto.randomSalt(); byte[] hash = PasswordCrypto.hash(secret, salt);
                JsonObject body = confirmation(id, code);
                body.addProperty("salt", Base64.getEncoder().encodeToString(salt));
                body.addProperty("passwordHash", Base64.getEncoder().encodeToString(hash)); Arrays.fill(hash, (byte) 0);
                JsonObject response = request("/finish", "POST", body, null);
                return new RecoveredAccount(UUID.fromString(response.get("accountId").getAsString()), response.get("friendCode").getAsString());
            } finally { Arrays.fill(secret, '\0'); }
        }, () -> Arrays.fill(secret, '\0'));
    }
    private static JsonObject confirmation(String id, String code) {
        JsonObject body = new JsonObject(); body.addProperty("requestId", id); body.addProperty("code", code); return body;
    }
    private static Challenge challenge(JsonObject body) { return new Challenge(body.get("requestId").getAsString()); }
    private static <T> CompletableFuture<T> work(Callable<T> task, Runnable rejected) {
        final CompletableFuture<T> result = new CompletableFuture<T>();
        try { WORK.execute(() -> {
            try { result.complete(task.call()); }
            catch (Failure failure) { result.completeExceptionally(failure); }
            catch (Exception unavailable) { result.completeExceptionally(new Failure("unavailable")); }
        }); } catch (RejectedExecutionException full) {
            rejected.run(); result.completeExceptionally(new Failure("busy"));
        }
        return result;
    }
    private JsonObject request(String suffix, String method, JsonObject body, byte[] token) throws IOException {
        if (endpoint == null || endpoint.trim().isEmpty()) throw new Failure("unconfigured");
        URI uri;
        try { uri = URI.create(endpoint.replaceAll("/+$", "") + suffix); }
        catch (IllegalArgumentException invalid) { throw new Failure("unconfigured"); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) throw new Failure("unconfigured");
        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10_000); connection.setReadTimeout(10_000);
            connection.setRequestMethod(method); connection.setRequestProperty("Accept", "application/json");
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + Base64.getEncoder().encodeToString(token));
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) throw new Failure("unavailable");
            InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new Failure("unavailable");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = stream) {
                byte[] buffer = new byte[1024]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 4096) throw new Failure("unavailable"); bytes.write(buffer, 0, count);
                }
            }
            JsonObject parsed = new JsonParser().parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (status != 200) {
                String error = parsed.has("error") ? parsed.get("error").getAsString() : "unavailable";
                if (!Arrays.asList("rate_limited", "invalid_code", "invalid_email", "bad_credentials", "unauthorized",
                        "email_unavailable", "storage_unavailable", "unlicensed_only").contains(error)) error = "unavailable";
                throw new Failure(error);
            }
            return parsed;
        } finally { connection.disconnect(); }
    }
    public static String errorCode(Throwable failure) {
        while (failure instanceof CompletionException || failure instanceof ExecutionException) failure = failure.getCause();
        return failure instanceof Failure ? ((Failure) failure).code : "unavailable";
    }
}
