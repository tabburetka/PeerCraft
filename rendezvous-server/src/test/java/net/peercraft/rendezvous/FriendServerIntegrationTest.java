package net.peercraft.rendezvous;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the friends flow (search/request/accept/list) against a real running
 * server over raw UDP — same convention as RendezvousServerIntegrationTest, but exercising
 * AccountProtocol instead of RendezvousProtocol.
 */
class FriendServerIntegrationTest {

    private RendezvousServer server;
    private Thread serverThread;

    @BeforeEach
    void startServer() throws Exception {
        server = new RendezvousServer(0);
        serverThread = new Thread(() -> {
            try {
                server.run();
            } catch (Exception ignored) {
                // socket closed on teardown
            }
        }, "test-rendezvous-server");
        serverThread.setDaemon(true);
        serverThread.start();

        long deadline = System.currentTimeMillis() + 2000;
        while (server.getBoundPort() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(server.getBoundPort() > 0, "server did not bind in time");
    }

    @AfterEach
    void stopServer() {
        serverThread.interrupt();
    }

    private byte[] sendAndReceive(DatagramSocket socket, InetAddress server, int port, byte[] request) throws Exception {
        socket.send(new DatagramPacket(request, request.length, server, port));
        byte[] buf = new byte[2048];
        DatagramPacket reply = new DatagramPacket(buf, buf.length);
        socket.receive(reply);
        byte[] data = new byte[reply.getLength()];
        System.arraycopy(reply.getData(), 0, data, 0, reply.getLength());
        return data;
    }

    private AccountProtocol.AuthOk registerAccount(DatagramSocket socket, InetAddress server, int port, String username) throws Exception {
        byte[] salt = new byte[AccountProtocol.SALT_LENGTH];
        new java.security.SecureRandom().nextBytes(salt);
        byte[] hash = new byte[AccountProtocol.PASSWORD_HASH_LENGTH];
        new java.security.SecureRandom().nextBytes(hash);
        byte[] request = AccountProtocol.encodeAccountRegister(username, salt, hash);
        byte[] reply = sendAndReceive(socket, server, port, request);
        assertEquals(AccountProtocol.TYPE_AUTH_OK, RendezvousProtocol.messageType(reply, reply.length));
        return AccountProtocol.decodeAuthOk(reply, reply.length);
    }

    @Test
    @Timeout(15)
    void sendRequestAcceptThenBothSeeEachOtherInFriendsList() throws Exception {
        int port = server.getBoundPort();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket aliceSocket = new DatagramSocket();
             DatagramSocket bobSocket = new DatagramSocket()) {
            aliceSocket.setSoTimeout(3000);
            bobSocket.setSoTimeout(3000);

            AccountProtocol.AuthOk alice = registerAccount(aliceSocket, loopback, port, "Alice");
            AccountProtocol.AuthOk bob = registerAccount(bobSocket, loopback, port, "Bob");

            byte[] sendReq = AccountProtocol.encodeFriendRequestSend(alice.sessionToken(), bob.accountId());
            byte[] sendReply = sendAndReceive(aliceSocket, loopback, port, sendReq);
            assertEquals(AccountProtocol.TYPE_FRIEND_REQUEST_ACK, RendezvousProtocol.messageType(sendReply, sendReply.length));
            assertTrue(AccountProtocol.decodeFriendRequestAck(sendReply, sendReply.length).ok());

            byte[] listReq = AccountProtocol.encodeFriendRequestListQuery(bob.sessionToken());
            byte[] listReply = sendAndReceive(bobSocket, loopback, port, listReq);
            AccountProtocol.FriendRequestListReply incoming = AccountProtocol.decodeFriendRequestListReply(listReply, listReply.length);
            assertEquals(1, incoming.requests().size());
            assertEquals(alice.accountId(), incoming.requests().get(0).fromAccountId());
            assertEquals("Alice", incoming.requests().get(0).displayName());

            byte[] respondReq = AccountProtocol.encodeFriendRequestRespond(bob.sessionToken(), alice.accountId(), true);
            byte[] respondReply = sendAndReceive(bobSocket, loopback, port, respondReq);
            assertTrue(AccountProtocol.decodeFriendRequestAck(respondReply, respondReply.length).ok());

            byte[] aliceFriendsReq = AccountProtocol.encodeFriendListQuery(alice.sessionToken());
            byte[] aliceFriendsReply = sendAndReceive(aliceSocket, loopback, port, aliceFriendsReq);
            AccountProtocol.FriendListReply aliceFriends = AccountProtocol.decodeFriendListReply(aliceFriendsReply, aliceFriendsReply.length);
            assertEquals(1, aliceFriends.friends().size());
            assertEquals(bob.accountId(), aliceFriends.friends().get(0).accountId());
            assertEquals(AccountProtocol.STATUS_OFFLINE, aliceFriends.friends().get(0).status());

            byte[] bobFriendsReq = AccountProtocol.encodeFriendListQuery(bob.sessionToken());
            byte[] bobFriendsReply = sendAndReceive(bobSocket, loopback, port, bobFriendsReq);
            AccountProtocol.FriendListReply bobFriends = AccountProtocol.decodeFriendListReply(bobFriendsReply, bobFriendsReply.length);
            assertEquals(1, bobFriends.friends().size());
            assertEquals(alice.accountId(), bobFriends.friends().get(0).accountId());
        }
    }

    @Test
    @Timeout(15)
    void searchFindsAccountsByDisplayNameSubstring() throws Exception {
        int port = server.getBoundPort();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket viewerSocket = new DatagramSocket();
             DatagramSocket targetSocket = new DatagramSocket()) {
            viewerSocket.setSoTimeout(3000);
            targetSocket.setSoTimeout(3000);

            AccountProtocol.AuthOk viewer = registerAccount(viewerSocket, loopback, port, "Viewer");
            AccountProtocol.AuthOk target = registerAccount(targetSocket, loopback, port, "SearchTarget");

            byte[] searchReq = AccountProtocol.encodeSearchAccounts(viewer.sessionToken(), "SearchTar");
            byte[] searchReply = sendAndReceive(viewerSocket, loopback, port, searchReq);
            AccountProtocol.SearchAccountsReply reply = AccountProtocol.decodeSearchAccountsReply(searchReply, searchReply.length);

            assertEquals(1, reply.results().size());
            assertEquals(target.accountId(), reply.results().get(0).accountId());
            assertEquals("SearchTarget", reply.results().get(0).displayName());
        }
    }

    @Test
    @Timeout(15)
    void removeFriendUnfriendsBothSides() throws Exception {
        int port = server.getBoundPort();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket aliceSocket = new DatagramSocket();
             DatagramSocket bobSocket = new DatagramSocket()) {
            aliceSocket.setSoTimeout(3000);
            bobSocket.setSoTimeout(3000);

            AccountProtocol.AuthOk alice = registerAccount(aliceSocket, loopback, port, "Alice2");
            AccountProtocol.AuthOk bob = registerAccount(bobSocket, loopback, port, "Bob2");

            sendAndReceive(aliceSocket, loopback, port, AccountProtocol.encodeFriendRequestSend(alice.sessionToken(), bob.accountId()));
            sendAndReceive(bobSocket, loopback, port, AccountProtocol.encodeFriendRequestRespond(bob.sessionToken(), alice.accountId(), true));

            byte[] removeReply = sendAndReceive(aliceSocket, loopback, port, AccountProtocol.encodeFriendRemove(alice.sessionToken(), bob.accountId()));
            assertTrue(AccountProtocol.decodeFriendRequestAck(removeReply, removeReply.length).ok());

            byte[] aliceFriendsReply = sendAndReceive(aliceSocket, loopback, port, AccountProtocol.encodeFriendListQuery(alice.sessionToken()));
            assertTrue(AccountProtocol.decodeFriendListReply(aliceFriendsReply, aliceFriendsReply.length).friends().isEmpty());

            byte[] bobFriendsReply = sendAndReceive(bobSocket, loopback, port, AccountProtocol.encodeFriendListQuery(bob.sessionToken()));
            assertTrue(AccountProtocol.decodeFriendListReply(bobFriendsReply, bobFriendsReply.length).friends().isEmpty());
        }
    }
}
