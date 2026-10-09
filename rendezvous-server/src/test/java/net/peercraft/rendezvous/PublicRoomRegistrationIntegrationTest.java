package net.peercraft.rendezvous;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Production client codec over real UDP; separate sockets simulate NAT port remapping. */
@Timeout(15)
class PublicRoomRegistrationIntegrationTest {
    private RendezvousServer server;
    private Thread serverThread;
    private InetAddress loopback;

    @BeforeEach
    void startServer() throws Exception {
        loopback = InetAddress.getByName("127.0.0.1");
        server = new RendezvousServer(0);
        serverThread = new Thread(() -> {
            try { server.run(); }
            catch (Exception ignored) { /* server closed during teardown */ }
        }, "test-public-room-registration");
        serverThread.setDaemon(true);
        serverThread.start();
        long deadline = System.currentTimeMillis() + 2000;
        while (server.getBoundPort() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertTrue(server.getBoundPort() > 0);
    }

    @AfterEach
    void stopServer() throws Exception {
        server.close();
        serverThread.interrupt();
        serverThread.join(2000);
    }

    private byte[] exchange(DatagramSocket socket, byte[] request) throws Exception {
        socket.setSoTimeout(3000);
        socket.send(new DatagramPacket(request, request.length, loopback, server.getBoundPort()));
        return receive(socket);
    }

    private byte[] receive(DatagramSocket socket) throws Exception {
        byte[] bytes = new byte[2048];
        DatagramPacket reply = new DatagramPacket(bytes, bytes.length);
        socket.receive(reply);
        return Arrays.copyOf(bytes, reply.getLength());
    }

    private String register(DatagramSocket socket, byte[] request) throws Exception {
        byte[] reply = exchange(socket, request);
        assertEquals(RendezvousProtocol.TYPE_ROOM_CREATED, RendezvousProtocol.messageType(reply, reply.length));
        return RendezvousProtocol.decodeRoomCreated(reply, reply.length).code();
    }

    private List<RendezvousProtocol.PublicRoom> list(DatagramSocket socket) throws Exception {
        byte[] reply = exchange(socket, RendezvousProtocol.encodeRoomList());
        return RendezvousProtocol.decodeRoomListReply(reply, reply.length).rooms();
    }

    private AccountProtocol.AuthOk registerAccount(DatagramSocket socket) throws Exception {
        byte[] request = AccountProtocol.encodeAccountRegister("TestPlayer", new byte[16], new byte[32]);
        byte[] reply = exchange(socket, request);
        assertEquals(AccountProtocol.TYPE_AUTH_OK, RendezvousProtocol.messageType(reply, reply.length));
        return AccountProtocol.decodeAuthOk(reply, reply.length);
    }

    private net.peercraft.network.rendezvous.RendezvousProtocol.ConnectivityAdvertisement session() {
        return new net.peercraft.network.rendezvous.RendezvousProtocol.ConnectivityAdvertisement(false, false, List.of());
    }

    @Test
    void anonymousWorldKeepsOneCodeAcrossThreeHostPortsAndJoinsTheLatestPort() throws Exception {
        try (DatagramSocket first = new DatagramSocket();
             DatagramSocket second = new DatagramSocket();
             DatagramSocket third = new DatagramSocket();
             DatagramSocket browser = new DatagramSocket()) {
            var identity = session();
            byte[] request = net.peercraft.network.rendezvous.RendezvousProtocol.encodeRegisterAnonymous(4, 0, true, "桜花", "26.2", identity);
            String code = register(first, request);
            assertEquals(code, register(second, request));
            assertEquals(code, register(third, request));
            var rooms = list(browser);
            assertEquals(1, rooms.size());
            assertEquals(code, rooms.getFirst().code());

            var account = registerAccount(browser);
            byte[] reply = exchange(browser, RendezvousProtocol.encodeJoinWithAccount(code, account.sessionToken()));
            var matched = RendezvousProtocol.decodePeerFound(reply, reply.length);
            assertEquals(third.getLocalPort(), matched.peer().port());
            byte[] hostReply = receive(third);
            assertEquals(RendezvousProtocol.TYPE_PEER_FOUND, RendezvousProtocol.messageType(hostReply, hostReply.length));
        }
    }

    @Test
    void authenticatedWorldAlsoKeepsOneCodeAcrossHostPorts() throws Exception {
        try (DatagramSocket first = new DatagramSocket();
             DatagramSocket second = new DatagramSocket();
             DatagramSocket browser = new DatagramSocket()) {
            first.setSoTimeout(3000);
            var account = registerAccount(first);
            byte[] request = net.peercraft.network.rendezvous.RendezvousProtocol.encodeRegisterWithAccount(4, 0, account.accountId(),
                    account.sessionToken(), false, true, "World", "26.2", session());
            assertEquals(register(first, request), register(second, request));
            assertEquals(1, list(browser).size());
        }
    }

    @Test
    void separateSessionsWithIdenticalWorldNamesBehindOneRouterRemainSeparate() throws Exception {
        try (DatagramSocket first = new DatagramSocket();
             DatagramSocket second = new DatagramSocket();
             DatagramSocket browser = new DatagramSocket()) {
            String a = register(first, net.peercraft.network.rendezvous.RendezvousProtocol.encodeRegisterAnonymous(4, 0, true, "桜花", "26.2", session()));
            String b = register(second, net.peercraft.network.rendezvous.RendezvousProtocol.encodeRegisterAnonymous(4, 0, true, "桜花", "26.2", session()));
            assertNotEquals(a, b);
            assertEquals(2, list(browser).size());
        }
    }

    @Test
    void legacyClientsStillRetryIdempotentlyAndCanHostSeparateWorlds() throws Exception {
        try (DatagramSocket first = new DatagramSocket();
             DatagramSocket second = new DatagramSocket();
             DatagramSocket browser = new DatagramSocket()) {
            byte[] request = net.peercraft.network.rendezvous.RendezvousProtocol.encodeRegisterAnonymous(4, 0, true, "World", "1.12.2");
            String a = register(first, request);
            assertEquals(a, register(first, request));
            assertNotEquals(a, register(second, request));
            assertEquals(2, list(browser).size());
        }
    }
}
