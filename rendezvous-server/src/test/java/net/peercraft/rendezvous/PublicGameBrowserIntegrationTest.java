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
 * End-to-end test of the Phase 7 public game browser against a real running server over raw
 * UDP — same convention as RendezvousServerIntegrationTest/FriendServerIntegrationTest. The
 * Browsing is anonymous; joining requires an account to preserve the player's save UUID.
 */
class PublicGameBrowserIntegrationTest {

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

    @Test
    @Timeout(15)
    void anonymousBrowsingWorksButJoiningRequiresAStableAccountIdentity() throws Exception {
        int port = server.getBoundPort();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket hostSocket = new DatagramSocket();
             DatagramSocket browserSocket = new DatagramSocket()) {
            hostSocket.setSoTimeout(3000);
            browserSocket.setSoTimeout(3000);

            byte[] registerReq = RendezvousProtocol.encodeRegisterAnonymous(4, 0, true, "Steve's SMP", "1.21.1");
            byte[] registerReply = sendAndReceive(hostSocket, loopback, port, registerReq);
            assertEquals(RendezvousProtocol.TYPE_ROOM_CREATED, RendezvousProtocol.messageType(registerReply, registerReply.length));
            RendezvousProtocol.RoomCreated roomCreated = RendezvousProtocol.decodeRoomCreated(registerReply, registerReply.length);

            byte[] listReq = RendezvousProtocol.encodeRoomList();
            byte[] listReply = sendAndReceive(browserSocket, loopback, port, listReq);
            assertEquals(RendezvousProtocol.TYPE_ROOM_LIST_REPLY, RendezvousProtocol.messageType(listReply, listReply.length));
            RendezvousProtocol.RoomListReply rooms = RendezvousProtocol.decodeRoomListReply(listReply, listReply.length);

            assertEquals(1, rooms.rooms().size());
            RendezvousProtocol.PublicRoom listed = rooms.rooms().get(0);
            assertEquals(roomCreated.code(), listed.code());
            assertEquals("Steve's SMP", listed.worldName());
            assertEquals("1.21.1", listed.mcVersion());
            assertEquals("", listed.hostDisplayName(), "anonymous host must not have a display name");

            byte[] joinReq = RendezvousProtocol.encodeJoin(listed.code());
            byte[] joinReply = sendAndReceive(browserSocket, loopback, port, joinReq);
            assertEquals(RendezvousProtocol.TYPE_JOIN_FAIL, RendezvousProtocol.messageType(joinReply, joinReply.length));
            assertEquals(RendezvousProtocol.REASON_ACCOUNT_REQUIRED, RendezvousProtocol.decodeJoinFailReason(joinReply, joinReply.length));
            var account = TestAccounts.register(browserSocket, loopback, port);
            joinReply = sendAndReceive(browserSocket, loopback, port, RendezvousProtocol.encodeJoinWithAccount(listed.code(), account.sessionToken()));
            assertEquals(RendezvousProtocol.TYPE_PEER_FOUND, RendezvousProtocol.messageType(joinReply, joinReply.length));
        }
    }

    @Test
    @Timeout(15)
    void nonPublicRoomIsNotListed() throws Exception {
        int port = server.getBoundPort();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket hostSocket = new DatagramSocket();
             DatagramSocket browserSocket = new DatagramSocket()) {
            hostSocket.setSoTimeout(3000);
            browserSocket.setSoTimeout(3000);

            sendAndReceive(hostSocket, loopback, port, RendezvousProtocol.encodeRegister(4, 0));

            byte[] listReply = sendAndReceive(browserSocket, loopback, port, RendezvousProtocol.encodeRoomList());
            RendezvousProtocol.RoomListReply rooms = RendezvousProtocol.decodeRoomListReply(listReply, listReply.length);

            assertTrue(rooms.rooms().isEmpty());
        }
    }
}
