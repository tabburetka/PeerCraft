package net.peercraft.network.rendezvous;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
    // Public game browser (Phase 7): anonymous, no session — see RoomRegistry.listPublicRooms().
    public static final byte TYPE_ROOM_LIST = 0x07;
    public static final byte TYPE_ROOM_LIST_REPLY = 0x08;
    public static final byte TYPE_PUNCH = 0x10;
    public static final byte TYPE_PUNCH_ACK = 0x11;

    // TYPE_JOIN_FAIL doubles as a generic "operation failed" reply — also used to
    // reject an over-limit REGISTER (with REASON_SERVER_BUSY) since a rejected
    // REGISTER has no dedicated wire message of its own.
    public static final byte REASON_INVALID_CODE = 1;
    public static final byte REASON_ALREADY_CLAIMED = 2;
    public static final byte REASON_EXPIRED = 3;
    public static final byte REASON_SERVER_BUSY = 4;
    // The room is friends-only (Phase 6) and the joiner either wasn't logged in or isn't on
    // the host's friends list — see RoomRegistry.join()'s friendChecker.
    public static final byte REASON_NOT_FRIEND = 5;

    private RendezvousProtocol() {
    }

    public record Address(InetAddress host, int port) {
    }

    public record RoomCreated(String code, Address hostAddress) {
    }

    /** {@code sessionToken} present (Phase 5) lets the server resolve — and only the server, from a token only the real owner could have — the joiner's accountId to relay to the host for save-data identity injection. */
    public record Join(String code, Optional<byte[]> sessionToken) {
    }

    /**
     * {@code account} is present only when the host is logged into a PeerCraft account
     * (Phase 4) — see encodeRegisterWithAccount. {@code friendsOnly} (Phase 6) only has an
     * effect when {@code account} is present — a room can't be gated to "friends" with no
     * account to own the friends list. {@code publicRoom}/{@code worldName}/{@code mcVersion}
     * (Phase 7) work regardless of whether an account is attached — anonymous hosts can host
     * publicly too, see RoomRegistry.register(). {@code worldName}/{@code mcVersion} are only
     * meaningful when publicRoom is true; empty otherwise. {@code mcVersion} is the host's
     * running Minecraft version (e.g. "1.21.1") — vanilla's own network protocol only lets
     * same-version clients actually connect, so the browser needs this to warn about (or filter
     * out) incompatible rooms before a joiner wastes a punch attempt on one.
     */
    public record Register(int maxPlayers, int currentPlayerCount, Optional<AccountRef> account, boolean friendsOnly,
                            boolean publicRoom, String worldName, String mcVersion) {
    }

    /** {@code sessionToken} must be re-validated server-side (see RendezvousServer.handleRegister) — a REGISTER must never be trusted to self-report its own accountId unchecked. */
    public record AccountRef(UUID accountId, byte[] sessionToken) {
    }

    /** {@code joinerAccountId} present (Phase 5) only in the copy sent to the HOST, and only when the joiner attached a valid session to their JOIN — see RendezvousServer.handleJoin. */
    public record PeerFound(Address peer, long token, Optional<UUID> joinerAccountId) {
    }

    /** One row of the public game browser (Phase 7) — {@code hostDisplayName} is "" for an anonymous host; {@code mcVersion} is the host's running Minecraft version, see Register's doc comment. */
    public record PublicRoom(String code, int maxPlayers, int currentPlayerCount, String hostDisplayName, String worldName, String mcVersion) {
    }

    public record RoomListReply(List<PublicRoom> rooms) {
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
    // message. Optional account trailer (Phase 4): [hasAccount:1][accountId:16][sessionToken:16]
    // — lets a logged-in host's room show up as "hosting" in their friends' presence, see
    // encodeRegisterWithAccount. The 4-byte anonymous form is untouched (old pin tests still
    // pass) — decodeRegister just checks the payload length to tell the two apart. A further
    // optional trailer byte (Phase 6, only meaningful with an account attached): [friendsOnly:1]
    // — gates JOIN to the host's friends list, see RoomRegistry.join(). Read only if present
    // (buf.remaining() >= 1), same incremental-trailer style as Join/PeerFound below, so an
    // older 20-byte account payload still decodes fine with friendsOnly=false. Phase 7 adds
    // more optional trailer bytes, read in BOTH the anonymous and account branches (unlike
    // friendsOnly, publicRoom works without an account — see RoomRegistry.register()):
    // [publicRoom:1], and if publicRoom is true, [worldNameLen:1][worldNameBytes][mcVersionLen:1]
    // [mcVersionBytes] (both UTF-8). Older payloads without this trailer (or missing just the
    // mcVersion part of it) decode with publicRoom=false/mcVersion="" — readShortString()
    // tolerates a short/absent buffer on its own, see its doc comment.

    public static byte[] encodeRegister(int maxPlayers, int currentPlayerCount) {
        return new byte[]{MAGIC, TYPE_REGISTER, (byte) maxPlayers, (byte) currentPlayerCount};
    }

    /** As {@link #encodeRegister(int, int)}, but for an anonymous host that wants to appear in the public game browser (Phase 7) — see RoomRegistry.listPublicRooms(). {@code worldName}/{@code mcVersion} are ignored (and not even written) when {@code publicRoom} is false. */
    public static byte[] encodeRegisterAnonymous(int maxPlayers, int currentPlayerCount, boolean publicRoom, String worldName, String mcVersion) {
        byte[] worldNameBytes = publicRoom ? worldName.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] mcVersionBytes = publicRoom ? mcVersion.getBytes(StandardCharsets.UTF_8) : new byte[0];
        int size = 2 + 1 + 1 + 1 + 1 + (publicRoom ? 1 + worldNameBytes.length + 1 + mcVersionBytes.length : 0);
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(MAGIC);
        buf.put(TYPE_REGISTER);
        buf.put((byte) maxPlayers);
        buf.put((byte) currentPlayerCount);
        buf.put((byte) 0); // hasAccount = false
        buf.put((byte) (publicRoom ? 1 : 0));
        if (publicRoom) {
            writeShortString(buf, worldNameBytes);
            writeShortString(buf, mcVersionBytes);
        }
        return buf.array();
    }

    /**
     * Kept byte-for-byte identical to its pre-Phase-7 form (no publicRoom trailer at all, not
     * even a false one) — {@link RendezvousProtocolTest#oldAccountRegisterPayloadWithoutFriendsOnlyByteStillDecodes()}
     * relies on this exact shape (friendsOnly as the very last byte) to simulate a genuinely
     * pre-Phase-6 payload by truncation. Use the 7-arg overload below to also set publicRoom/worldName.
     */
    public static byte[] encodeRegisterWithAccount(int maxPlayers, int currentPlayerCount, UUID accountId, byte[] sessionToken, boolean friendsOnly) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 1 + 1 + 1 + 16 + sessionToken.length + 1);
        buf.put(MAGIC);
        buf.put(TYPE_REGISTER);
        buf.put((byte) maxPlayers);
        buf.put((byte) currentPlayerCount);
        buf.put((byte) 1);
        buf.putLong(accountId.getMostSignificantBits());
        buf.putLong(accountId.getLeastSignificantBits());
        buf.put(sessionToken);
        buf.put((byte) (friendsOnly ? 1 : 0));
        return buf.array();
    }

    /** As {@link #encodeRegisterWithAccount(int, int, UUID, byte[], boolean)}, but also carries the public-game-browser flag/label/version (Phase 7) — see RoomRegistry.register(). */
    public static byte[] encodeRegisterWithAccount(int maxPlayers, int currentPlayerCount, UUID accountId, byte[] sessionToken,
                                                     boolean friendsOnly, boolean publicRoom, String worldName, String mcVersion) {
        byte[] worldNameBytes = publicRoom ? worldName.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] mcVersionBytes = publicRoom ? mcVersion.getBytes(StandardCharsets.UTF_8) : new byte[0];
        int size = 2 + 1 + 1 + 1 + 16 + sessionToken.length + 1 + 1 + (publicRoom ? 1 + worldNameBytes.length + 1 + mcVersionBytes.length : 0);
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(MAGIC);
        buf.put(TYPE_REGISTER);
        buf.put((byte) maxPlayers);
        buf.put((byte) currentPlayerCount);
        buf.put((byte) 1);
        buf.putLong(accountId.getMostSignificantBits());
        buf.putLong(accountId.getLeastSignificantBits());
        buf.put(sessionToken);
        buf.put((byte) (friendsOnly ? 1 : 0));
        buf.put((byte) (publicRoom ? 1 : 0));
        if (publicRoom) {
            writeShortString(buf, worldNameBytes);
            writeShortString(buf, mcVersionBytes);
        }
        return buf.array();
    }

    public static Register decodeRegister(byte[] data, int length) {
        int maxPlayers = data[2] & 0xFF;
        int currentPlayerCount = data[3] & 0xFF;
        if (length <= 4) {
            return new Register(maxPlayers, currentPlayerCount, Optional.empty(), false, false, "", "");
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 4, length - 4);
        boolean hasAccount = buf.get() != 0;
        if (!hasAccount) {
            boolean publicRoom = buf.remaining() >= 1 && buf.get() != 0;
            String worldName = publicRoom ? readShortString(buf) : "";
            String mcVersion = publicRoom ? readShortString(buf) : "";
            return new Register(maxPlayers, currentPlayerCount, Optional.empty(), false, publicRoom, worldName, mcVersion);
        }
        UUID accountId = new UUID(buf.getLong(), buf.getLong());
        byte[] sessionToken = new byte[16];
        buf.get(sessionToken);
        boolean friendsOnly = buf.remaining() >= 1 && buf.get() != 0;
        boolean publicRoom = buf.remaining() >= 1 && buf.get() != 0;
        String worldName = publicRoom ? readShortString(buf) : "";
        String mcVersion = publicRoom ? readShortString(buf) : "";
        return new Register(maxPlayers, currentPlayerCount, Optional.of(new AccountRef(accountId, sessionToken)), friendsOnly, publicRoom, worldName, mcVersion);
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
    // Optional trailer (Phase 5): [hasToken:1][sessionToken:16] — see Join's doc comment.
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
    // Optional trailer (Phase 5): [hasAccount:1][accountId:16] — only ever sent to the HOST
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

    // ---- ROOM_LIST: client -> server (Phase 7, anonymous poll — no session, anyone can ask) ----

    public static byte[] encodeRoomList() {
        return new byte[]{MAGIC, TYPE_ROOM_LIST};
    }

    // ---- ROOM_LIST_REPLY: server -> client ----

    public static byte[] encodeRoomListReply(List<PublicRoom> rooms) {
        int size = 2 + 1;
        for (PublicRoom r : rooms) {
            size += 1 + r.code().getBytes(StandardCharsets.US_ASCII).length
                    + 1 + 1
                    + 1 + r.hostDisplayName().getBytes(StandardCharsets.UTF_8).length
                    + 1 + r.worldName().getBytes(StandardCharsets.UTF_8).length
                    + 1 + r.mcVersion().getBytes(StandardCharsets.UTF_8).length;
        }
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(MAGIC);
        buf.put(TYPE_ROOM_LIST_REPLY);
        buf.put((byte) rooms.size());
        for (PublicRoom r : rooms) {
            byte[] codeBytes = r.code().getBytes(StandardCharsets.US_ASCII);
            buf.put((byte) codeBytes.length);
            buf.put(codeBytes);
            buf.put((byte) r.maxPlayers());
            buf.put((byte) r.currentPlayerCount());
            writeShortString(buf, r.hostDisplayName().getBytes(StandardCharsets.UTF_8));
            writeShortString(buf, r.worldName().getBytes(StandardCharsets.UTF_8));
            writeShortString(buf, r.mcVersion().getBytes(StandardCharsets.UTF_8));
        }
        return buf.array();
    }

    public static RoomListReply decodeRoomListReply(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        int count = buf.get() & 0xFF;
        List<PublicRoom> rooms = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int codeLen = buf.get() & 0xFF;
            byte[] codeBytes = new byte[codeLen];
            buf.get(codeBytes);
            String code = new String(codeBytes, StandardCharsets.US_ASCII);
            int maxPlayers = buf.get() & 0xFF;
            int currentPlayerCount = buf.get() & 0xFF;
            String hostDisplayName = readShortString(buf);
            String worldName = readShortString(buf);
            String mcVersion = readShortString(buf);
            rooms.add(new PublicRoom(code, maxPlayers, currentPlayerCount, hostDisplayName, worldName, mcVersion));
        }
        return new RoomListReply(rooms);
    }

    private static void writeShortString(ByteBuffer buf, byte[] utf8Bytes) {
        buf.put((byte) utf8Bytes.length);
        buf.put(utf8Bytes);
    }

    /** Returns "" instead of throwing on a missing/truncated buffer — lets REGISTER's Phase 7 trailer decode two independent optional strings (worldName, mcVersion) without each needing its own explicit remaining()-guard at the call site. */
    private static String readShortString(ByteBuffer buf) {
        if (buf.remaining() < 1) {
            return "";
        }
        int len = buf.get() & 0xFF;
        if (buf.remaining() < len) {
            return "";
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
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
