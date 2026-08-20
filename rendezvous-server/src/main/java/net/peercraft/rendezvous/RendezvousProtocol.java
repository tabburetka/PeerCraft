package net.peercraft.rendezvous;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Wire codec for the rendezvous + hole-punch control protocol. Every datagram starts
 * with [magic:1][type:1] so it can be told apart from the mod's relay traffic (whose
 * FramedPacket.decode rejects anything not starting with its own VERSION byte).
 *
 * This file is intentionally duplicated between this server and the mod client (no
 * shared module yet — see the project plan). Keep the two copies byte-for-byte wire
 * compatible; both have unit tests asserting fixed encodings to catch drift early.
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

    /** {@code sessionToken} present (Фаза 5) lets the server resolve — and only the server, from a token only the real owner could have — the joiner's accountId to relay to the host for save-data identity injection. */
    public record Join(String code, Optional<byte[]> sessionToken) {
    }

    /** {@code account} is present only when the host is logged into a PeerCraft account (Фаза 4) — see encodeRegisterWithAccount. */
    public record Register(int maxPlayers, int currentPlayerCount, Optional<AccountRef> account) {
    }

    /** {@code sessionToken} must be re-validated server-side (see RendezvousServer.handleRegister) — a REGISTER must never be trusted to self-report its own accountId unchecked. */
    public record AccountRef(UUID accountId, byte[] sessionToken) {
    }

    /** {@code joinerAccountId} present (Фаза 5) only in the copy sent to the HOST, and only when the joiner attached a valid session to their JOIN — see RendezvousServer.handleJoin. */
    public record PeerFound(Address peer, long token, Optional<UUID> joinerAccountId) {
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

    // ---- REGISTER: host -> server ----
    // payload: [maxPlayers:1][currentPlayerCount:1] — currentPlayerCount is resent on every
    // 15s keepalive (see RendezvousClient) so the server's room capacity check self-corrects
    // within one keepalive interval of a player leaving, without a dedicated "player left"
    // message. Optional account trailer (Фаза 4): [hasAccount:1][accountId:16][sessionToken:16]
    // — lets a logged-in host's room show up as "hosting" in their friends' presence, see
    // encodeRegisterWithAccount. The 4-byte anonymous form is untouched (old pin tests still
    // pass) — decodeRegister just checks the payload length to tell the two apart.

    public static byte[] encodeRegister(int maxPlayers, int currentPlayerCount) {
        return new byte[]{MAGIC, TYPE_REGISTER, (byte) maxPlayers, (byte) currentPlayerCount};
    }

    public static byte[] encodeRegisterWithAccount(int maxPlayers, int currentPlayerCount, UUID accountId, byte[] sessionToken) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 1 + 1 + 1 + 16 + sessionToken.length);
        buf.put(MAGIC);
        buf.put(TYPE_REGISTER);
        buf.put((byte) maxPlayers);
        buf.put((byte) currentPlayerCount);
        buf.put((byte) 1);
        buf.putLong(accountId.getMostSignificantBits());
        buf.putLong(accountId.getLeastSignificantBits());
        buf.put(sessionToken);
        return buf.array();
    }

    public static Register decodeRegister(byte[] data, int length) {
        int maxPlayers = data[2] & 0xFF;
        int currentPlayerCount = data[3] & 0xFF;
        if (length <= 4) {
            return new Register(maxPlayers, currentPlayerCount, Optional.empty());
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 4, length - 4);
        boolean hasAccount = buf.get() != 0;
        if (!hasAccount) {
            return new Register(maxPlayers, currentPlayerCount, Optional.empty());
        }
        UUID accountId = new UUID(buf.getLong(), buf.getLong());
        byte[] sessionToken = new byte[16];
        buf.get(sessionToken);
        return new Register(maxPlayers, currentPlayerCount, Optional.of(new AccountRef(accountId, sessionToken)));
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
    // Optional trailer (Фаза 5): [hasToken:1][sessionToken:16] — see Join's doc comment.
    // The anonymous 3+codeLen-byte form is untouched (old pin tests still pass).

    public static byte[] encodeJoin(String code) {
        byte[] codeBytes = code.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(2 + 1 + codeBytes.length);
        buf.put(MAGIC);
        buf.put(TYPE_JOIN);
        buf.put((byte) codeBytes.length);
        buf.put(codeBytes);
        return buf.array();
    }

    public static byte[] encodeJoinWithAccount(String code, byte[] sessionToken) {
        byte[] codeBytes = code.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(2 + 1 + codeBytes.length + 1 + sessionToken.length);
        buf.put(MAGIC);
        buf.put(TYPE_JOIN);
        buf.put((byte) codeBytes.length);
        buf.put(codeBytes);
        buf.put((byte) 1);
        buf.put(sessionToken);
        return buf.array();
    }

    public static Join decodeJoin(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        int codeLen = buf.get() & 0xFF;
        byte[] codeBytes = new byte[codeLen];
        buf.get(codeBytes);
        String code = new String(codeBytes, StandardCharsets.US_ASCII);
        if (buf.remaining() >= 1) {
            boolean hasToken = buf.get() != 0;
            if (hasToken && buf.remaining() >= 16) {
                byte[] sessionToken = new byte[16];
                buf.get(sessionToken);
                return new Join(code, Optional.of(sessionToken));
            }
        }
        return new Join(code, Optional.empty());
    }

    // ---- JOIN_FAIL: server -> requester ----

    public static byte[] encodeJoinFail(byte reason) {
        return new byte[]{MAGIC, TYPE_JOIN_FAIL, reason};
    }

    public static byte decodeJoinFailReason(byte[] data, int length) {
        return data[2];
    }

    // ---- PEER_FOUND: server -> both matched peers ----
    // Optional trailer (Фаза 5): [hasAccount:1][accountId:16] — only ever sent to the HOST
    // (see PeerFound's doc comment); the anonymous form is untouched (old pin tests still pass).

    public static byte[] encodePeerFound(Address peer, long token) {
        ByteBuffer buf = ByteBuffer.allocate(2 + addressSize(peer) + 8);
        buf.put(MAGIC);
        buf.put(TYPE_PEER_FOUND);
        writeAddress(buf, peer);
        buf.putLong(token);
        return buf.array();
    }

    public static byte[] encodePeerFoundWithAccount(Address peer, long token, UUID joinerAccountId) {
        ByteBuffer buf = ByteBuffer.allocate(2 + addressSize(peer) + 8 + 1 + 16);
        buf.put(MAGIC);
        buf.put(TYPE_PEER_FOUND);
        writeAddress(buf, peer);
        buf.putLong(token);
        buf.put((byte) 1);
        buf.putLong(joinerAccountId.getMostSignificantBits());
        buf.putLong(joinerAccountId.getLeastSignificantBits());
        return buf.array();
    }

    public static PeerFound decodePeerFound(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        Address peer = readAddress(buf);
        long token = buf.getLong();
        if (buf.remaining() >= 1) {
            boolean hasAccount = buf.get() != 0;
            if (hasAccount && buf.remaining() >= 16) {
                UUID accountId = new UUID(buf.getLong(), buf.getLong());
                return new PeerFound(peer, token, Optional.of(accountId));
            }
        }
        return new PeerFound(peer, token, Optional.empty());
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
