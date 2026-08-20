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

            byte[] registerMsg = RendezvousProtocol.encodeRegister(4, 0);
            hostSocket.send(new DatagramPacket(registerMsg, registerMsg.length, loopback, port));
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
    void twoJoinersCanMatchTheSameRoomConcurrently() throws Exception {
        int port = server.getBoundPort();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket hostSocket = new DatagramSocket();
             DatagramSocket joinerASocket = new DatagramSocket();
             DatagramSocket joinerBSocket = new DatagramSocket()) {
            hostSocket.setSoTimeout(2000);
            joinerASocket.setSoTimeout(2000);
            joinerBSocket.setSoTimeout(2000);

            byte[] registerMsg = RendezvousProtocol.encodeRegister(2, 0);
            hostSocket.send(new DatagramPacket(registerMsg, registerMsg.length, loopback, port));
            byte[] roomCreatedBuf = new byte[64];
            DatagramPacket roomCreatedPkt = new DatagramPacket(roomCreatedBuf, roomCreatedBuf.length);
            hostSocket.receive(roomCreatedPkt);
            RendezvousProtocol.RoomCreated roomCreated = RendezvousProtocol.decodeRoomCreated(roomCreatedBuf, roomCreatedPkt.getLength());

            byte[] joinA = RendezvousProtocol.encodeJoin(roomCreated.code());
            joinerASocket.send(new DatagramPacket(joinA, joinA.length, loopback, port));
            byte[] hostPeerFoundA = new byte[64];
            DatagramPacket hostPeerFoundAPkt = new DatagramPacket(hostPeerFoundA, hostPeerFoundA.length);
            hostSocket.receive(hostPeerFoundAPkt);
            RendezvousProtocol.PeerFound hostSideA = RendezvousProtocol.decodePeerFound(hostPeerFoundA, hostPeerFoundAPkt.getLength());
            assertEquals(joinerASocket.getLocalPort(), hostSideA.peer().port());

            byte[] joinB = RendezvousProtocol.encodeJoin(roomCreated.code());
            joinerBSocket.send(new DatagramPacket(joinB, joinB.length, loopback, port));
            byte[] hostPeerFoundB = new byte[64];
            DatagramPacket hostPeerFoundBPkt = new DatagramPacket(hostPeerFoundB, hostPeerFoundB.length);
            hostSocket.receive(hostPeerFoundBPkt);
            RendezvousProtocol.PeerFound hostSideB = RendezvousProtocol.decodePeerFound(hostPeerFoundB, hostPeerFoundBPkt.getLength());
            assertEquals(joinerBSocket.getLocalPort(), hostSideB.peer().port());

            // Both joiners matched independently — the host got two distinct PEER_FOUND
            // replies (one per joiner) with two distinct tokens, neither replaced the other.
            assertNotEquals(hostSideA.token(), hostSideB.token());
        }
    }

    @Test
    void thirdJoinerBeyondMaxPlayersGetsRoomFullReply() throws Exception {
        int port = server.getBoundPort();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket hostSocket = new DatagramSocket();
             DatagramSocket joinerASocket = new DatagramSocket();
             DatagramSocket joinerBSocket = new DatagramSocket()) {
            hostSocket.setSoTimeout(2000);
            joinerASocket.setSoTimeout(2000);
            joinerBSocket.setSoTimeout(2000);

            byte[] registerMsg = RendezvousProtocol.encodeRegister(1, 0);
            hostSocket.send(new DatagramPacket(registerMsg, registerMsg.length, loopback, port));
            byte[] roomCreatedBuf = new byte[64];
            DatagramPacket roomCreatedPkt = new DatagramPacket(roomCreatedBuf, roomCreatedBuf.length);
            hostSocket.receive(roomCreatedPkt);
            RendezvousProtocol.RoomCreated roomCreated = RendezvousProtocol.decodeRoomCreated(roomCreatedBuf, roomCreatedPkt.getLength());

            byte[] joinA = RendezvousProtocol.encodeJoin(roomCreated.code());
            joinerASocket.send(new DatagramPacket(joinA, joinA.length, loopback, port));
            byte[] hostPeerFoundA = new byte[64];
            DatagramPacket hostPeerFoundAPkt = new DatagramPacket(hostPeerFoundA, hostPeerFoundA.length);
            hostSocket.receive(hostPeerFoundAPkt);

            // Host hasn't re-registered with an updated count yet, so the room still reads
            // maxPlayers=1/currentPlayerCount=0 as far as the server knows — this exercises
            // P2PBridge-independent, purely rendezvous-side capacity accounting once the
            // host's own report catches up. Simulate that catch-up here before the second join.
            byte[] keepalive = RendezvousProtocol.encodeRegister(1, 1);
            hostSocket.send(new DatagramPacket(keepalive, keepalive.length, loopback, port));
            byte[] roomCreatedAgainBuf = new byte[64];
            DatagramPacket roomCreatedAgainPkt = new DatagramPacket(roomCreatedAgainBuf, roomCreatedAgainBuf.length);
            hostSocket.receive(roomCreatedAgainPkt);

            byte[] joinB = RendezvousProtocol.encodeJoin(roomCreated.code());
            joinerBSocket.send(new DatagramPacket(joinB, joinB.length, loopback, port));
            byte[] replyBuf = new byte[64];
            DatagramPacket replyPkt = new DatagramPacket(replyBuf, replyBuf.length);
            joinerBSocket.receive(replyPkt);

            assertEquals(RendezvousProtocol.TYPE_JOIN_FAIL, (byte) RendezvousProtocol.messageType(replyBuf, replyPkt.getLength()));
            assertEquals(RendezvousProtocol.REASON_ALREADY_CLAIMED, RendezvousProtocol.decodeJoinFailReason(replyBuf, replyPkt.getLength()));
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

    /**
     * Фаза 5: a joiner who attaches their session to JOIN must have the host's PEER_FOUND
     * carry their real accountId — resolved server-side from the session token itself, never
     * trusted from anything the joiner could just claim (there's no client-supplied accountId
     * on JOIN at all, unlike REGISTER's account trailer).
     */
    @Test
    void joinWithAccountMakesHostSidePeerFoundCarryTheJoinersAccountId() throws Exception {
        int port = server.getBoundPort();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket hostSocket = new DatagramSocket();
             DatagramSocket joinerSocket = new DatagramSocket()) {
            hostSocket.setSoTimeout(2000);
            joinerSocket.setSoTimeout(2000);

            byte[] registerReq = AccountProtocol.encodeAccountRegister("Joiner1", new byte[AccountProtocol.SALT_LENGTH], new byte[AccountProtocol.PASSWORD_HASH_LENGTH]);
            joinerSocket.send(new DatagramPacket(registerReq, registerReq.length, loopback, port));
            byte[] authBuf = new byte[256];
            DatagramPacket authPkt = new DatagramPacket(authBuf, authBuf.length);
            joinerSocket.receive(authPkt);
            AccountProtocol.AuthOk joinerAccount = AccountProtocol.decodeAuthOk(authBuf, authPkt.getLength());

            byte[] registerMsg = RendezvousProtocol.encodeRegister(4, 0);
            hostSocket.send(new DatagramPacket(registerMsg, registerMsg.length, loopback, port));
            byte[] roomCreatedBuf = new byte[64];
            DatagramPacket roomCreatedPkt = new DatagramPacket(roomCreatedBuf, roomCreatedBuf.length);
            hostSocket.receive(roomCreatedPkt);
            RendezvousProtocol.RoomCreated roomCreated = RendezvousProtocol.decodeRoomCreated(roomCreatedBuf, roomCreatedPkt.getLength());

            byte[] joinMsg = RendezvousProtocol.encodeJoinWithAccount(roomCreated.code(), joinerAccount.sessionToken());
            joinerSocket.send(new DatagramPacket(joinMsg, joinMsg.length, loopback, port));

            byte[] hostPeerFoundBuf = new byte[128];
            DatagramPacket hostPeerFoundPkt = new DatagramPacket(hostPeerFoundBuf, hostPeerFoundBuf.length);
            hostSocket.receive(hostPeerFoundPkt);
            RendezvousProtocol.PeerFound hostSidePeerFound = RendezvousProtocol.decodePeerFound(hostPeerFoundBuf, hostPeerFoundPkt.getLength());

            assertEquals(java.util.Optional.of(joinerAccount.accountId()), hostSidePeerFound.joinerAccountId());

            // The joiner's own copy of PEER_FOUND never needs (or gets) an accountId trailer —
            // that field only matters to the host.
            byte[] joinerPeerFoundBuf = new byte[128];
            DatagramPacket joinerPeerFoundPkt = new DatagramPacket(joinerPeerFoundBuf, joinerPeerFoundBuf.length);
            joinerSocket.receive(joinerPeerFoundPkt);
            RendezvousProtocol.PeerFound joinerSidePeerFound = RendezvousProtocol.decodePeerFound(joinerPeerFoundBuf, joinerPeerFoundPkt.getLength());
            assertTrue(joinerSidePeerFound.joinerAccountId().isEmpty());
        }
    }
}
