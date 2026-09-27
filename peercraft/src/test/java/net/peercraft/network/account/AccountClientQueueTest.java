package net.peercraft.network.account;

import net.peercraft.network.rendezvous.AccountProtocol;
import net.peercraft.network.rendezvous.RendezvousProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AccountClientQueueTest {
    @Test
    @Timeout(10)
    void concurrentFriendsGamesAndAnotherFriendsRequestAllComplete() throws Exception {
        exerciseQueue(false);
    }

    @Test
    @Timeout(20)
    void timeoutAdvancesQueueAndQueuedRequestGetsItsOwnTimeoutBudget() throws Exception {
        exerciseQueue(true);
    }

    private void exerciseQueue(boolean dropFriends) throws Exception {
        CountDownLatch queued = new CountDownLatch(1);
        AtomicInteger friendReplies = new AtomicInteger();
        CompletableFuture<Void> serverFailure = new CompletableFuture<>();
        try (DatagramSocket server = new DatagramSocket()) {
            AccountClient client = new AccountClient();
            client.connect("127.0.0.1", server.getLocalPort());
            var sessionField = AccountClient.class.getDeclaredField("currentSession");
            sessionField.setAccessible(true);
            sessionField.set(client, new AccountClient.AccountSession(UUID.randomUUID(), new byte[16],
                    new byte[16], false, "ABCDEF", "Tester"));

            Thread responder = new Thread(() -> {
                try {
                    if (!queued.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("requests were not queued");
                    }
                    byte[] buffer = new byte[512];
                    while (!server.isClosed()) {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        server.receive(packet);
                        int type = RendezvousProtocol.messageType(buffer, packet.getLength());
                        byte[] reply;
                        if (type == AccountProtocol.TYPE_FRIEND_LIST) {
                            if (dropFriends) continue;
                            String name = "Friend" + friendReplies.incrementAndGet();
                            reply = AccountProtocol.encodeFriendListReply(List.of(new AccountProtocol.FriendEntry(
                                    UUID.randomUUID(), false, name, AccountProtocol.STATUS_ONLINE, "")));
                        } else if (type == RendezvousProtocol.TYPE_ROOM_LIST) {
                            reply = RendezvousProtocol.encodeRoomListReply(List.of());
                        } else {
                            throw new AssertionError("unexpected request type " + type);
                        }
                        server.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                    }
                } catch (Exception | AssertionError e) {
                    if (!server.isClosed()) serverFailure.completeExceptionally(e);
                }
            }, "fake-account-queue-server");
            responder.setDaemon(true);
            responder.start();

            try {
                CompletableFuture<List<AccountClient.FriendInfo>> first = listFriends(client);
                CompletableFuture<List<AccountClient.PublicGameInfo>> games = new CompletableFuture<>();
                client.listPublicGames(new AccountClient.PublicGameListCallback() {
                    public void onResult(List<AccountClient.PublicGameInfo> result) { games.complete(result); }
                    public void onTimeout() { games.completeExceptionally(new AssertionError("games timed out")); }
                });
                CompletableFuture<List<AccountClient.FriendInfo>> second = dropFriends ? null : listFriends(client);
                queued.countDown();

                if (dropFriends) {
                    assertNull(first.get(12, TimeUnit.SECONDS), "first request must time out");
                } else {
                    assertEquals("Friend1", first.get(5, TimeUnit.SECONDS).get(0).displayName());
                }
                assertTrue(games.get(5, TimeUnit.SECONDS).isEmpty());
                if (second != null) {
                    assertEquals("Friend2", second.get(5, TimeUnit.SECONDS).get(0).displayName());
                    assertEquals(2, friendReplies.get());
                }
                assertFalse(serverFailure.isCompletedExceptionally(), "fake server failed");
            } finally {
                queued.countDown();
                // Isolated clients otherwise retain their daemon receiver socket after the test.
                var socketField = AccountClient.class.getDeclaredField("socket");
                socketField.setAccessible(true);
                ((DatagramSocket) socketField.get(client)).close();
                responder.interrupt();
            }
        }
    }

    private CompletableFuture<List<AccountClient.FriendInfo>> listFriends(AccountClient client) {
        CompletableFuture<List<AccountClient.FriendInfo>> result = new CompletableFuture<>();
        client.listFriends(new AccountClient.FriendListCallback() {
            public void onResult(List<AccountClient.FriendInfo> friends) { result.complete(friends); }
            public void onTimeout() { result.complete(null); }
        });
        return result;
    }
}
