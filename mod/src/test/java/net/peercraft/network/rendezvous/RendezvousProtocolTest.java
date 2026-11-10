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
        byte[] encoded = RendezvousProtocol.encodeRegister(4, 2);
        assertEquals(RendezvousProtocol.TYPE_REGISTER, (byte) RendezvousProtocol.messageType(encoded, encoded.length));
        RendezvousProtocol.Register decoded = RendezvousProtocol.decodeRegister(encoded, encoded.length);
        assertEquals(4, decoded.maxPlayers());
        assertEquals(2, decoded.currentPlayerCount());
        assertTrue(decoded.account().isEmpty());
    }

    @Test
    void registerWithAccountRoundTrips() {
        java.util.UUID accountId = java.util.UUID.randomUUID();
        byte[] sessionToken = new byte[16];
        for (int i = 0; i < sessionToken.length; i++) {
            sessionToken[i] = (byte) i;
        }
        byte[] encoded = RendezvousProtocol.encodeRegisterWithAccount(4, 2, accountId, sessionToken, true);

        assertEquals(RendezvousProtocol.TYPE_REGISTER, (byte) RendezvousProtocol.messageType(encoded, encoded.length));
        RendezvousProtocol.Register decoded = RendezvousProtocol.decodeRegister(encoded, encoded.length);
        assertEquals(4, decoded.maxPlayers());
        assertEquals(2, decoded.currentPlayerCount());
        assertTrue(decoded.account().isPresent());
        assertEquals(accountId, decoded.account().get().accountId());
        assertArrayEquals(sessionToken, decoded.account().get().sessionToken());
        assertTrue(decoded.friendsOnly());
    }

    @Test
    void registerWithAccountNotFriendsOnlyRoundTrips() {
        java.util.UUID accountId = java.util.UUID.randomUUID();
        byte[] sessionToken = new byte[16];
        for (int i = 0; i < sessionToken.length; i++) {
            sessionToken[i] = (byte) i;
        }
        byte[] encoded = RendezvousProtocol.encodeRegisterWithAccount(4, 2, accountId, sessionToken, false);

        RendezvousProtocol.Register decoded = RendezvousProtocol.decodeRegister(encoded, encoded.length);
        assertFalse(decoded.friendsOnly());
    }

    @Test
    void oldAccountRegisterPayloadWithoutFriendsOnlyByteStillDecodes() {
        // Regression guard: a REGISTER encoded before Phase 6 (20 bytes, no trailing
        // friendsOnly byte) must still decode — friendsOnly just defaults to false.
        java.util.UUID accountId = java.util.UUID.randomUUID();
        byte[] sessionToken = new byte[16];
        for (int i = 0; i < sessionToken.length; i++) {
            sessionToken[i] = (byte) i;
        }
        byte[] fullEncoded = RendezvousProtocol.encodeRegisterWithAccount(4, 2, accountId, sessionToken, true);
        byte[] oldStyleEncoded = java.util.Arrays.copyOfRange(fullEncoded, 0, fullEncoded.length - 1);

        RendezvousProtocol.Register decoded = RendezvousProtocol.decodeRegister(oldStyleEncoded, oldStyleEncoded.length);
        assertTrue(decoded.account().isPresent());
        assertEquals(accountId, decoded.account().get().accountId());
        assertFalse(decoded.friendsOnly());
    }

    @Test
    void anonymousRegisterStillDecodesCorrectlyAlongsideTheAccountForm() {
        // Regression guard: extending REGISTER for Phase 4 must not disturb the original
        // 4-byte anonymous form still used by every host without an account.
        byte[] encoded = RendezvousProtocol.encodeRegister(8, 3);
        assertEquals(4, encoded.length);
        RendezvousProtocol.Register decoded = RendezvousProtocol.decodeRegister(encoded, encoded.length);
        assertEquals(8, decoded.maxPlayers());
        assertEquals(3, decoded.currentPlayerCount());
        assertTrue(decoded.account().isEmpty());
    }

    // ---- Phase 7: public game browser ----

    @Test
    void anonymousPublicRegisterRoundTrips() {
        byte[] encoded = RendezvousProtocol.encodeRegisterAnonymous(4, 2, true, "Steve's SMP", "1.21.1");

        RendezvousProtocol.Register decoded = RendezvousProtocol.decodeRegister(encoded, encoded.length);
        assertEquals(4, decoded.maxPlayers());
        assertEquals(2, decoded.currentPlayerCount());
        assertTrue(decoded.account().isEmpty());
        assertFalse(decoded.friendsOnly());
        assertTrue(decoded.publicRoom());
        assertEquals("Steve's SMP", decoded.worldName());
        assertEquals("1.21.1", decoded.mcVersion());
    }

    @Test
    void anonymousNonPublicRegisterRoundTrips() {
        byte[] encoded = RendezvousProtocol.encodeRegisterAnonymous(4, 2, false, "ignored", "ignored");

        RendezvousProtocol.Register decoded = RendezvousProtocol.decodeRegister(encoded, encoded.length);
        assertFalse(decoded.publicRoom());
        assertEquals("", decoded.worldName());
        assertEquals("", decoded.mcVersion());
    }

    @Test
    void accountRegisterWithPublicRoomRoundTrips() {
        java.util.UUID accountId = java.util.UUID.randomUUID();
        byte[] sessionToken = new byte[16];
        for (int i = 0; i < sessionToken.length; i++) {
            sessionToken[i] = (byte) i;
        }
        byte[] encoded = RendezvousProtocol.encodeRegisterWithAccount(4, 2, accountId, sessionToken, false, true, "Cool base", "1.21.10");

        RendezvousProtocol.Register decoded = RendezvousProtocol.decodeRegister(encoded, encoded.length);
        assertTrue(decoded.account().isPresent());
        assertFalse(decoded.friendsOnly());
        assertTrue(decoded.publicRoom());
        assertEquals("Cool base", decoded.worldName());
        assertEquals("1.21.10", decoded.mcVersion());
    }

    @Test
    void oldStylePayloadsStillDecodeWithPublicRoomFalse() {
        // Regression guard: a REGISTER encoded before Phase 7 (no publicRoom trailer at all)
        // must still decode — publicRoom/worldName just default to false/"".
        byte[] anonymous = RendezvousProtocol.encodeRegister(4, 2);
        assertFalse(RendezvousProtocol.decodeRegister(anonymous, anonymous.length).publicRoom());

        java.util.UUID accountId = java.util.UUID.randomUUID();
        byte[] sessionToken = new byte[16];
        byte[] withAccount = RendezvousProtocol.encodeRegisterWithAccount(4, 2, accountId, sessionToken, true);
        RendezvousProtocol.Register decoded = RendezvousProtocol.decodeRegister(withAccount, withAccount.length);
        assertTrue(decoded.friendsOnly());
        assertFalse(decoded.publicRoom());
        assertEquals("", decoded.worldName());
        assertEquals("", decoded.mcVersion());
    }

    @Test
    void roomListRoundTrips() {
        byte[] encoded = RendezvousProtocol.encodeRoomList();
        assertEquals(RendezvousProtocol.TYPE_ROOM_LIST, (byte) RendezvousProtocol.messageType(encoded, encoded.length));
    }

    @Test
    void roomListReplyRoundTrips() {
        RendezvousProtocol.PublicRoom room1 = new RendezvousProtocol.PublicRoom("ABC123", 4, 1, "Steve", "Steve's SMP", "1.21.1");
        RendezvousProtocol.PublicRoom room2 = new RendezvousProtocol.PublicRoom("XYZ789", 8, 0, "", "Anonymous world", "1.21.11");
        byte[] encoded = RendezvousProtocol.encodeRoomListReply(java.util.List.of(room1, room2));

        assertEquals(RendezvousProtocol.TYPE_ROOM_LIST_REPLY, (byte) RendezvousProtocol.messageType(encoded, encoded.length));
        RendezvousProtocol.RoomListReply decoded = RendezvousProtocol.decodeRoomListReply(encoded, encoded.length);
        assertEquals(2, decoded.rooms().size());
        assertEquals(room1, decoded.rooms().get(0));
        assertEquals(room2, decoded.rooms().get(1));
    }

    @Test
    void roomListReplyRoundTripsWithEmptyList() {
        byte[] encoded = RendezvousProtocol.encodeRoomListReply(java.util.List.of());
        RendezvousProtocol.RoomListReply decoded = RendezvousProtocol.decodeRoomListReply(encoded, encoded.length);
        assertTrue(decoded.rooms().isEmpty());
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
        assertTrue(decoded.sessionToken().isEmpty());
    }

    @Test
    void joinWithAccountRoundTrips() {
        byte[] sessionToken = new byte[16];
        for (int i = 0; i < sessionToken.length; i++) {
            sessionToken[i] = (byte) (i + 1);
        }
        byte[] encoded = RendezvousProtocol.encodeJoinWithAccount("XYZ789", sessionToken);

        assertEquals(RendezvousProtocol.TYPE_JOIN, (byte) RendezvousProtocol.messageType(encoded, encoded.length));
        RendezvousProtocol.Join decoded = RendezvousProtocol.decodeJoin(encoded, encoded.length);
        assertEquals("XYZ789", decoded.code());
        assertTrue(decoded.sessionToken().isPresent());
        assertArrayEquals(sessionToken, decoded.sessionToken().get());
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
        assertTrue(decoded.joinerAccountId().isEmpty());
    }

    @Test
    void peerFoundWithAccountRoundTrips() throws UnknownHostException {
        RendezvousProtocol.Address peer = addr("198.51.100.9", 54321);
        java.util.UUID joinerAccountId = java.util.UUID.randomUUID();
        byte[] encoded = RendezvousProtocol.encodePeerFoundWithAccount(peer, 0x1122334455667788L, joinerAccountId);

        assertEquals(RendezvousProtocol.TYPE_PEER_FOUND, (byte) RendezvousProtocol.messageType(encoded, encoded.length));
        RendezvousProtocol.PeerFound decoded = RendezvousProtocol.decodePeerFound(encoded, encoded.length);
        assertEquals(peer, decoded.peer());
        assertEquals(0x1122334455667788L, decoded.token());
        assertEquals(java.util.Optional.of(joinerAccountId), decoded.joinerAccountId());
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
        byte[] register = RendezvousProtocol.encodeRegister(4, 2);
        assertArrayEquals(new byte[]{(byte) 0xE1, 0x01, 0x04, 0x02}, register);

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

        byte[] anonymousPublicRegister = RendezvousProtocol.encodeRegisterAnonymous(4, 2, true, "AB", "CD");
        assertArrayEquals(new byte[]{(byte) 0xE1, 0x01, 4, 2, 0, 1, 2, 'A', 'B', 2, 'C', 'D'}, anonymousPublicRegister);

        byte[] roomList = RendezvousProtocol.encodeRoomList();
        assertArrayEquals(new byte[]{(byte) 0xE1, 0x07}, roomList);

        byte[] roomListReply = RendezvousProtocol.encodeRoomListReply(
                java.util.List.of(new RendezvousProtocol.PublicRoom("AB", 4, 2, "C", "DE", "F")));
        assertArrayEquals(new byte[]{
                (byte) 0xE1, 0x08,
                1,
                2, 'A', 'B',
                4, 2,
                1, 'C',
                2, 'D', 'E',
                1, 'F',
        }, roomListReply);
    }
}
