package net.peercraft.rendezvous.account;

import net.peercraft.network.account.EmailRecoveryClient;
import net.peercraft.network.account.PasswordCrypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the production Minecraft transport against the production TLS endpoint. */
class EmailHttpsContractTest {
    @TempDir Path directory;
    @Test void trustedHttpsBindingAndResetPreserveUuidAndRejectWrongHostAndPlaintext() throws Exception {
        Path keys = directory.resolve("test.p12");
        String password = "local-fixture-password";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "local", "-keyalg", "RSA", "-keystore", keys.toString(), "-storetype", "PKCS12",
                "-storepass", password, "-keypass", password, "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1",
                "-validity", "2", "-noprompt").redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keys)) { trust.load(input, password.toCharArray()); }
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, managers.getTrustManagers(), null);
        SSLSocketFactory previous = HttpsURLConnection.getDefaultSSLSocketFactory();
        List<EmailRecoveryService.Mail> mail = new CopyOnWriteArrayList<>();
        AccountService accounts = new AccountService(directory.resolve("accounts.json"), true, System::currentTimeMillis);
        byte[] salt = PasswordCrypto.randomSalt(), hash = PasswordCrypto.hash("first-password".toCharArray(), salt);
        var user = ((AccountService.Result.Ok<AccountService.AuthOkInfo>) accounts.register("Player", salt, hash, InetAddress.getLoopbackAddress())).value();
        var recovery = accounts.emailRecovery(letter -> { mail.add(letter); return true; }, System::currentTimeMillis);
        EmailConfig config = new EmailConfig(true, "127.0.0.1", 0, false, keys, password, "smtp.invalid", 587, false, "user", "unused", "accounts@example.org");
        try (EmailHttpServer server = new EmailHttpServer(config, recovery)) {
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory()); server.start();
            EmailRecoveryClient client = new EmailRecoveryClient("https://127.0.0.1:" + server.port() + "/v1/account/email");
            char[] first = "first-password".toCharArray();
            var binding = client.beginBinding(user.sessionToken(), "player@example.org", first).get(15, TimeUnit.SECONDS);
            assertArrayEquals(new char[first.length], first);
            client.confirmBinding(user.sessionToken(), binding.requestId, code(mail)).get(15, TimeUnit.SECONDS);
            var reset = client.beginReset("player@example.org").get(15, TimeUnit.SECONDS);
            char[] replacement = "replacement-password".toCharArray();
            var restored = client.finishReset(reset.requestId, code(mail), replacement).get(15, TimeUnit.SECONDS);
            assertArrayEquals(new char[replacement.length], replacement);
            assertEquals(user.accountId(), restored.accountId); assertEquals(user.friendCode(), restored.friendCode);
            assertTrue(accounts.resolveSession(user.sessionToken()).isEmpty());
            var challenge = ((AccountService.Result.Ok<AccountService.LoginChallengeInfo>) accounts.beginPasswordLogin(false,
                    restored.accountId, "", InetAddress.getLoopbackAddress())).value();
            var login = accounts.completePasswordLogin(challenge.requestId(), PasswordCrypto.hmacChallenge(
                    PasswordCrypto.hash("replacement-password".toCharArray(), challenge.salt()), challenge.challenge()));
            assertInstanceOf(AccountService.Result.Ok.class, login);
            var wrongHost = new EmailRecoveryClient("https://localhost:" + server.port() + "/v1/account/email");
            assertThrows(ExecutionException.class, () -> wrongHost.beginReset("player@example.org").get(15, TimeUnit.SECONDS));
            var plaintext = new EmailRecoveryClient("http://127.0.0.1:" + server.port() + "/v1/account/email");
            Exception error = assertThrows(ExecutionException.class, () -> plaintext.beginReset("player@example.org").get(15, TimeUnit.SECONDS));
            assertEquals("unconfigured", EmailRecoveryClient.errorCode(error));
        } finally { HttpsURLConnection.setDefaultSSLSocketFactory(previous); }
    }
    private static String code(List<EmailRecoveryService.Mail> mail) {
        return mail.getLast().body().split("\n")[0].substring("Code / Код: ".length());
    }
}
