package net.peercraft.rendezvous;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TYPE_LOOKUP_HOST (host handoff): an anonymous client can ask "what room is account X
 * hosting?" and gets X's current room code back once X has done an account-bearing REGISTER —
 * this is how a joiner finds the successor's new room after a handoff changes the code.
 */
class LookupHostServerIntegrationTest {

    private RendezvousServer server;
    private Thread serverThread;

    @BeforeEach
    void startServer() throws Exception {
        server = new RendezvousServer(0);
        serverThread = new Thread(() -> {
            try {
                server.run();
            } catch (Exception ignored) {
            }
        }, "test-rendezvous-server");
        serverThread.setDaemon(true);
        serverThread.start();
        long deadline = System.currentTimeMillis() + 2000;
        while (server.getBoundPort() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(server.getBoundPort() > 0);
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

    private AccountProtocol.AuthOk registerAccount(DatagramSocket socket, InetAddress srv, int port, String username) throws Exception {
        byte[] salt = new byte[AccountProtocol.SALT_LENGTH];
        byte[] hash = new byte[AccountProtocol.PASSWORD_HASH_LENGTH];
        byte[] reply = sendAndReceive(socket, srv, port, AccountProtocol.encodeAccountRegister(username, salt, hash));
        return AccountProtocol.decodeAuthOk(reply, reply.length);
    }

    @Test
    @Timeout(15)
    void lookupReturnsEmptyBeforeHostingThenTheRoomCode() throws Exception {
        int port = server.getBoundPort();
        InetAddress lo = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket hostSocket = new DatagramSocket();
             DatagramSocket anonSocket = new DatagramSocket()) {
            hostSocket.setSoTimeout(3000);
            anonSocket.setSoTimeout(3000);

            AccountProtocol.AuthOk host = registerAccount(hostSocket, lo, port, "Successor");

            // Not hosting yet -> empty.
            byte[] before = sendAndReceive(anonSocket, lo, port, RendezvousProtocol.encodeLookupHost(host.accountId()));
            assertEquals(RendezvousProtocol.TYPE_LOOKUP_HOST_REPLY, (byte) RendezvousProtocol.messageType(before, before.length));
            assertEquals("", RendezvousProtocol.decodeLookupHostReply(before, before.length));

            // Account-bearing REGISTER -> now hosting.
            byte[] reg = RendezvousProtocol.encodeRegisterWithAccount(4, 0, host.accountId(), host.sessionToken(), false);
            byte[] regReply = sendAndReceive(hostSocket, lo, port, reg);
            RendezvousProtocol.RoomCreated room = RendezvousProtocol.decodeRoomCreated(regReply, regReply.length);

            byte[] after = sendAndReceive(anonSocket, lo, port, RendezvousProtocol.encodeLookupHost(host.accountId()));
            assertEquals(room.code(), RendezvousProtocol.decodeLookupHostReply(after, after.length));
        }
    }

    @Test
    @Timeout(15)
    void lookupForUnknownAccountIsEmpty() throws Exception {
        int port = server.getBoundPort();
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(3000);
            byte[] reply = sendAndReceive(s, lo, port, RendezvousProtocol.encodeLookupHost(UUID.randomUUID()));
            assertEquals("", RendezvousProtocol.decodeLookupHostReply(reply, reply.length));
        }
    }
}
