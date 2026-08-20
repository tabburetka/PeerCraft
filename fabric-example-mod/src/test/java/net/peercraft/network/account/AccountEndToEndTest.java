package net.peercraft.network.account;

import net.peercraft.network.rendezvous.RendezvousProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the real {@link AccountClient} against the real rendezvous-server jar (see
 * RendezvousEndToEndTest for the sibling-project jar convention this follows) running as a
 * subprocess with {@code -Dpeercraft.rendezvous.fakeMojang=true} — no mocks, no real Mojang
 * network calls, but a genuine separate JVM speaking the real wire protocol end to end.
 * Self-skips if the sibling jar hasn't been built yet.
 */
class AccountEndToEndTest {

    private static final int SERVER_PORT = 51092;

    private Process serverProcess;
    private Path dataDir;

    @BeforeEach
    void startServer() throws Exception {
        Path jar = findServerJar();
        Assumptions.assumeTrue(jar != null,
                "rendezvous-server jar not built — run its own `./gradlew jar` first to enable this test");

        dataDir = Files.createTempDirectory("peercraft-account-e2e");
        serverProcess = new ProcessBuilder("java",
                "-Dpeercraft.rendezvous.fakeMojang=true",
                "-Dpeercraft.rendezvous.dataDir=" + dataDir,
                "-jar", jar.toString(), String.valueOf(SERVER_PORT))
                .redirectErrorStream(true)
                .start();

        long deadline = System.currentTimeMillis() + 3000;
        while (!isServerUp() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(isServerUp(), "rendezvous-server subprocess did not come up in time");
    }

    @AfterEach
    void stopServer() {
        if (serverProcess != null) {
            serverProcess.destroyForcibly();
        }
    }

    private static boolean isServerUp() {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.setSoTimeout(200);
            byte[] register = RendezvousProtocol.encodeRegister(1, 0);
            probe.send(new DatagramPacket(register, register.length, InetAddress.getByName("127.0.0.1"), SERVER_PORT));
            byte[] buf = new byte[64];
            probe.receive(new DatagramPacket(buf, buf.length));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path findServerJar() throws IOException {
        Path libsDir = Path.of("../rendezvous-server/build/libs");
        if (!Files.isDirectory(libsDir)) {
            return null;
        }
        try (var stream = Files.list(libsDir)) {
            return stream.filter(p -> p.toString().endsWith(".jar")).findFirst().orElse(null);
        }
    }

    @Test
    @Timeout(20)
    void registerThenLoginByFriendCodeFromASecondClientSucceeds() throws Exception {
        AccountClient registerer = new AccountClient();
        registerer.connect("127.0.0.1", SERVER_PORT);

        CompletableFuture<AccountClient.AccountSession> registered = new CompletableFuture<>();
        registerer.registerUnlicensed("Pirate1", "hunter2".toCharArray(), new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession session) {
                registered.complete(session);
            }

            @Override
            public void onFailed(String reason) {
                registered.completeExceptionally(new AssertionError("register failed: " + reason));
            }
        });
        AccountClient.AccountSession originalSession = registered.get(10, TimeUnit.SECONDS);
        assertFalse(originalSession.licensed());

        // Simulates logging in from a brand-new device — a fresh AccountClient with no
        // client-local state, only the friend code + password the player would type in.
        AccountClient newDevice = new AccountClient();
        newDevice.connect("127.0.0.1", SERVER_PORT);

        CompletableFuture<AccountClient.AccountSession> loggedIn = new CompletableFuture<>();
        newDevice.loginByFriendCode(originalSession.friendCode(), "hunter2".toCharArray(), new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession session) {
                loggedIn.complete(session);
            }

            @Override
            public void onFailed(String reason) {
                loggedIn.completeExceptionally(new AssertionError("login failed: " + reason));
            }
        });
        AccountClient.AccountSession reloggedSession = loggedIn.get(10, TimeUnit.SECONDS);

        assertEquals(originalSession.accountId(), reloggedSession.accountId());
        assertEquals("Pirate1", reloggedSession.displayName());
    }

    @Test
    @Timeout(20)
    void loginByFriendCodeWithWrongPasswordFails() throws Exception {
        AccountClient registerer = new AccountClient();
        registerer.connect("127.0.0.1", SERVER_PORT);
        CompletableFuture<AccountClient.AccountSession> registered = new CompletableFuture<>();
        registerer.registerUnlicensed("Pirate2", "correcthorse".toCharArray(), new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession session) {
                registered.complete(session);
            }

            @Override
            public void onFailed(String reason) {
                registered.completeExceptionally(new AssertionError("register failed: " + reason));
            }
        });
        AccountClient.AccountSession session = registered.get(10, TimeUnit.SECONDS);

        AccountClient attacker = new AccountClient();
        attacker.connect("127.0.0.1", SERVER_PORT);
        CompletableFuture<String> failure = new CompletableFuture<>();
        attacker.loginByFriendCode(session.friendCode(), "wrongpassword".toCharArray(), new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession s) {
                failure.completeExceptionally(new AssertionError("expected failure but login succeeded"));
            }

            @Override
            public void onFailed(String reason) {
                failure.complete(reason);
            }
        });

        assertNotNull(failure.get(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(20)
    void licensedLoginAgainstFakeMojangCreatesLicensedAccount() throws Exception {
        AccountClient client = new AccountClient();
        client.connect("127.0.0.1", SERVER_PORT);

        // FakeMojangVerifier (server-side, -Dpeercraft.rendezvous.fakeMojang=true) confirms
        // any username deterministically — we drive the same 0x20/0x22 wire steps a real
        // client would, but skip the actual sessionService.joinServer() Mojang HTTPS call by
        // just going straight from BEGIN's requestId to CONFIRM (fine for this fake path,
        // since FakeMojangVerifier's hasJoined() doesn't check anything about the join call
        // having actually happened — it just deterministically trusts the username).
        var beginResult = new CompletableFuture<net.peercraft.network.rendezvous.AccountProtocol.ServerIdChallenge>();
        // Reach into the protocol directly for this one test, since exercising the full
        // loginLicensed() path would require a real com.mojang.authlib session service call.
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.setSoTimeout(3000);
            byte[] begin = net.peercraft.network.rendezvous.AccountProtocol.encodeLicensedBegin("RealSteve");
            probe.send(new DatagramPacket(begin, begin.length, InetAddress.getByName("127.0.0.1"), SERVER_PORT));
            byte[] buf = new byte[256];
            DatagramPacket reply = new DatagramPacket(buf, buf.length);
            probe.receive(reply);
            var challenge = net.peercraft.network.rendezvous.AccountProtocol.decodeServerIdChallenge(reply.getData(), reply.getLength());

            byte[] confirm = net.peercraft.network.rendezvous.AccountProtocol.encodeLicensedConfirm(challenge.requestId());
            probe.send(new DatagramPacket(confirm, confirm.length, InetAddress.getByName("127.0.0.1"), SERVER_PORT));
            DatagramPacket authReply = new DatagramPacket(buf, buf.length);
            probe.receive(authReply);
            assertEquals(net.peercraft.network.rendezvous.AccountProtocol.TYPE_AUTH_OK,
                    RendezvousProtocol.messageType(authReply.getData(), authReply.getLength()));
            var ok = net.peercraft.network.rendezvous.AccountProtocol.decodeAuthOk(authReply.getData(), authReply.getLength());
            assertTrue(ok.licensed());
            assertEquals("RealSteve", ok.displayName());
        }
    }

    @Test
    @Timeout(20)
    void friendRequestSentThenAcceptedMakesBothClientsSeeEachOtherAsFriends() throws Exception {
        AccountClient alice = new AccountClient();
        alice.connect("127.0.0.1", SERVER_PORT);
        AccountClient bob = new AccountClient();
        bob.connect("127.0.0.1", SERVER_PORT);

        AccountClient.AccountSession aliceSession = registerVia(alice, "Alice");
        AccountClient.AccountSession bobSession = registerVia(bob, "Bob");

        CompletableFuture<Void> sent = new CompletableFuture<>();
        alice.sendFriendRequest(bobSession.accountId(), new AccountClient.AckCallback() {
            @Override
            public void onSuccess() {
                sent.complete(null);
            }

            @Override
            public void onFailed(String reason) {
                sent.completeExceptionally(new AssertionError("send failed: " + reason));
            }
        });
        sent.get(10, TimeUnit.SECONDS);

        CompletableFuture<Void> accepted = new CompletableFuture<>();
        bob.respondToRequest(aliceSession.accountId(), true, new AccountClient.AckCallback() {
            @Override
            public void onSuccess() {
                accepted.complete(null);
            }

            @Override
            public void onFailed(String reason) {
                accepted.completeExceptionally(new AssertionError("respond failed: " + reason));
            }
        });
        accepted.get(10, TimeUnit.SECONDS);

        CompletableFuture<java.util.List<AccountClient.FriendInfo>> aliceFriends = new CompletableFuture<>();
        alice.listFriends(new AccountClient.FriendListCallback() {
            @Override
            public void onResult(java.util.List<AccountClient.FriendInfo> friends) {
                aliceFriends.complete(friends);
            }

            @Override
            public void onTimeout() {
                aliceFriends.completeExceptionally(new AssertionError("timed out"));
            }
        });

        java.util.List<AccountClient.FriendInfo> friends = aliceFriends.get(10, TimeUnit.SECONDS);
        assertEquals(1, friends.size());
        assertEquals(bobSession.accountId(), friends.get(0).accountId());
    }

    private static AccountClient.AccountSession registerVia(AccountClient client, String name) throws Exception {
        CompletableFuture<AccountClient.AccountSession> result = new CompletableFuture<>();
        client.registerUnlicensed(name, "hunter2".toCharArray(), new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession session) {
                result.complete(session);
            }

            @Override
            public void onFailed(String reason) {
                result.completeExceptionally(new AssertionError("register failed: " + reason));
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }
}
