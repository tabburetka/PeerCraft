package net.peercraft.network.rendezvous;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class RendezvousProtocolTest {

    private static RendezvousProtocol.Address addr(String ip, int port) throws UnknownHostException {
        return new RendezvousProtocol.Address(InetAddress.getByName(ip), port);
    }

    @Test
    void messageTypeRejectsWrongMagic() {
        byte[] data = {0x00, RendezvousProtocol.TYPE_REGISTER};
        assertEquals(-1, RendezvousProtocol.messageType(data, data.length));
    }

    @Test
    void messageTypeRejectsTooShort() {
        byte[] data = {RendezvousProtocol.MAGIC};
        assertEquals(-1, RendezvousProtocol.messageType(data, data.length));
    }

    @Test
    void registerRoundTrip() {
        byte[] encoded = RendezvousProtocol.encodeRegister();
        assertEquals(RendezvousProtocol.TYPE_REGISTER, (byte) RendezvousProtocol.messageType(encoded, encoded.length));
    }

    @Test
    void roomCreatedRoundTrip() throws UnknownHostException {
        RendezvousProtocol.Address hostAddress = addr("203.0.113.5", 12345);
        byte[] encoded = RendezvousProtocol.encodeRoomCreated("ABC123", hostAddress);

        assertEquals(RendezvousProtocol.TYPE_ROOM_CREATED, (byte) RendezvousProtocol.messageType(encoded, encoded.length));
        RendezvousProtocol.RoomCreated decoded = RendezvousProtocol.decodeRoomCreated(encoded, encoded.length);
        assertEquals("ABC123", decoded.code());
        assertEquals(hostAddress, decoded.hostAddress());
    }

    @Test
    void joinRoundTrip() {
        byte[] encoded = RendezvousProtocol.encodeJoin("XYZ789");

        assertEquals(RendezvousProtocol.TYPE_JOIN, (byte) RendezvousProtocol.messageType(encoded, encoded.length));
        RendezvousProtocol.Join decoded = RendezvousProtocol.decodeJoin(encoded, encoded.length);
        assertEquals("XYZ789", decoded.code());
    }

    @Test
    void joinFailRoundTrip() {
        byte[] encoded = RendezvousProtocol.encodeJoinFail(RendezvousProtocol.REASON_ALREADY_CLAIMED);

        assertEquals(RendezvousProtocol.TYPE_JOIN_FAIL, (byte) RendezvousProtocol.messageType(encoded, encoded.length));
        assertEquals(RendezvousProtocol.REASON_ALREADY_CLAIMED, RendezvousProtocol.decodeJoinFailReason(encoded, encoded.length));
    }

    @Test
    void peerFoundRoundTrip() throws UnknownHostException {
        RendezvousProtocol.Address peer = addr("198.51.100.9", 54321);
        byte[] encoded = RendezvousProtocol.encodePeerFound(peer, 0x1122334455667788L);

        assertEquals(RendezvousProtocol.TYPE_PEER_FOUND, (byte) RendezvousProtocol.messageType(encoded, encoded.length));
        RendezvousProtocol.PeerFound decoded = RendezvousProtocol.decodePeerFound(encoded, encoded.length);
        assertEquals(peer, decoded.peer());
        assertEquals(0x1122334455667788L, decoded.token());
    }

    @Test
    void punchAndPunchAckRoundTrip() {
        byte[] punch = RendezvousProtocol.encodePunch(42L);
        byte[] ack = RendezvousProtocol.encodePunchAck(42L);

        assertEquals(RendezvousProtocol.TYPE_PUNCH, (byte) RendezvousProtocol.messageType(punch, punch.length));
        assertEquals(RendezvousProtocol.TYPE_PUNCH_ACK, (byte) RendezvousProtocol.messageType(ack, ack.length));
        assertEquals(42L, RendezvousProtocol.decodeToken(punch, punch.length));
        assertEquals(42L, RendezvousProtocol.decodeToken(ack, ack.length));
    }

    /**
     * Pins the exact wire bytes so drift between this copy and rendezvous-server's
     * RendezvousProtocol.java is caught immediately, not just "both decode their own
     * encoding fine" (which wouldn't catch a change that's internally consistent but
     * incompatible with the other copy).
     */
    @Test
    void fixedEncodingMatchesServerCopy() throws UnknownHostException {
        byte[] register = RendezvousProtocol.encodeRegister();
        assertArrayEquals(new byte[]{(byte) 0xE1, 0x01}, register);

        byte[] join = RendezvousProtocol.encodeJoin("ABCDEF");
        assertArrayEquals(new byte[]{(byte) 0xE1, 0x03, 0x06, 'A', 'B', 'C', 'D', 'E', 'F'}, join);

        byte[] joinFail = RendezvousProtocol.encodeJoinFail((byte) 2);
        assertArrayEquals(new byte[]{(byte) 0xE1, 0x05, 0x02}, joinFail);

        byte[] roomCreated = RendezvousProtocol.encodeRoomCreated("AAAAAA", addr("1.2.3.4", 0x1234));
        assertArrayEquals(new byte[]{
                (byte) 0xE1, 0x02,
                0x06, 'A', 'A', 'A', 'A', 'A', 'A',
                0x04, 1, 2, 3, 4,
                0x12, 0x34,
        }, roomCreated);

        byte[] peerFound = RendezvousProtocol.encodePeerFound(addr("1.2.3.4", 0x1234), 0x0102030405060708L);
        assertArrayEquals(new byte[]{
                (byte) 0xE1, 0x06,
                0x04, 1, 2, 3, 4,
                0x12, 0x34,
                1, 2, 3, 4, 5, 6, 7, 8,
        }, peerFound);

        byte[] punch = RendezvousProtocol.encodePunch(0x0102030405060708L);
        assertArrayEquals(new byte[]{(byte) 0xE1, 0x10, 1, 2, 3, 4, 5, 6, 7, 8}, punch);
    }
}
