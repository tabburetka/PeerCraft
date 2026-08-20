package net.peercraft.network.account;

import net.peercraft.network.rendezvous.AccountProtocol;
import net.peercraft.network.rendezvous.RendezvousProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AccountClientTest {

    @Test
    @Timeout(15)
    void registerUnlicensedRoundTripsThroughFakeServer() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        UUID accountId = UUID.randomUUID();

        try (DatagramSocket fakeServerSocket = new DatagramSocket()) {
            Thread fakeServer = new Thread(() -> {
                byte[] buffer = new byte[512];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        packet.setLength(buffer.length);
                        fakeServerSocket.receive(packet);
                        if (RendezvousProtocol.messageType(packet.getData(), packet.getLength()) == AccountProtocol.TYPE_ACCOUNT_REGISTER) {
                            AccountProtocol.AccountRegister reg = AccountProtocol.decodeAccountRegister(packet.getData(), packet.getLength());
                            byte[] reply = AccountProtocol.encodeAuthOk(accountId, new byte[16], new byte[16], false, "ABCDEF", reg.username());
                            fakeServerSocket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                        }
                    }
                } catch (Exception ignored) {
                    // socket closed on teardown
                }
            }, "fake-account-server");
            fakeServer.setDaemon(true);
            fakeServer.start();

            AccountClient client = new AccountClient();
            client.connect("127.0.0.1", fakeServerSocket.getLocalPort());

            CompletableFuture<AccountClient.AccountSession> result = new CompletableFuture<>();
            client.registerUnlicensed("Pirate1", "hunter2".toCharArray(), new AccountClient.AuthCallback() {
                @Override
                public void onSuccess(AccountClient.AccountSession session) {
                    result.complete(session);
                }

                @Override
                public void onFailed(String reason) {
                    result.completeExceptionally(new AssertionError("unexpected failure: " + reason));
                }
            });

            AccountClient.AccountSession session = result.get(5, TimeUnit.SECONDS);
            fakeServer.interrupt();

            assertEquals(accountId, session.accountId());
            assertFalse(session.licensed());
            assertEquals("Pirate1", session.displayName());
            assertEquals("ABCDEF", session.friendCode());
            assertSame(session, client.getCurrentSession());
        }
    }

    @Test
    @Timeout(15)
    void registerFailureReportsReason() throws Exception {
        try (DatagramSocket fakeServerSocket = new DatagramSocket()) {
            Thread fakeServer = new Thread(() -> {
                byte[] buffer = new byte[512];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        packet.setLength(buffer.length);
                        fakeServerSocket.receive(packet);
                        if (RendezvousProtocol.messageType(packet.getData(), packet.getLength()) == AccountProtocol.TYPE_ACCOUNT_REGISTER) {
                            byte[] reply = AccountProtocol.encodeAuthFail(0L, AccountProtocol.REASON_USERNAME_INVALID);
                            fakeServerSocket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                        }
                    }
                } catch (Exception ignored) {
                    // socket closed on teardown
                }
            }, "fake-account-server");
            fakeServer.setDaemon(true);
            fakeServer.start();

            AccountClient client = new AccountClient();
            client.connect("127.0.0.1", fakeServerSocket.getLocalPort());

            CompletableFuture<String> failure = new CompletableFuture<>();
            client.registerUnlicensed("a", "hunter2".toCharArray(), new AccountClient.AuthCallback() {
                @Override
                public void onSuccess(AccountClient.AccountSession session) {
                    failure.completeExceptionally(new AssertionError("expected failure but got success"));
                }

                @Override
                public void onFailed(String reason) {
                    failure.complete(reason);
                }
            });

            String reason = failure.get(5, TimeUnit.SECONDS);
            fakeServer.interrupt();

            assertTrue(reason.contains("ник"), "expected the username-invalid message, got: " + reason);
        }
    }

    @Test
    @Timeout(15)
    void loginByFriendCodeCompletesChallengeResponseRoundTrip() throws Exception {
        // Exercises the FULL client-side crypto pipeline against a fake server that performs
        // the same HMAC check a real server would — not just protocol framing, but that
        // PasswordCrypto.hash/hmacChallenge on the client actually produces what an
        // independent, correctly-implemented server side expects.
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        UUID accountId = UUID.randomUUID();
        char[] password = "hunter2".toCharArray();
        byte[] salt = PasswordCrypto.randomSalt();
        byte[] storedHash = PasswordCrypto.hash(password, salt);
        byte[] challenge = new byte[AccountProtocol.CHALLENGE_LENGTH];
        new java.security.SecureRandom().nextBytes(challenge);
        long requestId = 42L;

        try (DatagramSocket fakeServerSocket = new DatagramSocket()) {
            Thread fakeServer = new Thread(() -> {
                byte[] buffer = new byte[512];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        packet.setLength(buffer.length);
                        fakeServerSocket.receive(packet);
                        int type = RendezvousProtocol.messageType(packet.getData(), packet.getLength());
                        if (type == AccountProtocol.TYPE_ACCOUNT_LOGIN_BEGIN) {
                            byte[] reply = AccountProtocol.encodeLoginChallenge(requestId, salt, challenge);
                            fakeServerSocket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                        } else if (type == AccountProtocol.TYPE_ACCOUNT_LOGIN_RESPONSE) {
                            AccountProtocol.LoginResponse resp = AccountProtocol.decodeLoginResponse(packet.getData(), packet.getLength());
                            byte[] expected = PasswordCrypto.hmacChallenge(storedHash, challenge);
                            byte[] reply = java.util.Arrays.equals(expected, resp.hmac())
                                    ? AccountProtocol.encodeAuthOk(accountId, new byte[16], new byte[16], false, "ABCDEF", "Pirate1")
                                    : AccountProtocol.encodeAuthFail(resp.requestId(), AccountProtocol.REASON_BAD_CREDENTIALS);
                            fakeServerSocket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                        }
                    }
                } catch (Exception ignored) {
                    // socket closed on teardown
                }
            }, "fake-account-server");
            fakeServer.setDaemon(true);
            fakeServer.start();

            AccountClient client = new AccountClient();
            client.connect("127.0.0.1", fakeServerSocket.getLocalPort());

            CompletableFuture<AccountClient.AccountSession> result = new CompletableFuture<>();
            client.loginByFriendCode("ABCDEF", password, new AccountClient.AuthCallback() {
                @Override
                public void onSuccess(AccountClient.AccountSession session) {
                    result.complete(session);
                }

                @Override
                public void onFailed(String reason) {
                    result.completeExceptionally(new AssertionError("unexpected failure: " + reason));
                }
            });

            AccountClient.AccountSession session = result.get(5, TimeUnit.SECONDS);
            fakeServer.interrupt();

            assertEquals(accountId, session.accountId());
        }
    }

    @Test
    @Timeout(15)
    void retriesRequestUntilServerRespondsThenStops() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        UUID accountId = UUID.randomUUID();
        AtomicInteger receivedCount = new AtomicInteger(0);
        CountDownLatch readyToReply = new CountDownLatch(3); // ignore the first two attempts to prove retry happens

        try (DatagramSocket fakeServerSocket = new DatagramSocket()) {
            Thread fakeServer = new Thread(() -> {
                byte[] buffer = new byte[512];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        packet.setLength(buffer.length);
                        fakeServerSocket.receive(packet);
                        if (RendezvousProtocol.messageType(packet.getData(), packet.getLength()) == AccountProtocol.TYPE_ACCOUNT_REGISTER) {
                            receivedCount.incrementAndGet();
                            readyToReply.countDown();
                            if (readyToReply.getCount() == 0) {
                                byte[] reply = AccountProtocol.encodeAuthOk(accountId, new byte[16], new byte[16], false, "ABCDEF", "Pirate1");
                                fakeServerSocket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // socket closed on teardown
                }
            }, "fake-account-server");
            fakeServer.setDaemon(true);
            fakeServer.start();

            AccountClient client = new AccountClient();
            client.connect("127.0.0.1", fakeServerSocket.getLocalPort());

            CompletableFuture<AccountClient.AccountSession> result = new CompletableFuture<>();
            client.registerUnlicensed("Pirate1", "hunter2".toCharArray(), new AccountClient.AuthCallback() {
                @Override
                public void onSuccess(AccountClient.AccountSession session) {
                    result.complete(session);
                }

                @Override
                public void onFailed(String reason) {
                    result.completeExceptionally(new AssertionError("unexpected failure: " + reason));
                }
            });

            result.get(5, TimeUnit.SECONDS);
            fakeServer.interrupt();

            assertTrue(receivedCount.get() >= 3, "expected the client to retry until the server started replying, got " + receivedCount.get() + " attempts");
        }
    }

    /** Registers against a fake server first, since friend methods require a current session — mirrors how a real GUI flow would look. */
    private static AccountClient loggedInClient(DatagramSocket fakeServerSocket, UUID accountId) throws Exception {
        AccountClient client = new AccountClient();
        client.connect("127.0.0.1", fakeServerSocket.getLocalPort());
        CompletableFuture<Void> registered = new CompletableFuture<>();
        client.registerUnlicensed("Pirate1", "hunter2".toCharArray(), new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession session) {
                registered.complete(null);
            }

            @Override
            public void onFailed(String reason) {
                registered.completeExceptionally(new AssertionError("register failed: " + reason));
            }
        });
        registered.get(5, TimeUnit.SECONDS);
        return client;
    }

    @Test
    @Timeout(15)
    void sendFriendRequestRoundTripsThroughFakeServer() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        try (DatagramSocket fakeServerSocket = new DatagramSocket()) {
            Thread fakeServer = new Thread(() -> {
                byte[] buffer = new byte[512];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        packet.setLength(buffer.length);
                        fakeServerSocket.receive(packet);
                        int type = RendezvousProtocol.messageType(packet.getData(), packet.getLength());
                        if (type == AccountProtocol.TYPE_ACCOUNT_REGISTER) {
                            byte[] reply = AccountProtocol.encodeAuthOk(accountId, new byte[16], new byte[16], false, "ABCDEF", "Pirate1");
                            fakeServerSocket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                        } else if (type == AccountProtocol.TYPE_FRIEND_REQUEST_SEND) {
                            AccountProtocol.FriendRequestSend send = AccountProtocol.decodeFriendRequestSend(packet.getData(), packet.getLength());
                            byte[] reply = send.targetAccountId().equals(targetId)
                                    ? AccountProtocol.encodeFriendRequestAck(true, (byte) 0)
                                    : AccountProtocol.encodeFriendRequestAck(false, AccountProtocol.REASON_UNKNOWN_ACCOUNT);
                            fakeServerSocket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                        }
                    }
                } catch (Exception ignored) {
                    // socket closed on teardown
                }
            }, "fake-account-server");
            fakeServer.setDaemon(true);
            fakeServer.start();

            AccountClient client = loggedInClient(fakeServerSocket, accountId);

            CompletableFuture<Void> result = new CompletableFuture<>();
            client.sendFriendRequest(targetId, new AccountClient.AckCallback() {
                @Override
                public void onSuccess() {
                    result.complete(null);
                }

                @Override
                public void onFailed(String reason) {
                    result.completeExceptionally(new AssertionError("unexpected failure: " + reason));
                }
            });

            assertDoesNotThrow(() -> result.get(5, TimeUnit.SECONDS));
            fakeServer.interrupt();
        }
    }

    @Test
    @Timeout(15)
    void listFriendsRoundTripsWithMultipleEntries() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID friendId = UUID.randomUUID();

        try (DatagramSocket fakeServerSocket = new DatagramSocket()) {
            Thread fakeServer = new Thread(() -> {
                byte[] buffer = new byte[512];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        packet.setLength(buffer.length);
                        fakeServerSocket.receive(packet);
                        int type = RendezvousProtocol.messageType(packet.getData(), packet.getLength());
                        if (type == AccountProtocol.TYPE_ACCOUNT_REGISTER) {
                            byte[] reply = AccountProtocol.encodeAuthOk(accountId, new byte[16], new byte[16], false, "ABCDEF", "Pirate1");
                            fakeServerSocket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                        } else if (type == AccountProtocol.TYPE_FRIEND_LIST) {
                            var entries = java.util.List.of(new AccountProtocol.FriendEntry(friendId, true, "Friendo", AccountProtocol.STATUS_HOSTING, "ZZZZZZ"));
                            byte[] reply = AccountProtocol.encodeFriendListReply(entries);
                            fakeServerSocket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                        }
                    }
                } catch (Exception ignored) {
                    // socket closed on teardown
                }
            }, "fake-account-server");
            fakeServer.setDaemon(true);
            fakeServer.start();

            AccountClient client = loggedInClient(fakeServerSocket, accountId);

            CompletableFuture<java.util.List<AccountClient.FriendInfo>> result = new CompletableFuture<>();
            client.listFriends(new AccountClient.FriendListCallback() {
                @Override
                public void onResult(java.util.List<AccountClient.FriendInfo> friends) {
                    result.complete(friends);
                }

                @Override
                public void onTimeout() {
                    result.completeExceptionally(new AssertionError("unexpected timeout"));
                }
            });

            java.util.List<AccountClient.FriendInfo> friends = result.get(5, TimeUnit.SECONDS);
            fakeServer.interrupt();

            assertEquals(1, friends.size());
            assertEquals(friendId, friends.get(0).accountId());
            assertEquals(AccountProtocol.STATUS_HOSTING, friends.get(0).status());
            assertEquals("ZZZZZZ", friends.get(0).roomCode());
        }
    }

    @Test
    @Timeout(15)
    void loginStartsPresenceHeartbeatImmediately() throws Exception {
        // AccountClient's heartbeat loop fires once immediately (before its first sleep), so
        // this doesn't need to wait out the real ~20s interval — just confirm login actually
        // triggers it, which is the thing regressions would break.
        UUID accountId = UUID.randomUUID();
        CompletableFuture<Void> heartbeatReceived = new CompletableFuture<>();

        try (DatagramSocket fakeServerSocket = new DatagramSocket()) {
            Thread fakeServer = new Thread(() -> {
                byte[] buffer = new byte[512];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        packet.setLength(buffer.length);
                        fakeServerSocket.receive(packet);
                        int type = RendezvousProtocol.messageType(packet.getData(), packet.getLength());
                        if (type == AccountProtocol.TYPE_ACCOUNT_REGISTER) {
                            byte[] reply = AccountProtocol.encodeAuthOk(accountId, new byte[16], new byte[16], false, "ABCDEF", "Pirate1");
                            fakeServerSocket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                        } else if (type == AccountProtocol.TYPE_PRESENCE_HEARTBEAT) {
                            heartbeatReceived.complete(null);
                        }
                    }
                } catch (Exception ignored) {
                    // socket closed on teardown
                }
            }, "fake-account-server");
            fakeServer.setDaemon(true);
            fakeServer.start();

            AccountClient client = loggedInClient(fakeServerSocket, accountId);

            assertDoesNotThrow(() -> heartbeatReceived.get(5, TimeUnit.SECONDS));
            client.stopPresenceHeartbeat();
            fakeServer.interrupt();
        }
    }
}
