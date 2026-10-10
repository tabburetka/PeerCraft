package net.peercraft.rendezvous.account;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EmailHttpServerTest {
    @TempDir Path directory;
    private final List<EmailRecoveryService.Mail> mail = new ArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();
    private EmailConfig config(String bind, boolean proxy) {
        return new EmailConfig(true, bind, 0, proxy, null, null, "smtp.invalid", 587, false,
                "user", "secret-for-test", "accounts@example.org");
    }
    private HttpResponse<String> post(int port, String action, JsonObject body, String token, boolean forwarded) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/account/email/" + action))
                .header("Content-Type", "application/json");
        if (forwarded) request.header("X-PeerCraft-Client-IP", "127.0.0.1");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void proxyApiBindsAndResetsSameAccountAndRevokesSession() throws Exception {
        AccountService accounts = new AccountService(directory.resolve("accounts.json"), true, System::currentTimeMillis);
        byte[] hash = new byte[32]; hash[0] = 12;
        var user = ((AccountService.Result.Ok<AccountService.AuthOkInfo>) accounts.register("Player", new byte[16], hash,
                InetAddress.getLoopbackAddress())).value();
        var recovery = accounts.emailRecovery(letter -> { mail.add(letter); return true; }, System::currentTimeMillis);
        try (var server = new EmailHttpServer(config("127.0.0.1", true), recovery)) {
            server.start(); String token = Base64.getEncoder().encodeToString(user.sessionToken());
            JsonObject body = new JsonObject(); body.addProperty("email", "player@example.org");
            body.addProperty("passwordHash", Base64.getEncoder().encodeToString(hash));
            assertEquals(401, post(server.port(), "bind", body, null, true).statusCode());
            assertEquals(400, post(server.port(), "bind", body, token, false).statusCode());
            var begun = post(server.port(), "bind", body, token, true); assertEquals(200, begun.statusCode());
            String id = JsonParser.parseString(begun.body()).getAsJsonObject().get("requestId").getAsString();
            JsonObject confirm = new JsonObject(); confirm.addProperty("requestId", id); confirm.addProperty("code", code());
            assertEquals(200, post(server.port(), "confirm", confirm, token, true).statusCode());
            JsonObject reset = new JsonObject(); reset.addProperty("email", "player@example.org");
            var requested = post(server.port(), "reset", reset, null, true); assertEquals(200, requested.statusCode());
            JsonObject finish = new JsonObject();
            finish.addProperty("requestId", JsonParser.parseString(requested.body()).getAsJsonObject().get("requestId").getAsString());
            finish.addProperty("code", code()); finish.addProperty("salt", Base64.getEncoder().encodeToString(new byte[16]));
            finish.addProperty("passwordHash", Base64.getEncoder().encodeToString(new byte[32]));
            var restored = post(server.port(), "finish", finish, null, true); assertEquals(200, restored.statusCode());
            assertEquals(user.accountId().toString(), JsonParser.parseString(restored.body()).getAsJsonObject().get("accountId").getAsString());
            assertEquals("no-store", restored.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(accounts.resolveSession(user.sessionToken()).isEmpty());
            assertEquals(400, post(server.port(), "finish", finish, null, true).statusCode());
        }
    }
    @Test void refusesPublicPlainHttpAndUntrustedProxyMode() {
        var accounts = new AccountService(directory.resolve("accounts.json"), true, System::currentTimeMillis);
        var recovery = accounts.emailRecovery(letter -> true, System::currentTimeMillis);
        assertThrows(java.io.IOException.class, () -> new EmailHttpServer(config("0.0.0.0", true), recovery));
        assertThrows(java.io.IOException.class, () -> new EmailHttpServer(config("127.0.0.1", false), recovery));
    }
    @Test void configRequiresEnvironmentSecretsAndNeverPrintsThem() throws Exception {
        java.nio.file.Files.writeString(directory.resolve("email.properties"), "enabled=true\nsmtp.host=smtp.example.org\nsmtp.from=accounts@example.org\n");
        assertThrows(java.io.IOException.class, () -> EmailConfig.load(directory, Map.of()));
        var config = EmailConfig.load(directory, Map.of("PEERCRAFT_SMTP_USER", "user", "PEERCRAFT_SMTP_PASSWORD", "hidden-password"));
        assertFalse(config.toString().contains("hidden-password")); assertFalse(config.toString().contains("user"));
    }
    private String code() { return mail.getLast().body().split("\n")[0].substring("Code / Код: ".length()); }
}
