package net.peercraft.network.rendezvous;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 *
 * <p><b>Java 8 backport</b> of {@code src/main/java/.../RendezvousProtocol.java} for the
 * Minecraft 1.16.5 target: the {@code record} carriers are hand-written final classes with
 * identical field sets and accessor names. Only {@link Address} — the one used as a
 * {@link java.util.Map} key (see {@code RendezvousClient}) — carries value
 * {@code equals}/{@code hashCode}; the rest are plain data holders exactly like the record
 * versions are at their (callback-only) call sites. The wire format is byte-for-byte
 * unchanged.
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
    // Host handoff (graceful baton pass): after a handoff the successor's room has a NEW code,
    // so a joiner finds its new host by the successor's (verified, from that client's own
    // account-bearing REGISTER) accountId instead of a code. Anonymous, no session — the same
    // low-stakes read as TYPE_ROOM_LIST, served straight from PresenceRegistry.
    public static final byte TYPE_LOOKUP_HOST = 0x09;
    public static final byte TYPE_LOOKUP_HOST_REPLY = 0x0A;
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
    public static final byte REASON_ACCOUNT_REQUIRED = 6;

    private RendezvousProtocol() {
    }

    public static final class Address {
        private final InetAddress host;
        private final int port;

        public Address(InetAddress host, int port) {
            this.host = host;
            this.port = port;
        }

        public InetAddress host() {
            return host;
        }

        public int port() {
            return port;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Address)) {
                return false;
            }
            Address other = (Address) o;
            return port == other.port && Objects.equals(host, other.host);
        }

        @Override
        public int hashCode() {
            return Objects.hash(host, port);
        }

        @Override
        public String toString() {
            return "Address[host=" + host + ", port=" + port + "]";
        }
    }

    public static final class RoomCreated {
        private final String code;
        private final Address hostAddress;

        public RoomCreated(String code, Address hostAddress) {
            this.code = code;
            this.hostAddress = hostAddress;
        }

        public String code() {
            return code;
        }

        public Address hostAddress() {
            return hostAddress;
        }
    }

    /** {@code sessionToken} present (Phase 5) lets the server resolve — and only the server, from a token only the real owner could have — the joiner's accountId to relay to the host for save-data identity injection. */
    public static final class Join {
        private final String code;
        private final Optional<byte[]> sessionToken;
        private final Optional<ConnectivityAdvertisement> connectivity;
        public Join(String code, Optional<byte[]> sessionToken) { this(code, sessionToken, Optional.empty()); }
        public Join(String code, Optional<byte[]> sessionToken, Optional<ConnectivityAdvertisement> connectivity) {
            this.code = code;
            this.sessionToken = sessionToken;
            this.connectivity = connectivity;
        }
        public String code() { return code; }
        public Optional<byte[]> sessionToken() { return sessionToken; }
        public Optional<ConnectivityAdvertisement> connectivity() { return connectivity; }
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
    public static final class Register {
        private final int maxPlayers;
        private final int currentPlayerCount;
        private final Optional<AccountRef> account;
        private final boolean friendsOnly;
        private final boolean publicRoom;
        private final String worldName;
        private final String mcVersion;
        private final Optional<ConnectivityAdvertisement> connectivity;
        public Register(int maxPlayers, int currentPlayerCount, Optional<AccountRef> account, boolean friendsOnly, boolean publicRoom, String worldName, String mcVersion) { this(maxPlayers, currentPlayerCount, account, friendsOnly, publicRoom, worldName, mcVersion, Optional.empty()); }
        public Register(int maxPlayers, int currentPlayerCount, Optional<AccountRef> account, boolean friendsOnly, boolean publicRoom, String worldName, String mcVersion, Optional<ConnectivityAdvertisement> connectivity) {
            this.maxPlayers = maxPlayers;
            this.currentPlayerCount = currentPlayerCount;
            this.account = account;
            this.friendsOnly = friendsOnly;
            this.publicRoom = publicRoom;
            this.worldName = worldName;
            this.mcVersion = mcVersion;
            this.connectivity = connectivity;
        }
        public int maxPlayers() { return maxPlayers; }
        public int currentPlayerCount() { return currentPlayerCount; }
        public Optional<AccountRef> account() { return account; }
        public boolean friendsOnly() { return friendsOnly; }
        public boolean publicRoom() { return publicRoom; }
        public String worldName() { return worldName; }
        public String mcVersion() { return mcVersion; }
        public Optional<ConnectivityAdvertisement> connectivity() { return connectivity; }
    }

    /** {@code sessionToken} must be re-validated server-side (see RendezvousServer.handleRegister) — a REGISTER must never be trusted to self-report its own accountId unchecked. */
    public static final class AccountRef {
        private final UUID accountId;
        private final byte[] sessionToken;

        public AccountRef(UUID accountId, byte[] sessionToken) {
            this.accountId = accountId;
            this.sessionToken = sessionToken;
        }

        public UUID accountId() {
            return accountId;
        }

        public byte[] sessionToken() {
            return sessionToken;
        }
    }

    /** {@code joinerAccountId} present (Phase 5) only in the copy sent to the HOST, and only when the joiner attached a valid session to their JOIN — see RendezvousServer.handleJoin. */
    public static final class PeerFound {
        private final Address peer;
        private final long token;
        private final Optional<UUID> joinerAccountId;
        private final Optional<NetworkOffer> networkOffer;
        public PeerFound(Address peer, long token, Optional<UUID> joinerAccountId) { this(peer, token, joinerAccountId, Optional.empty()); }
        public PeerFound(Address peer, long token, Optional<UUID> joinerAccountId, Optional<NetworkOffer> networkOffer) {
            this.peer = peer;
            this.token = token;
            this.joinerAccountId = joinerAccountId;
            this.networkOffer = networkOffer;
        }
        public Address peer() { return peer; }
        public long token() { return token; }
        public Optional<UUID> joinerAccountId() { return joinerAccountId; }
        public Optional<NetworkOffer> networkOffer() { return networkOffer; }
    }

    /** One row of the public game browser (Phase 7) — {@code hostDisplayName} is "" for an anonymous host; {@code mcVersion} is the host's running Minecraft version, see Register's doc comment. */
    public static final class PublicRoom {
        private final String code;
        private final int maxPlayers;
        private final int currentPlayerCount;
        private final String hostDisplayName;
        private final String worldName;
        private final String mcVersion;

        public PublicRoom(String code, int maxPlayers, int currentPlayerCount, String hostDisplayName, String worldName, String mcVersion) {
            this.code = code;
            this.maxPlayers = maxPlayers;
            this.currentPlayerCount = currentPlayerCount;
            this.hostDisplayName = hostDisplayName;
            this.worldName = worldName;
            this.mcVersion = mcVersion;
        }

        public String code() {
            return code;
        }

        public int maxPlayers() {
            return maxPlayers;
        }

        public int currentPlayerCount() {
            return currentPlayerCount;
        }

        public String hostDisplayName() {
            return hostDisplayName;
        }

        public String worldName() {
            return worldName;
        }

        public String mcVersion() {
            return mcVersion;
        }
    }

    public static final class RoomListReply {
        private final List<PublicRoom> rooms;

        public RoomListReply(List<PublicRoom> rooms) {
            this.rooms = rooms;
        }

        public List<PublicRoom> rooms() {
            return rooms;
        }
    }

    /** New clients advertise checks independently of relay/account eligibility. */
    public static final class ConnectivityAdvertisement {
        private final boolean relayCapable, relayConsent;
        private final List<Address> candidates;
        private final UUID clientAttemptId;
        public ConnectivityAdvertisement(boolean relayCapable, boolean relayConsent, List<Address> candidates) {
            this(relayCapable, relayConsent, candidates, UUID.randomUUID());
        }
        public ConnectivityAdvertisement(boolean relayCapable, boolean relayConsent, List<Address> candidates, UUID clientAttemptId) {
            if (clientAttemptId == null) throw new IllegalArgumentException("Missing client attempt identity");
            this.relayCapable = relayCapable; this.relayConsent = relayConsent;
            this.candidates = checkedCandidates(candidates); this.clientAttemptId = clientAttemptId;
        }
        public UUID clientAttemptId() { return clientAttemptId; }
        public boolean relayCapable() { return relayCapable; }
        public boolean relayConsent() { return relayConsent; }
        public List<Address> candidates() { return candidates; }
    }

    /** Per-match direct check proof; identical attempt/key on both sides. */
    public static final class NetworkOffer {
        private final UUID attemptId;
        private final byte[] challengeKey;
        private final List<Address> peerCandidates;
        private final boolean relayCapable;
        private final String brokerUrl;
        public NetworkOffer(UUID attemptId, byte[] challengeKey, List<Address> peerCandidates,
                            boolean relayCapable, String brokerUrl) {
            if (attemptId == null || challengeKey == null || challengeKey.length != 32)
                throw new IllegalArgumentException("Invalid connectivity proof");
            if (brokerUrl == null || brokerUrl.getBytes(StandardCharsets.UTF_8).length > 512)
                throw new IllegalArgumentException("Invalid broker URL length");
            this.attemptId = attemptId; this.challengeKey = challengeKey.clone();
            this.peerCandidates = checkedCandidates(peerCandidates);
            this.relayCapable = relayCapable; this.brokerUrl = brokerUrl;
        }
        public UUID attemptId() { return attemptId; }
        public byte[] challengeKey() { return challengeKey.clone(); }
        public List<Address> peerCandidates() { return peerCandidates; }
        public boolean relayCapable() { return relayCapable; }
        public String brokerUrl() { return brokerUrl; }
    }

    private static List<Address> checkedCandidates(List<Address> candidates) {
        if (candidates == null || candidates.size() > 8) throw new IllegalArgumentException("Too many candidates");
        List<Address> copy = new ArrayList<>();
        for (Address address : candidates) {
            if (address == null || address.host() == null || address.port() < 1 || address.port() > 65535
                    || address.host().isAnyLocalAddress() || address.host().isMulticastAddress())
                throw new IllegalArgumentException("Invalid candidate");
            if (!copy.contains(address)) copy.add(address);
        }
        return java.util.Collections.unmodifiableList(copy);
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
        if ((addrLen != 4 && addrLen != 16) || buf.remaining() < addrLen + 2)
            throw new IllegalArgumentException("Invalid endpoint length");
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

    public static final int TYPE_CONNECTIVITY_CHECK = 0x12;
    public static final int DIRECT_CANDIDATE_UPDATE = 5;
    private static final int DIRECT_CONTROL_HEADER = 29;
    private static final int DIRECT_CONTROL_TAG = 16;

    /** Untrusted identifier for looking up an existing match; it grants no permission itself. */
    public static Optional<UUID> directCandidateAttempt(byte[] bytes, int length) {
        if (bytes == null || length < DIRECT_CONTROL_HEADER + DIRECT_CONTROL_TAG || length > bytes.length
                || bytes[0] != MAGIC || (bytes[1] & 255) != TYPE_CONNECTIVITY_CHECK
                || bytes[2] != 1 || bytes[3] != DIRECT_CANDIDATE_UPDATE) return Optional.empty();
        ByteBuffer b = ByteBuffer.wrap(bytes, 5, 16);
        return Optional.of(new UUID(b.getLong(), b.getLong()));
    }
    public static boolean isGlobalCandidate(Address address) {
        if (address == null || address.host() == null || address.port() < 1 || address.port() > 65535) return false;
        InetAddress ip = address.host(); byte[] bytes = ip.getAddress();
        if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                || ip.isSiteLocalAddress() || ip.isMulticastAddress()) return false;
        if (bytes.length == 16) return (bytes[0] & 0xe0) == 0x20;
        if (bytes.length != 4) return false;
        int a = bytes[0] & 255, b = bytes[1] & 255;
        return a != 0 && a < 224 && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 198 && (b == 18 || b == 19));
    }
    public static byte[] encodeDirectCandidate(NetworkOffer offer, boolean hostRole, Address candidate) {
        if (!isGlobalCandidate(candidate)) throw new IllegalArgumentException("Candidate must be global unicast");
        ByteBuffer b = ByteBuffer.allocate(DIRECT_CONTROL_HEADER + addressSize(candidate) + DIRECT_CONTROL_TAG);
        b.put(MAGIC).put((byte) TYPE_CONNECTIVITY_CHECK).put((byte) 1).put((byte) DIRECT_CANDIDATE_UPDATE);
        b.put((byte) (hostRole ? 1 : 0));
        b.putLong(offer.attemptId().getMostSignificantBits()).putLong(offer.attemptId().getLeastSignificantBits());
        b.putLong(0); writeAddress(b, candidate);
        byte[] bytes = b.array(); byte[] tag = directControlTag(java.util.Arrays.copyOf(bytes, bytes.length - DIRECT_CONTROL_TAG), offer.challengeKey());
        System.arraycopy(tag, 0, bytes, bytes.length - DIRECT_CONTROL_TAG, DIRECT_CONTROL_TAG); return bytes;
    }
    public static Optional<Address> decodeDirectCandidate(byte[] bytes, int length, NetworkOffer offer, boolean expectedHostRole) {
        Optional<UUID> attempt = directCandidateAttempt(bytes, length);
        if (!attempt.isPresent() || !attempt.get().equals(offer.attemptId())
                || (bytes[4] & 255) != (expectedHostRole ? 1 : 0) || length > 64) return Optional.empty();
        byte[] expected = directControlTag(java.util.Arrays.copyOf(bytes, length - DIRECT_CONTROL_TAG), offer.challengeKey());
        if (!java.security.MessageDigest.isEqual(expected,
                java.util.Arrays.copyOfRange(bytes, length - DIRECT_CONTROL_TAG, length))) return Optional.empty();
        try {
            ByteBuffer b = ByteBuffer.wrap(bytes, DIRECT_CONTROL_HEADER, length - DIRECT_CONTROL_HEADER - DIRECT_CONTROL_TAG);
            Address candidate = readAddress(b);
            return !b.hasRemaining() && isGlobalCandidate(candidate) ? Optional.of(candidate) : Optional.empty();
        } catch (RuntimeException malformed) { return Optional.empty(); }
    }
    private static byte[] directControlTag(byte[] bytes, byte[] key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return java.util.Arrays.copyOf(mac.doFinal(bytes), DIRECT_CONTROL_TAG);
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is required for candidate updates", unavailable);
        }
    }

    // Connectivity extension: 0xCA, version 1; legacy overloads preserve their exact bytes.
    private static final byte CONNECTIVITY_EXTENSION = (byte) 0xCA;
    private static byte[] append(byte[] base, byte[] extension) {
        byte[] result = java.util.Arrays.copyOf(base, base.length + extension.length);
        System.arraycopy(extension, 0, result, base.length, extension.length); return result;
    }
    private static byte[] encodeConnectivity(ConnectivityAdvertisement advertisement) {
        int size = 20; for (Address a : advertisement.candidates()) size += addressSize(a);
        ByteBuffer b = ByteBuffer.allocate(size);
        b.put(CONNECTIVITY_EXTENSION).put((byte) 1);
        b.putLong(advertisement.clientAttemptId().getMostSignificantBits()).putLong(advertisement.clientAttemptId().getLeastSignificantBits());
        b.put((byte) ((advertisement.relayCapable() ? 1 : 0) | (advertisement.relayConsent() ? 2 : 0)));
        b.put((byte) advertisement.candidates().size());
        for (Address a : advertisement.candidates()) writeAddress(b, a);
        return b.array();
    }
    private static Optional<ConnectivityAdvertisement> readConnectivity(ByteBuffer b) {
        if (!b.hasRemaining()) return Optional.empty();
        if (b.remaining() < 2 || b.get() != CONNECTIVITY_EXTENSION || b.get() != 1) return Optional.empty();
        if (b.remaining() < 18) throw new IllegalArgumentException("Truncated connectivity extension");
        UUID clientAttempt = new UUID(b.getLong(), b.getLong());
        int flags = b.get() & 255, count = b.get() & 255;
        if (count > 8 || (flags & ~3) != 0) throw new IllegalArgumentException("Invalid connectivity extension");
        List<Address> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) candidates.add(readAddress(b));
        return Optional.of(new ConnectivityAdvertisement((flags & 1) != 0, (flags & 2) != 0, candidates, clientAttempt));
    }
    public static byte[] encodeRegisterAnonymous(int maxPlayers, int currentPlayerCount, boolean publicRoom,
                                                String worldName, String mcVersion, ConnectivityAdvertisement connectivity) {
        return append(encodeRegisterAnonymous(maxPlayers, currentPlayerCount, publicRoom, worldName, mcVersion), encodeConnectivity(connectivity));
    }
    public static byte[] encodeRegisterWithAccount(int maxPlayers, int currentPlayerCount, UUID accountId, byte[] sessionToken,
                                                 boolean friendsOnly, boolean publicRoom, String worldName, String mcVersion,
                                                 ConnectivityAdvertisement connectivity) {
        return append(encodeRegisterWithAccount(maxPlayers, currentPlayerCount, accountId, sessionToken, friendsOnly,
                publicRoom, worldName, mcVersion), encodeConnectivity(connectivity));
    }
    public static byte[] encodeJoin(String code, ConnectivityAdvertisement connectivity) {
        return append(append(encodeJoin(code), new byte[] { 0 }), encodeConnectivity(connectivity));
    }
    public static byte[] encodeJoinWithAccount(String code, byte[] token, ConnectivityAdvertisement connectivity) {
        return append(encodeJoinWithAccount(code, token), encodeConnectivity(connectivity));
    }
    public static byte[] encodePeerFoundDetailed(Address peer, long token, Optional<UUID> account, NetworkOffer offer) {
        byte[] base = account.isPresent() ? encodePeerFoundWithAccount(peer, token, account.get())
                : append(encodePeerFound(peer, token), new byte[] { 0 });
        byte[] url = offer.brokerUrl().getBytes(StandardCharsets.UTF_8);
        int size = 2 + 16 + 32 + 1 + 1 + 2 + url.length;
        for (Address a : offer.peerCandidates()) size += addressSize(a);
        ByteBuffer b = ByteBuffer.allocate(size);
        b.put(CONNECTIVITY_EXTENSION).put((byte) 1);
        b.putLong(offer.attemptId().getMostSignificantBits()).putLong(offer.attemptId().getLeastSignificantBits());
        b.put(offer.challengeKey()).put((byte) (offer.relayCapable() ? 1 : 0));
        b.put((byte) offer.peerCandidates().size());
        for (Address a : offer.peerCandidates()) writeAddress(b, a);
        b.putShort((short) url.length).put(url);
        return append(base, b.array());
    }
    private static Optional<NetworkOffer> readNetworkOffer(ByteBuffer b) {
        if (!b.hasRemaining()) return Optional.empty();
        if (b.remaining() < 2 || b.get() != CONNECTIVITY_EXTENSION || b.get() != 1) return Optional.empty();
        if (b.remaining() < 52) throw new IllegalArgumentException("Truncated network offer");
        UUID attempt = new UUID(b.getLong(), b.getLong()); byte[] key = new byte[32]; b.get(key);
        int flags = b.get() & 255, count = b.get() & 255;
        if (flags > 1 || count > 8) throw new IllegalArgumentException("Invalid network offer");
        List<Address> candidates = new ArrayList<>();
        for (int i = 0; i < count; i++) candidates.add(readAddress(b));
        if (b.remaining() < 2) throw new IllegalArgumentException("Truncated broker URL");
        int size = b.getShort() & 65535;
        if (size > 512 || size > b.remaining()) throw new IllegalArgumentException("Invalid broker URL length");
        byte[] url = new byte[size]; b.get(url);
        return Optional.of(new NetworkOffer(attempt, key, candidates, flags != 0, new String(url, StandardCharsets.UTF_8)));
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
            return new Register(maxPlayers, currentPlayerCount, Optional.empty(), false, publicRoom, worldName, mcVersion, readConnectivity(buf));
        }
        UUID accountId = new UUID(buf.getLong(), buf.getLong());
        byte[] sessionToken = new byte[16];
        buf.get(sessionToken);
        boolean friendsOnly = buf.remaining() >= 1 && buf.get() != 0;
        boolean publicRoom = buf.remaining() >= 1 && buf.get() != 0;
        String worldName = publicRoom ? readShortString(buf) : "";
        String mcVersion = publicRoom ? readShortString(buf) : "";
        return new Register(maxPlayers, currentPlayerCount, Optional.of(new AccountRef(accountId, sessionToken)), friendsOnly, publicRoom, worldName, mcVersion, readConnectivity(buf));
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
        ByteBuffer buf = ByteBuffer.wrap(data, 2, length - 2);
        int codeLen = buf.get() & 255; byte[] codeBytes = new byte[codeLen]; buf.get(codeBytes);
        String code = new String(codeBytes, StandardCharsets.US_ASCII);
        Optional<byte[]> token = Optional.empty();
        if (buf.hasRemaining()) {
            boolean present = buf.get() != 0;
            if (present) { byte[] value = new byte[16]; buf.get(value); token = Optional.of(value); }
        }
        return new Join(code, token, readConnectivity(buf));
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
        ByteBuffer buf = ByteBuffer.wrap(data, 2, length - 2);
        Address peer = readAddress(buf); long token = buf.getLong();
        Optional<UUID> account = Optional.empty();
        if (buf.hasRemaining()) {
            boolean present = buf.get() != 0;
            if (present) account = Optional.of(new UUID(buf.getLong(), buf.getLong()));
        }
        return new PeerFound(peer, token, account, readNetworkOffer(buf));
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

    // ---- LOOKUP_HOST: client -> server (anonymous). payload: [accountId:16] ----

    public static byte[] encodeLookupHost(UUID accountId) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 16);
        buf.put(MAGIC);
        buf.put(TYPE_LOOKUP_HOST);
        buf.putLong(accountId.getMostSignificantBits());
        buf.putLong(accountId.getLeastSignificantBits());
        return buf.array();
    }

    public static UUID decodeLookupHost(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 2, length - 2);
        return new UUID(buf.getLong(), buf.getLong());
    }

    // ---- LOOKUP_HOST_REPLY: server -> client. payload: [codeLen:1][code] ("" = not hosting) ----

    public static byte[] encodeLookupHostReply(String roomCode) {
        byte[] c = roomCode == null ? new byte[0] : roomCode.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.allocate(2 + 1 + c.length);
        buf.put(MAGIC);
        buf.put(TYPE_LOOKUP_HOST_REPLY);
        buf.put((byte) c.length);
        buf.put(c);
        return buf.array();
    }

    public static String decodeLookupHostReply(byte[] data, int length) {
        ByteBuffer buf = ByteBuffer.wrap(data, 2, length - 2);
        if (buf.remaining() < 1) {
            return "";
        }
        int len = buf.get() & 0xFF;
        if (buf.remaining() < len) {
            return "";
        }
        byte[] c = new byte[len];
        buf.get(c);
        return new String(c, StandardCharsets.US_ASCII);
    }
}
