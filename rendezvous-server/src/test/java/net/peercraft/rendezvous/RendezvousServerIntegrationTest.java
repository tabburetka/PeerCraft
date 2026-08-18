package net.peercraft.rendezvous;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end test against a real running server, no Minecraft involved — just raw UDP. */
class RendezvousServerIntegrationTest {

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

    @Test
    void registerThenJoinMatchesBothPeers() throws Exception {
        int port = server.getBoundPort();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket hostSocket = new DatagramSocket();
             DatagramSocket joinerSocket = new DatagramSocket()) {
            hostSocket.setSoTimeout(2000);
            joinerSocket.setSoTimeout(2000);

            hostSocket.send(new DatagramPacket(RendezvousProtocol.encodeRegister(), 2, loopback, port));
            byte[] roomCreatedBuf = new byte[64];
            DatagramPacket roomCreatedPkt = new DatagramPacket(roomCreatedBuf, roomCreatedBuf.length);
            hostSocket.receive(roomCreatedPkt);
            RendezvousProtocol.RoomCreated roomCreated = RendezvousProtocol.decodeRoomCreated(roomCreatedBuf, roomCreatedPkt.getLength());
            assertEquals(6, roomCreated.code().length());
            assertEquals(hostSocket.getLocalPort(), roomCreated.hostAddress().port());

            byte[] joinMsg = RendezvousProtocol.encodeJoin(roomCreated.code());
            joinerSocket.send(new DatagramPacket(joinMsg, joinMsg.length, loopback, port));

            byte[] hostPeerFoundBuf = new byte[64];
            DatagramPacket hostPeerFoundPkt = new DatagramPacket(hostPeerFoundBuf, hostPeerFoundBuf.length);
            hostSocket.receive(hostPeerFoundPkt);
            RendezvousProtocol.PeerFound hostSidePeerFound = RendezvousProtocol.decodePeerFound(hostPeerFoundBuf, hostPeerFoundPkt.getLength());
            assertEquals(joinerSocket.getLocalPort(), hostSidePeerFound.peer().port());

            byte[] joinerPeerFoundBuf = new byte[64];
            DatagramPacket joinerPeerFoundPkt = new DatagramPacket(joinerPeerFoundBuf, joinerPeerFoundBuf.length);
            joinerSocket.receive(joinerPeerFoundPkt);
            RendezvousProtocol.PeerFound joinerSidePeerFound = RendezvousProtocol.decodePeerFound(joinerPeerFoundBuf, joinerPeerFoundPkt.getLength());
            assertEquals(hostSocket.getLocalPort(), joinerSidePeerFound.peer().port());

            assertEquals(hostSidePeerFound.token(), joinerSidePeerFound.token());
        }
    }

    @Test
    void joinWithBadCodeGetsInvalidCodeReply() throws Exception {
        int port = server.getBoundPort();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket joinerSocket = new DatagramSocket()) {
            joinerSocket.setSoTimeout(2000);

            byte[] joinMsg = RendezvousProtocol.encodeJoin("NOPE12");
            joinerSocket.send(new DatagramPacket(joinMsg, joinMsg.length, loopback, port));

            byte[] replyBuf = new byte[64];
            DatagramPacket replyPkt = new DatagramPacket(replyBuf, replyBuf.length);
            joinerSocket.receive(replyPkt);

            assertEquals(RendezvousProtocol.TYPE_JOIN_FAIL, (byte) RendezvousProtocol.messageType(replyBuf, replyPkt.getLength()));
            assertEquals(RendezvousProtocol.REASON_INVALID_CODE, RendezvousProtocol.decodeJoinFailReason(replyBuf, replyPkt.getLength()));
        }
    }
}
