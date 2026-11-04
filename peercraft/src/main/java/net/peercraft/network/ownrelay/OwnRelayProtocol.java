package net.peercraft.network.ownrelay;

import java.net.URI;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Wire format shared by the PeerCraft client and the self-hosted peercraft-relay program.
 * Java 8 and dependency-free: the relay compiles this exact file, never a copy of its logic.
 *
 * <p>Every datagram starts with {@code [0xEB][version][type]}. Only the relay owner knows the
 * secret: it signs PING/OPEN, and the relay signs PONG/OPENED back. The other player joins an
 * opened session with its 64-bit identifier, received through the rendezvous candidate channel.
 * DATA payloads are already end-to-end encrypted by the players; the relay only forwards them.
 */
public final class OwnRelayProtocol {
    public static final byte MAGIC = (byte) 0xEB;
    public static final byte VERSION = 1;
    public static final int PING = 1, PONG = 2, OPEN = 3, OPENED = 4, REFUSED = 5, JOIN = 6, JOINED = 7,
            PEER_JOINED = 8, DATA = 9, KEEPALIVE = 10, CLOSE = 11;
    public static final int REASON_AUTH = 1, REASON_FULL = 2, REASON_RATE = 3, REASON_UNKNOWN_SESSION = 4,
            REASON_STALE = 5, REASON_TAKEN = 6;
    public static final int SECRET_BYTES = 32, NONCE_BYTES = 16, TAG_BYTES = 16;
    public static final int HEADER_BYTES = 3, SESSION_HEADER_BYTES = HEADER_BYTES + 8;
    /** Encrypted fragments are at most 1200 bytes; leave room for the session header. */
    public static final int MAX_DATAGRAM = 1400;
    /** Signed requests tolerate this much clock difference between a player and the relay. */
    public static final long MAX_CLOCK_SKEW_MILLIS = 10 * 60_000L;
    public static final int DEFAULT_PORT = 52100;
    public static final String CODE_SCHEME = "pcrelay";

    private static final int AUTH_BYTES = HEADER_BYTES + NONCE_BYTES + 8 + TAG_BYTES;
    private OwnRelayProtocol() {}

    /** @return the message type, or -1 when this is not a relay datagram of a supported version. */
    public static int type(byte[] packet, int length) {
        if (packet == null || length < HEADER_BYTES || length > packet.length || packet[0] != MAGIC || packet[1] != VERSION) return -1;
        return packet[2] & 255;
    }

    public static boolean isPacket(byte[] packet, int length) { return type(packet, length) > 0; }

    // --- Owner-authenticated requests (PING, OPEN) ---

    public static byte[] encodeAuth(int type, byte[] secret, byte[] nonce, long nowMillis) {
        if (type != PING && type != OPEN) throw new IllegalArgumentException("Not an authenticated request");
        ByteBuffer b = ByteBuffer.allocate(AUTH_BYTES);
        header(b, type).put(checkedNonce(nonce)).putLong(nowMillis);
        return sign(b, secret);
    }

    /** @return 0 when the request is genuine and fresh, otherwise {@link #REASON_AUTH} or {@link #REASON_STALE}. */
    public static int verifyAuth(byte[] packet, int length, byte[] secret, long nowMillis) {
        int type = type(packet, length);
        if ((type != PING && type != OPEN) || length != AUTH_BYTES || !verify(packet, length, secret)) return REASON_AUTH;
        long sent = ByteBuffer.wrap(packet, HEADER_BYTES + NONCE_BYTES, 8).getLong();
        return Math.abs(nowMillis - sent) <= MAX_CLOCK_SKEW_MILLIS ? 0 : REASON_STALE;
    }

    /** Nonce of PING/OPEN/REFUSED (offset 3) or of JOIN/JOINED (after the session id). */
    public static byte[] nonce(byte[] packet, int length) {
        int type = type(packet, length);
        int offset = type == JOIN || type == JOINED || type == OPENED ? SESSION_HEADER_BYTES : HEADER_BYTES;
        if (type < 0 || length < offset + NONCE_BYTES) return null;
        return Arrays.copyOfRange(packet, offset, offset + NONCE_BYTES);
    }

    // --- Relay replies to the owner ---

    public static byte[] encodePong(byte[] secret, byte[] nonce) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES + NONCE_BYTES + TAG_BYTES);
        header(b, PONG).put(checkedNonce(nonce));
        return sign(b, secret);
    }

    public static boolean verifyPong(byte[] packet, int length, byte[] secret, byte[] nonce) {
        return type(packet, length) == PONG && length == HEADER_BYTES + NONCE_BYTES + TAG_BYTES
                && Arrays.equals(nonce, nonce(packet, length)) && verify(packet, length, secret);
    }

    public static byte[] encodeOpened(byte[] secret, byte[] nonce, long sessionId) {
        ByteBuffer b = ByteBuffer.allocate(SESSION_HEADER_BYTES + NONCE_BYTES + TAG_BYTES);
        header(b, OPENED).putLong(sessionId).put(checkedNonce(nonce));
        return sign(b, secret);
    }

    /** @return the opened session, or 0 when the reply is not a genuine answer to {@code nonce}. */
    public static long openedSession(byte[] packet, int length, byte[] secret, byte[] nonce) {
        if (type(packet, length) != OPENED || length != SESSION_HEADER_BYTES + NONCE_BYTES + TAG_BYTES
                || !Arrays.equals(nonce, nonce(packet, length)) || !verify(packet, length, secret)) return 0;
        return sessionId(packet, length);
    }

    /** Unsigned: a relay cannot sign a refusal for a requester whose secret is wrong. */
    public static byte[] encodeRefused(byte[] nonce, int reason) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES + NONCE_BYTES + 1);
        header(b, REFUSED).put(checkedNonce(nonce)).put((byte) reason);
        return b.array();
    }

    /** @return the refusal reason for the request identified by {@code nonce}, or -1. */
    public static int refusedReason(byte[] packet, int length, byte[] nonce) {
        if (type(packet, length) != REFUSED || length != HEADER_BYTES + NONCE_BYTES + 1
                || !Arrays.equals(nonce, nonce(packet, length))) return -1;
        return packet[HEADER_BYTES + NONCE_BYTES] & 255;
    }

    // --- Session messages ---

    public static byte[] encodeJoin(long sessionId, byte[] nonce) { return sessionWithNonce(JOIN, sessionId, nonce); }
    public static byte[] encodeJoined(long sessionId, byte[] nonce) { return sessionWithNonce(JOINED, sessionId, nonce); }

    public static boolean isJoined(byte[] packet, int length, long sessionId, byte[] nonce) {
        return type(packet, length) == JOINED && length == SESSION_HEADER_BYTES + NONCE_BYTES
                && sessionId(packet, length) == sessionId && Arrays.equals(nonce, nonce(packet, length));
    }

    /** PEER_JOINED, KEEPALIVE and CLOSE carry only the session identifier. */
    public static byte[] encodeSession(int type, long sessionId) {
        if (type != PEER_JOINED && type != KEEPALIVE && type != CLOSE) throw new IllegalArgumentException("Not a session message");
        return header(ByteBuffer.allocate(SESSION_HEADER_BYTES), type).putLong(sessionId).array();
    }

    public static byte[] encodeData(long sessionId, byte[] payload) {
        if (payload == null || SESSION_HEADER_BYTES + payload.length > MAX_DATAGRAM) throw new IllegalArgumentException("Relay payload too large");
        return header(ByteBuffer.allocate(SESSION_HEADER_BYTES + payload.length), DATA).putLong(sessionId).put(payload).array();
    }

    public static byte[] dataPayload(byte[] packet, int length) {
        if (type(packet, length) != DATA || length <= SESSION_HEADER_BYTES) return null;
        return Arrays.copyOfRange(packet, SESSION_HEADER_BYTES, length);
    }

    /** @return the session id of a session message, or 0 for any other datagram. */
    public static long sessionId(byte[] packet, int length) {
        int type = type(packet, length);
        if (type != OPENED && type != JOIN && type != JOINED && type != PEER_JOINED && type != DATA
                && type != KEEPALIVE && type != CLOSE) return 0;
        if (length < SESSION_HEADER_BYTES) return 0;
        return ByteBuffer.wrap(packet, HEADER_BYTES, 8).getLong();
    }

    // --- Relay code: pcrelay://host:port#base64url-secret ---

    public static String formatCode(String host, int port, byte[] secret) {
        if (secret == null || secret.length != SECRET_BYTES) throw new IllegalArgumentException("Relay secret must be 32 bytes");
        String shown = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        return CODE_SCHEME + "://" + shown + ":" + port + "#" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    /** Parsed relay code. Never contains a resolved address: resolution belongs to the caller. */
    public static final class Code {
        public final String host;
        public final int port;
        private final byte[] secret;
        Code(String host, int port, byte[] secret) { this.host = host; this.port = port; this.secret = secret; }
        public byte[] secret() { return secret.clone(); }
    }

    /** @return the parsed code, or null when {@code text} is not a well-formed relay code. */
    public static Code parseCode(String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.isEmpty() || text.length() > 512) return null;
        try {
            URI uri = new URI(text);
            if (!CODE_SCHEME.equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty()) || uri.getQuery() != null || uri.getRawFragment() == null)
                return null;
            int port = uri.getPort() < 0 ? DEFAULT_PORT : uri.getPort();
            if (port < 1 || port > 65535) return null;
            byte[] secret = Base64.getUrlDecoder().decode(uri.getRawFragment());
            if (secret.length != SECRET_BYTES) return null;
            String host = uri.getHost();
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            return new Code(host, port, secret);
        } catch (Exception malformed) { return null; }
    }

    // --- Internals ---

    private static ByteBuffer header(ByteBuffer b, int type) { return b.put(MAGIC).put(VERSION).put((byte) type); }

    private static byte[] sessionWithNonce(int type, long sessionId, byte[] nonce) {
        return header(ByteBuffer.allocate(SESSION_HEADER_BYTES + NONCE_BYTES), type).putLong(sessionId).put(checkedNonce(nonce)).array();
    }

    private static byte[] checkedNonce(byte[] nonce) {
        if (nonce == null || nonce.length != NONCE_BYTES) throw new IllegalArgumentException("Relay nonce must be 16 bytes");
        return nonce;
    }

    private static byte[] sign(ByteBuffer b, byte[] secret) {
        byte[] bytes = b.array();
        byte[] tag = tag(secret, bytes, bytes.length - TAG_BYTES);
        System.arraycopy(tag, 0, bytes, bytes.length - TAG_BYTES, TAG_BYTES);
        return bytes;
    }

    private static boolean verify(byte[] packet, int length, byte[] secret) {
        if (secret == null || secret.length != SECRET_BYTES || length < HEADER_BYTES + TAG_BYTES) return false;
        return MessageDigest.isEqual(tag(secret, packet, length - TAG_BYTES),
                Arrays.copyOfRange(packet, length - TAG_BYTES, length));
    }

    private static byte[] tag(byte[] secret, byte[] bytes, int length) {
        if (secret == null || secret.length != SECRET_BYTES) throw new IllegalArgumentException("Relay secret must be 32 bytes");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(bytes, 0, length);
            return Arrays.copyOf(mac.doFinal(), TAG_BYTES);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is required for the relay protocol", unavailable);
        }
    }
}
