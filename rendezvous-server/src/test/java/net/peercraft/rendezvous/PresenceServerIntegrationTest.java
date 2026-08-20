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
 * End-to-end test tying REGISTER (RendezvousProtocol, room pairing) together with presence
 * (AccountProtocol) — a logged-in host's REGISTER-with-account must make them show up as
 * HOSTING with the right room code in a friend's TYPE_FRIEND_LIST_REPLY.
 */
class PresenceServerIntegrationTest {

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
        byte[] hash = new byte[AccountProtocol.PASSWORD_HASH_LENGTH];
        byte[] request = AccountProtocol.encodeAccountRegister(username, salt, hash);
        byte[] reply = sendAndReceive(socket, server, port, request);
        return AccountProtocol.decodeAuthOk(reply, reply.length);
    }

    @Test
    @Timeout(15)
    void hostRegisteringWithAccountMakesFriendSeeHostingStatusAndRoomCode() throws Exception {
        int port = server.getBoundPort();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket hostSocket = new DatagramSocket();
             DatagramSocket friendSocket = new DatagramSocket()) {
            hostSocket.setSoTimeout(3000);
            friendSocket.setSoTimeout(3000);

            AccountProtocol.AuthOk host = registerAccount(hostSocket, loopback, port, "HostPlayer");
            AccountProtocol.AuthOk friend = registerAccount(friendSocket, loopback, port, "FriendPlayer");

            sendAndReceive(hostSocket, loopback, port, AccountProtocol.encodeFriendRequestSend(host.sessionToken(), friend.accountId()));
            sendAndReceive(friendSocket, loopback, port, AccountProtocol.encodeFriendRequestRespond(friend.sessionToken(), host.accountId(), true));

            // Before hosting: friend sees them offline (no presence yet).
            byte[] beforeReply = sendAndReceive(friendSocket, loopback, port, AccountProtocol.encodeFriendListQuery(friend.sessionToken()));
            AccountProtocol.FriendListReply before = AccountProtocol.decodeFriendListReply(beforeReply, beforeReply.length);
            assertEquals(AccountProtocol.STATUS_OFFLINE, before.friends().get(0).status());

            // Host opens a room, attaching their account — same wire message a real
            // RendezvousClient.registerRoom(...) with a logged-in session would send.
            byte[] registerReq = RendezvousProtocol.encodeRegisterWithAccount(4, 0, host.accountId(), host.sessionToken());
            byte[] registerReply = sendAndReceive(hostSocket, loopback, port, registerReq);
            RendezvousProtocol.RoomCreated roomCreated = RendezvousProtocol.decodeRoomCreated(registerReply, registerReply.length);

            byte[] afterReply = sendAndReceive(friendSocket, loopback, port, AccountProtocol.encodeFriendListQuery(friend.sessionToken()));
            AccountProtocol.FriendListReply after = AccountProtocol.decodeFriendListReply(afterReply, afterReply.length);
            assertEquals(1, after.friends().size());
            assertEquals(host.accountId(), after.friends().get(0).accountId());
            assertEquals(AccountProtocol.STATUS_HOSTING, after.friends().get(0).status());
            assertEquals(roomCreated.code(), after.friends().get(0).roomCode());
        }
    }

    @Test
    @Timeout(15)
    void registerWithMismatchedSessionAndAccountIdIsIgnoredForPresence() throws Exception {
        // A REGISTER claiming to be accountId X but signed with a session token that actually
        // resolves to a DIFFERENT account must not let X show up as hosting — this is the
        // spoofing protection in RendezvousServer.handleRegister.
        int port = server.getBoundPort();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket attackerSocket = new DatagramSocket();
             DatagramSocket victimSocket = new DatagramSocket();
             DatagramSocket friendSocket = new DatagramSocket()) {
            attackerSocket.setSoTimeout(3000);
            victimSocket.setSoTimeout(3000);
            friendSocket.setSoTimeout(3000);

            AccountProtocol.AuthOk attacker = registerAccount(attackerSocket, loopback, port, "Attacker");
            AccountProtocol.AuthOk victim = registerAccount(victimSocket, loopback, port, "Victim");
            AccountProtocol.AuthOk friend = registerAccount(friendSocket, loopback, port, "Friend");

            sendAndReceive(victimSocket, loopback, port, AccountProtocol.encodeFriendRequestSend(victim.sessionToken(), friend.accountId()));
            sendAndReceive(friendSocket, loopback, port, AccountProtocol.encodeFriendRequestRespond(friend.sessionToken(), victim.accountId(), true));

            // Attacker claims victim's accountId but uses their OWN sessionToken.
            byte[] spoofed = RendezvousProtocol.encodeRegisterWithAccount(4, 0, victim.accountId(), attacker.sessionToken());
            sendAndReceive(attackerSocket, loopback, port, spoofed);

            byte[] friendsReply = sendAndReceive(friendSocket, loopback, port, AccountProtocol.encodeFriendListQuery(friend.sessionToken()));
            AccountProtocol.FriendListReply friends = AccountProtocol.decodeFriendListReply(friendsReply, friendsReply.length);
            assertEquals(AccountProtocol.STATUS_OFFLINE, friends.friends().get(0).status(), "spoofed REGISTER must not mark the victim as hosting");
        }
    }
}
