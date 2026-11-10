package net.peercraft.rendezvous;

import java.net.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real account registration over UDP for room tests that require a stable join identity. */
final class TestAccounts {
    static AccountProtocol.AuthOk register(DatagramSocket socket, InetAddress host, int port) throws Exception {
        byte[] request = AccountProtocol.encodeAccountRegister("TestPlayer", new byte[16], new byte[32]);
        socket.send(new DatagramPacket(request, request.length, host, port));
        byte[] bytes = new byte[512]; DatagramPacket reply = new DatagramPacket(bytes, bytes.length); socket.receive(reply);
        assertEquals(AccountProtocol.TYPE_AUTH_OK, (byte) RendezvousProtocol.messageType(bytes, reply.getLength()));
        return AccountProtocol.decodeAuthOk(bytes, reply.getLength());
    }
}
