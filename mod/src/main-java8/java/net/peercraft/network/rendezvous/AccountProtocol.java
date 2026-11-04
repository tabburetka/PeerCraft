package net.peercraft.network.rendezvous;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wire codec for the account/friends/presence control protocol — same {@link RendezvousProtocol#MAGIC}
 * byte and dispatch convention as {@link RendezvousProtocol}, kept in its own file purely so that
 * file doesn't balloon past its original room-pairing scope. Duplicated between the rendezvous-server
 * project and the mod exactly like RendezvousProtocol — keep both copies byte-for-byte compatible;
 * both have pinned-byte tests to catch drift early.
 *
 * <p><b>Java 8 backport</b> of {@code src/main/java/.../AccountProtocol.java} for the
 * Minecraft 1.16.5 target: every {@code record} carrier is a hand-written final class with
 * the same field set and accessor names. None are used as map keys or compared with
 * {@code equals} anywhere in the mod, so (like the record call sites) they carry no
 * value {@code equals}/{@code hashCode}. The wire format is byte-for-byte unchanged.
 *
 * Auth (0x20-0x2B, Phase 1) and friends (0x30-0x3B, Phase 3) are implemented. Presence (0x40-0x41)
 * type bytes are reserved here to lock in numbering for Phase 4, but have no encode/decode methods
 * yet. {@code TYPE_FRIEND_LIST_REPLY}'s per-friend {@code status}/{@code roomCode} fields are part
 * of the wire format already (so it doesn't need to break again later) but are always
 * offline/empty until Phase 4 wires in real presence data.
 */
public final class AccountProtocol {

    // ---- Auth (0x20-0x2B) ----
    public static final byte TYPE_AUTH_LICENSED_BEGIN = 0x20;
    public static final byte TYPE_AUTH_SERVER_ID = 0x21;
    public static final byte TYPE_AUTH_LICENSED_CONFIRM = 0x22;
    public static final byte TYPE_ACCOUNT_REGISTER = 0x23;
    public static final byte TYPE_ACCOUNT_LOGIN_BEGIN = 0x24;
    public static final byte TYPE_ACCOUNT_LOGIN_CHALLENGE = 0x25;
    public static final byte TYPE_ACCOUNT_LOGIN_RESPONSE = 0x26;
    public static final byte TYPE_ACCOUNT_LOGIN_REMEMBER = 0x27;
    public static final byte TYPE_AUTH_OK = 0x28;
    public static final byte TYPE_AUTH_FAIL = 0x29;
    public static final byte TYPE_ACCOUNT_RENAME = 0x2A;
    public static final byte TYPE_ACCOUNT_RENAME_ACK = 0x2B;

    // ---- Friends (0x30-0x3B) — reserved, not yet implemented ----
    public static final byte TYPE_FRIEND_CODE_LOOKUP = 0x30;
    public static final byte TYPE_FRIEND_CODE_LOOKUP_REPLY = 0x31;
    public static final byte TYPE_FRIEND_REQUEST_SEND = 0x32;
    // TYPE_FRIEND_REQUEST_ACK doubles as the generic ack for SEND (0x32), RESPOND (0x36) and
    // REMOVE (0x37) — same "one generic ack reused for several mutating requests" convention
    // as RendezvousProtocol.TYPE_JOIN_FAIL for REGISTER.
    public static final byte TYPE_FRIEND_REQUEST_ACK = 0x33;
    public static final byte TYPE_FRIEND_REQUEST_LIST = 0x34;
    public static final byte TYPE_FRIEND_REQUEST_LIST_REPLY = 0x35;
    public static final byte TYPE_FRIEND_REQUEST_RESPOND = 0x36;
    public static final byte TYPE_FRIEND_REMOVE = 0x37;
    public static final byte TYPE_FRIEND_LIST = 0x38;
    public static final byte TYPE_FRIEND_LIST_REPLY = 0x39;
    public static final byte TYPE_SEARCH_ACCOUNTS = 0x3A;
    public static final byte TYPE_SEARCH_ACCOUNTS_REPLY = 0x3B;

    public static final int STATUS_OFFLINE = 0;
    public static final int STATUS_ONLINE = 1;
    public static final int STATUS_HOSTING = 2;

    // ---- Presence (0x40-0x41) — reserved, not yet implemented ----
    public static final byte TYPE_PRESENCE_HEARTBEAT = 0x40;
    public static final byte TYPE_PRESENCE_STOP = 0x41;

    public static final byte REASON_MOJANG_VERIFICATION_FAILED = 1;
    public static final byte REASON_USERNAME_INVALID = 2;
    public static final byte REASON_BAD_CREDENTIALS = 3;
    public static final byte REASON_UNKNOWN_ACCOUNT = 4;
    public static final byte REASON_RATE_LIMITED = 5;
    public static final byte REASON_UNKNOWN_REQUEST_ID = 6;
    public static final byte REASON_MOJANG_UNAVAILABLE = 7;
    public static final byte REASON_NAME_INVALID = 8;
    public static final byte REASON_LICENSED_CANNOT_RENAME = 9;
    public static final byte REASON_ALREADY_FRIENDS = 10;
    public static final byte REASON_INVALID_TARGET = 11;
    public static final byte REASON_REQUEST_NOT_FOUND = 12;
    public static final byte REASON_STORAGE_UNAVAILABLE = 13;

    public static final int TOKEN_LENGTH = 16;
    public static final int FRIEND_CODE_LENGTH = 6;
    public static final int SALT_LENGTH = 16;
    public static final int PASSWORD_HASH_LENGTH = 32;
    public static final int CHALLENGE_LENGTH = 16;
    public static final int HMAC_LENGTH = 32;

    private AccountProtocol() {
    }

    public static final class LicensedBegin {
        private final String username;

        public LicensedBegin(String username) {
            this.username = username;
        }

        public String username() {
            return username;
        }
    }

    public static final class ServerIdChallenge {
        private final long requestId;
        private final String serverId;

        public ServerIdChallenge(long requestId, String serverId) {
            this.requestId = requestId;
            this.serverId = serverId;
        }

        public long requestId() {
            return requestId;
        }

        public String serverId() {
            return serverId;
        }
    }

    public static final class LicensedConfirm {
        private final long requestId;

        public LicensedConfirm(long requestId) {
            this.requestId = requestId;
        }

        public long requestId() {
            return requestId;
        }
    }

    public static final class AccountRegister {
        private final String username;
        private final byte[] salt;
        private final byte[] passwordHash;

        public AccountRegister(String username, byte[] salt, byte[] passwordHash) {
            this.username = username;
            this.salt = salt;
            this.passwordHash = passwordHash;
        }

        public String username() {
            return username;
        }

        public byte[] salt() {
            return salt;
        }

        public byte[] passwordHash() {
            return passwordHash;
        }
    }

    /** Exactly one of {@code accountId}/{@code friendCode} is populated, selected by {@code byFriendCode}. */
    public static final class LoginBegin {
        private final boolean byFriendCode;
        private final UUID accountId;
        private final String friendCode;

        public LoginBegin(boolean byFriendCode, UUID accountId, String friendCode) {
            this.byFriendCode = byFriendCode;
            this.accountId = accountId;
            this.friendCode = friendCode;
        }

        public boolean byFriendCode() {
            return byFriendCode;
        }

        public UUID accountId() {
            return accountId;
        }

        public String friendCode() {
            return friendCode;
        }
    }

    /**
     * {@code salt} is the account's stored PBKDF2 salt — included here (not just at
     * registration) because a login can happen from a brand-new device that never saw it
     * before; without it the client couldn't rederive the same hash to answer the challenge.
     */
    public static final class LoginChallenge {
        private final long requestId;
        private final byte[] salt;
        private final byte[] challenge;

        public LoginChallenge(long requestId, byte[] salt, byte[] challenge) {
            this.requestId = requestId;
            this.salt = salt;
            this.challenge = challenge;
        }

        public long requestId() {
            return requestId;
        }

        public byte[] salt() {
            return salt;
        }

        public byte[] challenge() {
            return challenge;
        }
    }

    public static final class LoginResponse {
        private final long requestId;
        private final byte[] hmac;

        public LoginResponse(long requestId, byte[] hmac) {
            this.requestId = requestId;
            this.hmac = hmac;
        }

        public long requestId() {
            return requestId;
        }

        public byte[] hmac() {
            return hmac;
        }
    }

    public static final class LoginRemember {
        private final UUID accountId;
        private final byte[] rememberToken;

        public LoginRemember(UUID accountId, byte[] rememberToken) {
            this.accountId = accountId;
            this.rememberToken = rememberToken;
        }

        public UUID accountId() {
            return accountId;
        }

        public byte[] rememberToken() {
            return rememberToken;
        }
    }

    public static final class AuthOk {
        private final UUID accountId;
        private final byte[] sessionToken;
        private final byte[] rememberToken;
        private final boolean licensed;
        private final String friendCode;
        private final String displayName;

        public AuthOk(UUID accountId, byte[] sessionToken, byte[] rememberToken, boolean licensed, String friendCode, String displayName) {
            this.accountId = accountId;
            this.sessionToken = sessionToken;
            this.rememberToken = rememberToken;
            this.licensed = licensed;
            this.friendCode = friendCode;
            this.displayName = displayName;
        }

        public UUID accountId() {
            return accountId;
        }

        public byte[] sessionToken() {
            return sessionToken;
        }

        public byte[] rememberToken() {
            return rememberToken;
        }

        public boolean licensed() {
            return licensed;
        }

        public String friendCode() {
            return friendCode;
        }

        public String displayName() {
            return displayName;
        }
    }

    public static final class AuthFail {
        private final long requestId;
        private final byte reason;

        public AuthFail(long requestId, byte reason) {
            this.requestId = requestId;
            this.reason = reason;
        }

        public long requestId() {
            return requestId;
        }

        public byte reason() {
            return reason;
        }
    }

    public static final class AccountRename {
        private final byte[] sessionToken;
        private final String newName;

        public AccountRename(byte[] sessionToken, String newName) {
            this.sessionToken = sessionToken;
            this.newName = newName;
        }

        public byte[] sessionToken() {
            return sessionToken;
        }

        public String newName() {
            return newName;
        }
    }

    public static final class RenameAck {
        private final boolean ok;
        private final byte reason;
        private final String appliedName;

        public RenameAck(boolean ok, byte reason, String appliedName) {
            this.ok = ok;
            this.reason = reason;
            this.appliedName = appliedName;
        }

        public boolean ok() {
            return ok;
        }

        public byte reason() {
            return reason;
        }

        public String appliedName() {
            return appliedName;
        }
    }

    public static final class FriendCodeLookup {
        private final byte[] sessionToken;
        private final String friendCode;

        public FriendCodeLookup(byte[] sessionToken, String friendCode) {
            this.sessionToken = sessionToken;
            this.friendCode = friendCode;
        }

        public byte[] sessionToken() {
            return sessionToken;
        }

        public String friendCode() {
            return friendCode;
        }
    }

    /** {@code accountId}/{@code licensed}/{@code displayName} are meaningless when {@code found} is false. */
    public static final class FriendCodeLookupReply {
        private final boolean found;
        private final UUID accountId;
        private final boolean licensed;
        private final String displayName;

        public FriendCodeLookupReply(boolean found, UUID accountId, boolean licensed, String displayName) {
            this.found = found;
            this.accountId = accountId;
            this.licensed = licensed;
            this.displayName = displayName;
        }

        public boolean found() {
            return found;
        }

        public UUID accountId() {
            return accountId;
        }

        public boolean licensed() {
            return licensed;
        }

        public String displayName() {
            return displayName;
        }
    }

    public static final class FriendRequestSend {
        private final byte[] sessionToken;
        private final UUID targetAccountId;

        public FriendRequestSend(byte[] sessionToken, UUID targetAccountId) {
            this.sessionToken = sessionToken;
            this.targetAccountId = targetAccountId;
        }

        public byte[] sessionToken() {
            return sessionToken;
        }

        public UUID targetAccountId() {
            return targetAccountId;
        }
    }

    /** Generic ack — see {@link #TYPE_FRIEND_REQUEST_ACK}'s doc comment for which requests reuse it. */
    public static final class FriendRequestAck {
        private final boolean ok;
        private final byte reason;

        public FriendRequestAck(boolean ok, byte reason) {
            this.ok = ok;
            this.reason = reason;
        }

        public boolean ok() {
            return ok;
        }

        public byte reason() {
            return reason;
        }
    }

    public static final class FriendRequestListQuery {
        private final byte[] sessionToken;

        public FriendRequestListQuery(byte[] sessionToken) {
            this.sessionToken = sessionToken;
        }

        public byte[] sessionToken() {
            return sessionToken;
        }
    }

    public static final class IncomingRequest {
        private final UUID fromAccountId;
        private final boolean licensed;
        private final String displayName;

        public IncomingRequest(UUID fromAccountId, boolean licensed, String displayName) {
            this.fromAccountId = fromAccountId;
            this.licensed = licensed;
            this.displayName = displayName;
        }

        public UUID fromAccountId() {
            return fromAccountId;
        }

        public boolean licensed() {
            return licensed;
        }

        public String displayName() {
            return displayName;
        }
    }

    public static final class FriendRequestListReply {
        private final List<IncomingRequest> requests;

        public FriendRequestListReply(List<IncomingRequest> requests) {
            this.requests = requests;
        }

        public List<IncomingRequest> requests() {
            return requests;
        }
    }

    public static final class FriendRequestRespond {
        private final byte[] sessionToken;
        private final UUID fromAccountId;
        private final boolean accept;

        public FriendRequestRespond(byte[] sessionToken, UUID fromAccountId, boolean accept) {
            this.sessionToken = sessionToken;
            this.fromAccountId = fromAccountId;
            this.accept = accept;
        }

        public byte[] sessionToken() {
            return sessionToken;
        }

        public UUID fromAccountId() {
            return fromAccountId;
        }

        public boolean accept() {
            return accept;
        }
    }

    public static final class FriendRemove {
        private final byte[] sessionToken;
        private final UUID friendAccountId;

        public FriendRemove(byte[] sessionToken, UUID friendAccountId) {
            this.sessionToken = sessionToken;
            this.friendAccountId = friendAccountId;
        }

        public byte[] sessionToken() {
            return sessionToken;
        }

        public UUID friendAccountId() {
            return friendAccountId;
        }
    }

    public static final class FriendListQuery {
        private final byte[] sessionToken;

        public FriendListQuery(byte[] sessionToken) {
            this.sessionToken = sessionToken;
        }

        public byte[] sessionToken() {
            return sessionToken;
        }
    }

    /** {@code status} is one of {@link #STATUS_OFFLINE}/{@link #STATUS_ONLINE}/{@link #STATUS_HOSTING}; {@code roomCode} is only meaningful when HOSTING. */
    public static final class FriendEntry {
        private final UUID accountId;
        private final boolean licensed;
        private final String displayName;
        private final int status;
        private final String roomCode;

        public FriendEntry(UUID accountId, boolean licensed, String displayName, int status, String roomCode) {
            this.accountId = accountId;
            this.licensed = licensed;
            this.displayName = displayName;
            this.status = status;
            this.roomCode = roomCode;
        }

        public UUID accountId() {
            return accountId;
        }

        public boolean licensed() {
            return licensed;
        }

        public String displayName() {
            return displayName;
        }

        public int status() {
            return status;
        }

        public String roomCode() {
            return roomCode;
        }
    }

    public static final class FriendListReply {
        private final List<FriendEntry> friends;

        public FriendListReply(List<FriendEntry> friends) {
            this.friends = friends;
        }

        public List<FriendEntry> friends() {
            return friends;
        }
    }

    public static final class SearchAccounts {
        private final byte[] sessionToken;
        private final String query;

        public SearchAccounts(byte[] sessionToken, String query) {
            this.sessionToken = sessionToken;
            this.query = query;
        }

        public byte[] sessionToken() {
            return sessionToken;
        }

        public String query() {
            return query;
        }
    }

    public static final class SearchResult {
        private final UUID accountId;
        private final boolean licensed;
        private final String displayName;

        public SearchResult(UUID accountId, boolean licensed, String displayName) {
            this.accountId = accountId;
            this.licensed = licensed;
            this.displayName = displayName;
        }

        public UUID accountId() {
            return accountId;
        }

        public boolean licensed() {
            return licensed;
        }

        public String displayName() {
            return displayName;
        }
    }

    public static final class SearchAccountsReply {
        private final List<SearchResult> results;

        public SearchAccountsReply(List<SearchResult> results) {
            this.results = results;
        }

        public List<SearchResult> results() {
            return results;
        }
    }

    private static void putUuid(ByteBuffer buf, UUID uuid) {
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
    }

    private static UUID getUuid(ByteBuffer buf) {
        long msb = buf.getLong();
        long lsb = buf.getLong();
        return new UUID(msb, lsb);
    }

    private static void putToken(ByteBuffer buf, byte[] token, int expectedLength) {
        if (token.length != expectedLength) {
            throw new IllegalArgumentException("Expected " + expectedLength + "-byte token, got " + token.length);
        }
        buf.put(token);
    }

    private static byte[] getToken(ByteBuffer buf, int length) {
        byte[] token = new byte[length];
        buf.get(token);
        return token;
    }

    private static void putShortString(ByteBuffer buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        buf.put((byte) bytes.length);
        buf.put(bytes);
    }

    private static String getShortString(ByteBuffer buf) {
        int len = buf.get() & 0xFF;
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void putFriendCode(ByteBuffer buf, String friendCode) {
        byte[] bytes = friendCode.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length != FRIEND_CODE_LENGTH) {
            throw new IllegalArgumentException("Friend code must be " + FRIEND_CODE_LENGTH + " ASCII characters: " + friendCode);
        }
        buf.put(bytes);
    }

    private static String getFriendCode(ByteBuffer buf) {
        byte[] bytes = new byte[FRIEND_CODE_LENGTH];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    // ---- TYPE_AUTH_LICENSED_BEGIN: client -> server ----

    public static byte[] encodeLicensedBegin(String username) {
        byte[] nameBytes = username.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + 1 + nameBytes.length);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_AUTH_LICENSED_BEGIN);
        buf.put((byte) nameBytes.length);
        buf.put(nameBytes);
        return buf.array();
    }

    public static LicensedBegin decodeLicensedBegin(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        return new LicensedBegin(getShortString(buf));
    }

    // ---- TYPE_AUTH_SERVER_ID: server -> client ----

    public static byte[] encodeServerIdChallenge(long requestId, String serverId) {
        byte[] serverIdBytes = serverId.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(2 + 8 + 1 + serverIdBytes.length);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_AUTH_SERVER_ID);
        buf.putLong(requestId);
        buf.put((byte) serverIdBytes.length);
        buf.put(serverIdBytes);
        return buf.array();
    }

    public static ServerIdChallenge decodeServerIdChallenge(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        long requestId = buf.getLong();
        int len = buf.get() & 0xFF;
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new ServerIdChallenge(requestId, new String(bytes, StandardCharsets.US_ASCII));
    }

    // ---- TYPE_AUTH_LICENSED_CONFIRM: client -> server ----

    public static byte[] encodeLicensedConfirm(long requestId) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 8);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_AUTH_LICENSED_CONFIRM);
        buf.putLong(requestId);
        return buf.array();
    }

    public static LicensedConfirm decodeLicensedConfirm(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        return new LicensedConfirm(buf.getLong());
    }

    // ---- TYPE_ACCOUNT_REGISTER: client -> server ----

    public static byte[] encodeAccountRegister(String username, byte[] salt, byte[] passwordHash) {
        byte[] nameBytes = username.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + 1 + nameBytes.length + SALT_LENGTH + PASSWORD_HASH_LENGTH);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_ACCOUNT_REGISTER);
        buf.put((byte) nameBytes.length);
        buf.put(nameBytes);
        putToken(buf, salt, SALT_LENGTH);
        putToken(buf, passwordHash, PASSWORD_HASH_LENGTH);
        return buf.array();
    }

    public static AccountRegister decodeAccountRegister(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        String username = getShortString(buf);
        byte[] salt = getToken(buf, SALT_LENGTH);
        byte[] hash = getToken(buf, PASSWORD_HASH_LENGTH);
        return new AccountRegister(username, salt, hash);
    }

    // ---- TYPE_ACCOUNT_LOGIN_BEGIN: client -> server ----

    public static byte[] encodeLoginBeginByAccountId(UUID accountId) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 1 + 16);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_ACCOUNT_LOGIN_BEGIN);
        buf.put((byte) 0);
        putUuid(buf, accountId);
        return buf.array();
    }

    public static byte[] encodeLoginBeginByFriendCode(String friendCode) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 1 + FRIEND_CODE_LENGTH);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_ACCOUNT_LOGIN_BEGIN);
        buf.put((byte) 1);
        putFriendCode(buf, friendCode);
        return buf.array();
    }

    public static LoginBegin decodeLoginBegin(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        int idType = buf.get() & 0xFF;
        if (idType == 0) {
            return new LoginBegin(false, getUuid(buf), null);
        }
        return new LoginBegin(true, null, getFriendCode(buf));
    }

    // ---- TYPE_ACCOUNT_LOGIN_CHALLENGE: server -> client ----

    public static byte[] encodeLoginChallenge(long requestId, byte[] salt, byte[] challenge) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 8 + SALT_LENGTH + CHALLENGE_LENGTH);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_ACCOUNT_LOGIN_CHALLENGE);
        buf.putLong(requestId);
        putToken(buf, salt, SALT_LENGTH);
        putToken(buf, challenge, CHALLENGE_LENGTH);
        return buf.array();
    }

    public static LoginChallenge decodeLoginChallenge(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        long requestId = buf.getLong();
        byte[] salt = getToken(buf, SALT_LENGTH);
        byte[] challenge = getToken(buf, CHALLENGE_LENGTH);
        return new LoginChallenge(requestId, salt, challenge);
    }

    // ---- TYPE_ACCOUNT_LOGIN_RESPONSE: client -> server ----

    public static byte[] encodeLoginResponse(long requestId, byte[] hmac) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 8 + HMAC_LENGTH);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_ACCOUNT_LOGIN_RESPONSE);
        buf.putLong(requestId);
        putToken(buf, hmac, HMAC_LENGTH);
        return buf.array();
    }

    public static LoginResponse decodeLoginResponse(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        long requestId = buf.getLong();
        return new LoginResponse(requestId, getToken(buf, HMAC_LENGTH));
    }

    // ---- TYPE_ACCOUNT_LOGIN_REMEMBER: client -> server (silent relogin) ----

    public static byte[] encodeLoginRemember(UUID accountId, byte[] rememberToken) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 16 + TOKEN_LENGTH);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_ACCOUNT_LOGIN_REMEMBER);
        putUuid(buf, accountId);
        putToken(buf, rememberToken, TOKEN_LENGTH);
        return buf.array();
    }

    public static LoginRemember decodeLoginRemember(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        UUID accountId = getUuid(buf);
        return new LoginRemember(accountId, getToken(buf, TOKEN_LENGTH));
    }

    // ---- TYPE_AUTH_OK: server -> client ----

    public static byte[] encodeAuthOk(UUID accountId, byte[] sessionToken, byte[] rememberToken, boolean licensed,
                                       String friendCode, String displayName) {
        byte[] nameBytes = displayName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + 16 + TOKEN_LENGTH + TOKEN_LENGTH + 1 + FRIEND_CODE_LENGTH + 1 + nameBytes.length);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_AUTH_OK);
        putUuid(buf, accountId);
        putToken(buf, sessionToken, TOKEN_LENGTH);
        putToken(buf, rememberToken, TOKEN_LENGTH);
        buf.put((byte) (licensed ? 1 : 0));
        putFriendCode(buf, friendCode);
        buf.put((byte) nameBytes.length);
        buf.put(nameBytes);
        return buf.array();
    }

    public static AuthOk decodeAuthOk(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        UUID accountId = getUuid(buf);
        byte[] sessionToken = getToken(buf, TOKEN_LENGTH);
        byte[] rememberToken = getToken(buf, TOKEN_LENGTH);
        boolean licensed = buf.get() != 0;
        String friendCode = getFriendCode(buf);
        String displayName = getShortString(buf);
        return new AuthOk(accountId, sessionToken, rememberToken, licensed, friendCode, displayName);
    }

    // ---- TYPE_AUTH_FAIL: server -> client ----

    public static byte[] encodeAuthFail(long requestId, byte reason) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 8 + 1);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_AUTH_FAIL);
        buf.putLong(requestId);
        buf.put(reason);
        return buf.array();
    }

    public static AuthFail decodeAuthFail(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        long requestId = buf.getLong();
        return new AuthFail(requestId, buf.get());
    }

    // ---- TYPE_ACCOUNT_RENAME: client -> server ----

    public static byte[] encodeAccountRename(byte[] sessionToken, String newName) {
        byte[] nameBytes = newName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + TOKEN_LENGTH + 1 + nameBytes.length);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_ACCOUNT_RENAME);
        putToken(buf, sessionToken, TOKEN_LENGTH);
        buf.put((byte) nameBytes.length);
        buf.put(nameBytes);
        return buf.array();
    }

    public static AccountRename decodeAccountRename(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        byte[] sessionToken = getToken(buf, TOKEN_LENGTH);
        String newName = getShortString(buf);
        return new AccountRename(sessionToken, newName);
    }

    // ---- TYPE_ACCOUNT_RENAME_ACK: server -> client ----

    public static byte[] encodeRenameAck(boolean ok, byte reason, String appliedName) {
        byte[] nameBytes = appliedName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + 1 + 1 + 1 + nameBytes.length);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_ACCOUNT_RENAME_ACK);
        buf.put((byte) (ok ? 1 : 0));
        buf.put(reason);
        buf.put((byte) nameBytes.length);
        buf.put(nameBytes);
        return buf.array();
    }

    public static RenameAck decodeRenameAck(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        boolean ok = buf.get() != 0;
        byte reason = buf.get();
        String appliedName = getShortString(buf);
        return new RenameAck(ok, reason, appliedName);
    }

    // ---- TYPE_FRIEND_CODE_LOOKUP: client -> server ----

    public static byte[] encodeFriendCodeLookup(byte[] sessionToken, String friendCode) {
        ByteBuffer buf = ByteBuffer.allocate(2 + TOKEN_LENGTH + FRIEND_CODE_LENGTH);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_FRIEND_CODE_LOOKUP);
        putToken(buf, sessionToken, TOKEN_LENGTH);
        putFriendCode(buf, friendCode);
        return buf.array();
    }

    public static FriendCodeLookup decodeFriendCodeLookup(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        byte[] sessionToken = getToken(buf, TOKEN_LENGTH);
        String friendCode = getFriendCode(buf);
        return new FriendCodeLookup(sessionToken, friendCode);
    }

    // ---- TYPE_FRIEND_CODE_LOOKUP_REPLY: server -> client ----

    public static byte[] encodeFriendCodeLookupReply(boolean found, UUID accountId, boolean licensed, String displayName) {
        byte[] nameBytes = displayName.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + 1 + 16 + 1 + 1 + nameBytes.length);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_FRIEND_CODE_LOOKUP_REPLY);
        buf.put((byte) (found ? 1 : 0));
        putUuid(buf, accountId);
        buf.put((byte) (licensed ? 1 : 0));
        buf.put((byte) nameBytes.length);
        buf.put(nameBytes);
        return buf.array();
    }

    public static FriendCodeLookupReply decodeFriendCodeLookupReply(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        boolean found = buf.get() != 0;
        UUID accountId = getUuid(buf);
        boolean licensed = buf.get() != 0;
        String displayName = getShortString(buf);
        return new FriendCodeLookupReply(found, accountId, licensed, displayName);
    }

    // ---- TYPE_FRIEND_REQUEST_SEND: client -> server ----

    public static byte[] encodeFriendRequestSend(byte[] sessionToken, UUID targetAccountId) {
        ByteBuffer buf = ByteBuffer.allocate(2 + TOKEN_LENGTH + 16);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_FRIEND_REQUEST_SEND);
        putToken(buf, sessionToken, TOKEN_LENGTH);
        putUuid(buf, targetAccountId);
        return buf.array();
    }

    public static FriendRequestSend decodeFriendRequestSend(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        byte[] sessionToken = getToken(buf, TOKEN_LENGTH);
        UUID targetAccountId = getUuid(buf);
        return new FriendRequestSend(sessionToken, targetAccountId);
    }

    // ---- TYPE_FRIEND_REQUEST_ACK: server -> client (reused for SEND/RESPOND/REMOVE) ----

    public static byte[] encodeFriendRequestAck(boolean ok, byte reason) {
        return new byte[]{RendezvousProtocol.MAGIC, TYPE_FRIEND_REQUEST_ACK, (byte) (ok ? 1 : 0), reason};
    }

    public static FriendRequestAck decodeFriendRequestAck(byte[] data, int length) {
        return new FriendRequestAck(data[2] != 0, data[3]);
    }

    // ---- TYPE_FRIEND_REQUEST_LIST: client -> server (poll) ----

    public static byte[] encodeFriendRequestListQuery(byte[] sessionToken) {
        ByteBuffer buf = ByteBuffer.allocate(2 + TOKEN_LENGTH);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_FRIEND_REQUEST_LIST);
        putToken(buf, sessionToken, TOKEN_LENGTH);
        return buf.array();
    }

    public static FriendRequestListQuery decodeFriendRequestListQuery(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        return new FriendRequestListQuery(getToken(buf, TOKEN_LENGTH));
    }

    // ---- TYPE_FRIEND_REQUEST_LIST_REPLY: server -> client ----

    public static byte[] encodeFriendRequestListReply(List<IncomingRequest> requests) {
        int size = 2 + 1;
        for (IncomingRequest r : requests) {
            size += 16 + 1 + 1 + r.displayName().getBytes(StandardCharsets.UTF_8).length;
        }
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_FRIEND_REQUEST_LIST_REPLY);
        buf.put((byte) requests.size());
        for (IncomingRequest r : requests) {
            putUuid(buf, r.fromAccountId());
            buf.put((byte) (r.licensed() ? 1 : 0));
            putShortString(buf, r.displayName());
        }
        return buf.array();
    }

    public static FriendRequestListReply decodeFriendRequestListReply(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        int count = buf.get() & 0xFF;
        List<IncomingRequest> requests = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID fromAccountId = getUuid(buf);
            boolean licensed = buf.get() != 0;
            String displayName = getShortString(buf);
            requests.add(new IncomingRequest(fromAccountId, licensed, displayName));
        }
        return new FriendRequestListReply(requests);
    }

    // ---- TYPE_FRIEND_REQUEST_RESPOND: client -> server ----

    public static byte[] encodeFriendRequestRespond(byte[] sessionToken, UUID fromAccountId, boolean accept) {
        ByteBuffer buf = ByteBuffer.allocate(2 + TOKEN_LENGTH + 16 + 1);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_FRIEND_REQUEST_RESPOND);
        putToken(buf, sessionToken, TOKEN_LENGTH);
        putUuid(buf, fromAccountId);
        buf.put((byte) (accept ? 1 : 0));
        return buf.array();
    }

    public static FriendRequestRespond decodeFriendRequestRespond(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        byte[] sessionToken = getToken(buf, TOKEN_LENGTH);
        UUID fromAccountId = getUuid(buf);
        boolean accept = buf.get() != 0;
        return new FriendRequestRespond(sessionToken, fromAccountId, accept);
    }

    // ---- TYPE_FRIEND_REMOVE: client -> server ----

    public static byte[] encodeFriendRemove(byte[] sessionToken, UUID friendAccountId) {
        ByteBuffer buf = ByteBuffer.allocate(2 + TOKEN_LENGTH + 16);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_FRIEND_REMOVE);
        putToken(buf, sessionToken, TOKEN_LENGTH);
        putUuid(buf, friendAccountId);
        return buf.array();
    }

    public static FriendRemove decodeFriendRemove(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        byte[] sessionToken = getToken(buf, TOKEN_LENGTH);
        UUID friendAccountId = getUuid(buf);
        return new FriendRemove(sessionToken, friendAccountId);
    }

    // ---- TYPE_FRIEND_LIST: client -> server (poll) ----

    public static byte[] encodeFriendListQuery(byte[] sessionToken) {
        ByteBuffer buf = ByteBuffer.allocate(2 + TOKEN_LENGTH);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_FRIEND_LIST);
        putToken(buf, sessionToken, TOKEN_LENGTH);
        return buf.array();
    }

    public static FriendListQuery decodeFriendListQuery(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        return new FriendListQuery(getToken(buf, TOKEN_LENGTH));
    }

    // ---- TYPE_FRIEND_LIST_REPLY: server -> client ----

    public static byte[] encodeFriendListReply(List<FriendEntry> friends) {
        int size = 2 + 1;
        for (FriendEntry f : friends) {
            size += 16 + 1 + 1 + f.displayName().getBytes(StandardCharsets.UTF_8).length
                    + 1 + 1 + f.roomCode().getBytes(StandardCharsets.US_ASCII).length;
        }
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_FRIEND_LIST_REPLY);
        buf.put((byte) friends.size());
        for (FriendEntry f : friends) {
            putUuid(buf, f.accountId());
            buf.put((byte) (f.licensed() ? 1 : 0));
            putShortString(buf, f.displayName());
            buf.put((byte) f.status());
            byte[] roomCodeBytes = f.roomCode().getBytes(StandardCharsets.US_ASCII);
            buf.put((byte) roomCodeBytes.length);
            buf.put(roomCodeBytes);
        }
        return buf.array();
    }

    public static FriendListReply decodeFriendListReply(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        int count = buf.get() & 0xFF;
        List<FriendEntry> friends = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID accountId = getUuid(buf);
            boolean licensed = buf.get() != 0;
            String displayName = getShortString(buf);
            int status = buf.get() & 0xFF;
            int roomCodeLen = buf.get() & 0xFF;
            byte[] roomCodeBytes = new byte[roomCodeLen];
            buf.get(roomCodeBytes);
            String roomCode = new String(roomCodeBytes, StandardCharsets.US_ASCII);
            friends.add(new FriendEntry(accountId, licensed, displayName, status, roomCode));
        }
        return new FriendListReply(friends);
    }

    // ---- TYPE_SEARCH_ACCOUNTS: client -> server ----

    public static byte[] encodeSearchAccounts(byte[] sessionToken, String query) {
        byte[] queryBytes = query.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + TOKEN_LENGTH + 1 + queryBytes.length);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_SEARCH_ACCOUNTS);
        putToken(buf, sessionToken, TOKEN_LENGTH);
        buf.put((byte) queryBytes.length);
        buf.put(queryBytes);
        return buf.array();
    }

    public static SearchAccounts decodeSearchAccounts(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        byte[] sessionToken = getToken(buf, TOKEN_LENGTH);
        String query = getShortString(buf);
        return new SearchAccounts(sessionToken, query);
    }

    // ---- TYPE_SEARCH_ACCOUNTS_REPLY: server -> client ----

    public static byte[] encodeSearchAccountsReply(List<SearchResult> results) {
        int size = 2 + 1;
        for (SearchResult r : results) {
            size += 16 + 1 + 1 + r.displayName().getBytes(StandardCharsets.UTF_8).length;
        }
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_SEARCH_ACCOUNTS_REPLY);
        buf.put((byte) results.size());
        for (SearchResult r : results) {
            putUuid(buf, r.accountId());
            buf.put((byte) (r.licensed() ? 1 : 0));
            putShortString(buf, r.displayName());
        }
        return buf.array();
    }

    public static SearchAccountsReply decodeSearchAccountsReply(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        int count = buf.get() & 0xFF;
        List<SearchResult> results = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID accountId = getUuid(buf);
            boolean licensed = buf.get() != 0;
            String displayName = getShortString(buf);
            results.add(new SearchResult(accountId, licensed, displayName));
        }
        return new SearchAccountsReply(results);
    }

    // ---- TYPE_PRESENCE_HEARTBEAT / TYPE_PRESENCE_STOP: client -> server, fire-and-forget (no reply) ----

    public static final class PresenceHeartbeat {
        private final byte[] sessionToken;

        public PresenceHeartbeat(byte[] sessionToken) {
            this.sessionToken = sessionToken;
        }

        public byte[] sessionToken() {
            return sessionToken;
        }
    }

    public static final class PresenceStop {
        private final byte[] sessionToken;

        public PresenceStop(byte[] sessionToken) {
            this.sessionToken = sessionToken;
        }

        public byte[] sessionToken() {
            return sessionToken;
        }
    }

    public static byte[] encodePresenceHeartbeat(byte[] sessionToken) {
        ByteBuffer buf = ByteBuffer.allocate(2 + TOKEN_LENGTH);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_PRESENCE_HEARTBEAT);
        putToken(buf, sessionToken, TOKEN_LENGTH);
        return buf.array();
    }

    public static PresenceHeartbeat decodePresenceHeartbeat(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        return new PresenceHeartbeat(getToken(buf, TOKEN_LENGTH));
    }

    public static byte[] encodePresenceStop(byte[] sessionToken) {
        ByteBuffer buf = ByteBuffer.allocate(2 + TOKEN_LENGTH);
        buf.put(RendezvousProtocol.MAGIC);
        buf.put(TYPE_PRESENCE_STOP);
        putToken(buf, sessionToken, TOKEN_LENGTH);
        return buf.array();
    }

    public static PresenceStop decodePresenceStop(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.get();
        buf.get();
        return new PresenceStop(getToken(buf, TOKEN_LENGTH));
    }
}
