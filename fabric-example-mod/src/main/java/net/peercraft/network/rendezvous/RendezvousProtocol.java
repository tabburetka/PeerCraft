package net.peercraft.network.rendezvous;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Wire codec for the rendezvous + hole-punch control protocol. Every datagram starts
 * with [magic:1][type:1] so it can be told apart from the mod's relay traffic — see
 * {@link net.peercraft.network.p2p.FramedPacket}, whose decode() rejects anything not
 * starting with its own VERSION byte, so P2PBridge can demux on the first byte.
 *
 * This file is intentionally duplicated between the rendezvous-server project and this
 * mod (no shared module yet — see the project plan). Keep the two copies byte-for-byte
 * wire compatible; both have unit tests asserting fixed encodings to catch drift early.
 */
public final class RendezvousProtocol {

    public static final byte MAGIC = (byte) 0xE1;

    public static final byte TYPE_REGISTER = 0x01;
    public static final byte TYPE_ROOM_CREATED = 0x02;
    public static final byte TYPE_JOIN = 0x03;
    // 0x04 intentionally left free of an assigned meaning in this version.
    public static final byte TYPE_JOIN_FAIL = 0x05;
    public static final byte TYPE_PEER_FOUND = 0x06;
    public static final byte TYPE_PUNCH = 0x10;
    public static final byte TYPE_PUNCH_ACK = 0x11;

    // TYPE_JOIN_FAIL doubles as a generic "operation failed" reply — also used to
    // reject an over-limit REGISTER (with REASON_SERVER_BUSY) since a rejected
    // REGISTER has no dedicated wire message of its own.
    public static final byte REASON_INVALID_CODE = 1;
    public static final byte REASON_ALREADY_CLAIMED = 2;
    public static final byte REASON_EXPIRED = 3;
    public static final byte REASON_SERVER_BUSY = 4;

    private RendezvousProtocol() {
    }

    public record Address(InetAddress host, int port) {
    }

    public record RoomCreated(String code, Address hostAddress) {
    }

    public record Join(String code) {
    }

    public record PeerFound(Address peer, long token) {
    }

    /** @return the {@code type} byte, or -1 if this isn't a rendezvous datagram at all (wrong magic/too short). */
    public static int messageType(byte[] data, int length) {
        if (length < 2 || data[0] != MAGIC) {
            return -1;
        }
        return data[1] & 0xFF;
    }

    private static void writeAddress(ByteBuffer buf, Address address) {
        byte[] addrBytes = address.host().getAddress();
        buf.put((byte) addrBytes.length);
        buf.put(addrBytes);
        buf.putShort((short) address.port());
    }

    private static Address readAddress(ByteBuffer buf) {
        int addrLen = buf.get() & 0xFF;
        byte[] addrBytes = new byte[addrLen];
        buf.get(addrBytes);
        int port = buf.getShort() & 0xFFFF;
        try {
            return new Address(InetAddress.getByAddress(addrBytes), port);
        } catch (UnknownHostException e) {
            // Only thrown for a malformed byte-array length, which can't happen here
            // since addrLen was read straight off the array we just built it from.
            throw new IllegalStateException(e);
        }
    }

    private static int addressSize(Address address) {
        return 1 + address.host().getAddress().length + 2;
    }

    // ---- REGISTER: host -> server, no payload ----

    public static byte[] encodeRegister() {
        return new byte[]{MAGIC, TYPE_REGISTER};
    }

    // ---- ROOM_CREATED: server -> host ----

    public static byte[] encodeRoomCreated(String code, Address hostAddress) {
        byte[] codeBytes = code.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(2 + 1 + codeBytes.length + addressSize(hostAddress));
        buf.put(MAGIC);
        buf.put(TYPE_ROOM_CREATED);
        buf.put((byte) codeBytes.length);
        buf.put(codeBytes);
        writeAddress(buf, hostAddress);
        return buf.array();
    }

    public static RoomCreated decodeRoomCreated(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        int codeLen = buf.get() & 0xFF;
        byte[] codeBytes = new byte[codeLen];
        buf.get(codeBytes);
        String code = new String(codeBytes, StandardCharsets.US_ASCII);
        return new RoomCreated(code, readAddress(buf));
    }

    // ---- JOIN: joiner -> server ----

    public static byte[] encodeJoin(String code) {
        byte[] codeBytes = code.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(2 + 1 + codeBytes.length);
        buf.put(MAGIC);
        buf.put(TYPE_JOIN);
        buf.put((byte) codeBytes.length);
        buf.put(codeBytes);
        return buf.array();
    }

    public static Join decodeJoin(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        int codeLen = buf.get() & 0xFF;
        byte[] codeBytes = new byte[codeLen];
        buf.get(codeBytes);
        return new Join(new String(codeBytes, StandardCharsets.US_ASCII));
    }

    // ---- JOIN_FAIL: server -> requester ----

    public static byte[] encodeJoinFail(byte reason) {
        return new byte[]{MAGIC, TYPE_JOIN_FAIL, reason};
    }

    public static byte decodeJoinFailReason(byte[] data, int length) {
        return data[2];
    }

    // ---- PEER_FOUND: server -> both matched peers ----

    public static byte[] encodePeerFound(Address peer, long token) {
        ByteBuffer buf = ByteBuffer.allocate(2 + addressSize(peer) + 8);
        buf.put(MAGIC);
        buf.put(TYPE_PEER_FOUND);
        writeAddress(buf, peer);
        buf.putLong(token);
        return buf.array();
    }

    public static PeerFound decodePeerFound(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        Address peer = readAddress(buf);
        long token = buf.getLong();
        return new PeerFound(peer, token);
    }

    // ---- PUNCH / PUNCH_ACK: peer -> peer directly, server not involved ----

    public static byte[] encodePunch(long token) {
        return encodeTokenMessage(TYPE_PUNCH, token);
    }

    public static byte[] encodePunchAck(long token) {
        return encodeTokenMessage(TYPE_PUNCH_ACK, token);
    }

    private static byte[] encodeTokenMessage(byte type, long token) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 8);
        buf.put(MAGIC);
        buf.put(type);
        buf.putLong(token);
        return buf.array();
    }

    public static long decodeToken(byte[] data, int length) {
        return ByteBuffer.wrap(data, 2, length - 2).getLong();
    }
}
