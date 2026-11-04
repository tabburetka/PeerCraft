package net.peercraft.network.control;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

/**
 * Wire format of the encrypted client↔rendezvous control channel (magic 0xEC).
 *
 * <pre>
 * HELLO        EC 01 ver | client ephemeral (65) | client random (16) | zero padding to REPLY size
 * HELLO_REPLY  EC 02 ver | server static (65) | server ephemeral (65) | session id (8) | confirm (32)
 * DATA         EC 03 | session id (8) | counter (8) | AES-128-GCM(ciphertext + tag)
 * RESET        EC 04 | session id (8) | counter (8)          (server: "I do not know this session")
 * </pre>
 *
 * Keys come from two ECDH results — server static × client ephemeral (only the real server can
 * compute it, which authenticates the server) and server ephemeral × client ephemeral (forward
 * secrecy) — run through HKDF over a hash of the whole handshake. HELLO is padded to the reply
 * size so the server can never be used to amplify spoofed traffic.
 */
public final class ControlProtocol {
    public static final byte MAGIC = (byte) 0xEC;
    public static final byte HELLO = 1;
    public static final byte HELLO_REPLY = 2;
    public static final byte DATA = 3;
    public static final byte RESET = 4;
    public static final byte VERSION = 1;

    public static final int RANDOM_BYTES = 16;
    public static final int SESSION_ID_BYTES = 8;
    public static final int CONFIRM_BYTES = 32;
    public static final int HELLO_BODY = 3 + ControlCrypto.PUBLIC_KEY_BYTES + RANDOM_BYTES;
    public static final int REPLY_SIZE = 3 + 2 * ControlCrypto.PUBLIC_KEY_BYTES + SESSION_ID_BYTES + CONFIRM_BYTES;
    public static final int HELLO_SIZE = REPLY_SIZE;
    public static final int DATA_HEADER = 2 + SESSION_ID_BYTES + 8;
    public static final int DATA_OVERHEAD = DATA_HEADER + ControlCrypto.TAG_BYTES;
    public static final int RESET_SIZE = DATA_HEADER;

    private static final byte[] LABEL = "PeerCraft-ctl-v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEYS_INFO = "keys".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONFIRM_INFO = "server-confirm".getBytes(StandardCharsets.US_ASCII);

    private ControlProtocol() { }

    public static boolean isControl(byte[] data, int length) {
        return length >= 2 && data[0] == MAGIC;
    }

    public static byte type(byte[] data) { return data[1]; }

    /** Symmetric state shared by both ends once a handshake completes. */
    public static final class Keys {
        public final long sessionId;
        final byte[] clientToServer;
        final byte[] serverToClient;

        Keys(long sessionId, byte[] clientToServer, byte[] serverToClient) {
            this.sessionId = sessionId;
            this.clientToServer = clientToServer;
            this.serverToClient = serverToClient;
        }
    }

    /** Client half of a pending handshake. */
    public static final class ClientHello {
        final KeyPair ephemeral = ControlCrypto.generateKeyPair();
        final byte[] packet = new byte[HELLO_SIZE];

        public ClientHello() {
            packet[0] = MAGIC; packet[1] = HELLO; packet[2] = VERSION;
            System.arraycopy(ControlCrypto.encodePublicKey(ephemeral.getPublic()), 0, packet, 3, ControlCrypto.PUBLIC_KEY_BYTES);
            System.arraycopy(ControlCrypto.randomBytes(RANDOM_BYTES), 0, packet, 3 + ControlCrypto.PUBLIC_KEY_BYTES, RANDOM_BYTES);
        }

        public byte[] packet() { return packet; }
    }

    /** Result of a verified HELLO_REPLY: the session keys plus the server's static key for trust checks. */
    public static final class Established {
        public final Keys keys;
        public final byte[] serverStatic;

        Established(Keys keys, byte[] serverStatic) {
            this.keys = keys;
            this.serverStatic = serverStatic;
        }
    }

    /** Verifies a HELLO_REPLY against the pending hello; {@code null} if it is forged or malformed. */
    public static Established finishClient(ClientHello hello, byte[] reply, int length) {
        if (length != REPLY_SIZE || reply[0] != MAGIC || reply[1] != HELLO_REPLY || reply[2] != VERSION) return null;
        try {
            int at = 3;
            byte[] serverStatic = Arrays.copyOfRange(reply, at, at += ControlCrypto.PUBLIC_KEY_BYTES);
            byte[] serverEphemeral = Arrays.copyOfRange(reply, at, at += ControlCrypto.PUBLIC_KEY_BYTES);
            byte[] sessionId = Arrays.copyOfRange(reply, at, at += SESSION_ID_BYTES);
            byte[] confirm = Arrays.copyOfRange(reply, at, at + CONFIRM_BYTES);
            PrivateKey own = hello.ephemeral.getPrivate();
            byte[] ss1 = ControlCrypto.ecdh(own, ControlCrypto.decodePublicKey(serverStatic));
            byte[] ss2 = ControlCrypto.ecdh(own, ControlCrypto.decodePublicKey(serverEphemeral));
            byte[] transcript = transcript(hello.packet, serverStatic, serverEphemeral, sessionId);
            byte[] okm = ControlCrypto.hkdf(transcript, concat(ss1, ss2), KEYS_INFO, 2 * ControlCrypto.KEY_BYTES + 32);
            byte[] expected = ControlCrypto.hmac(Arrays.copyOfRange(okm, 32, 64), CONFIRM_INFO, transcript);
            if (!MessageDigest.isEqual(expected, confirm)) return null;
            return new Established(keys(sessionId, okm), serverStatic);
        } catch (GeneralSecurityException | RuntimeException e) {
            return null;
        }
    }

    /** Server side: answers a HELLO. Returns {@code null} for malformed or undersized hellos. */
    public static final class ServerReply {
        public final Keys keys;
        public final byte[] packet;

        ServerReply(Keys keys, byte[] packet) {
            this.keys = keys;
            this.packet = packet;
        }
    }

    public static ServerReply answer(KeyPair serverStatic, byte[] hello, int length) {
        if (length < HELLO_SIZE || hello[0] != MAGIC || hello[1] != HELLO || hello[2] != VERSION) return null;
        try {
            byte[] clientEphemeralBytes = Arrays.copyOfRange(hello, 3, 3 + ControlCrypto.PUBLIC_KEY_BYTES);
            PublicKey clientEphemeral = ControlCrypto.decodePublicKey(clientEphemeralBytes);
            KeyPair ephemeral = ControlCrypto.generateKeyPair();
            byte[] staticBytes = ControlCrypto.encodePublicKey(serverStatic.getPublic());
            byte[] ephemeralBytes = ControlCrypto.encodePublicKey(ephemeral.getPublic());
            byte[] sessionId = ControlCrypto.randomBytes(SESSION_ID_BYTES);
            byte[] ss1 = ControlCrypto.ecdh(serverStatic.getPrivate(), clientEphemeral);
            byte[] ss2 = ControlCrypto.ecdh(ephemeral.getPrivate(), clientEphemeral);
            byte[] canonicalHello = Arrays.copyOf(hello, HELLO_SIZE);
            Arrays.fill(canonicalHello, HELLO_BODY, HELLO_SIZE, (byte) 0);
            byte[] transcript = transcript(canonicalHello, staticBytes, ephemeralBytes, sessionId);
            byte[] okm = ControlCrypto.hkdf(transcript, concat(ss1, ss2), KEYS_INFO, 2 * ControlCrypto.KEY_BYTES + 32);
            byte[] confirm = ControlCrypto.hmac(Arrays.copyOfRange(okm, 32, 64), CONFIRM_INFO, transcript);
            ByteBuffer reply = ByteBuffer.allocate(REPLY_SIZE);
            reply.put(MAGIC).put(HELLO_REPLY).put(VERSION).put(staticBytes).put(ephemeralBytes).put(sessionId).put(confirm);
            return new ServerReply(keys(sessionId, okm), reply.array());
        } catch (GeneralSecurityException | RuntimeException e) {
            return null;
        }
    }

    /** Encrypts one datagram payload under {@code key}. */
    public static byte[] seal(Keys keys, boolean fromClient, long counter, byte[] plain, int offset, int length) throws GeneralSecurityException {
        byte[] out = new byte[DATA_OVERHEAD + length];
        out[0] = MAGIC; out[1] = DATA;
        putLong(out, 2, keys.sessionId);
        putLong(out, 2 + SESSION_ID_BYTES, counter);
        byte[] key = fromClient ? keys.clientToServer : keys.serverToClient;
        ControlCrypto.seal(key, nonce(fromClient, counter), out, DATA_HEADER, plain, offset, length, out, DATA_HEADER);
        return out;
    }

    /** @return the plaintext, or {@code null} if the datagram does not authenticate. */
    public static byte[] open(Keys keys, boolean fromClient, byte[] data, int length) {
        if (length < DATA_OVERHEAD || data[0] != MAGIC || data[1] != DATA || sessionId(data) != keys.sessionId) return null;
        byte[] key = fromClient ? keys.clientToServer : keys.serverToClient;
        return ControlCrypto.open(key, nonce(fromClient, counter(data)), data, DATA_HEADER, data, DATA_HEADER, length - DATA_HEADER);
    }

    public static long sessionId(byte[] data) { return getLong(data, 2); }

    public static long counter(byte[] data) { return getLong(data, 2 + SESSION_ID_BYTES); }

    public static byte[] reset(long sessionId, long counter) {
        byte[] out = new byte[RESET_SIZE];
        out[0] = MAGIC; out[1] = RESET;
        putLong(out, 2, sessionId);
        putLong(out, 2 + SESSION_ID_BYTES, counter);
        return out;
    }

    private static Keys keys(byte[] sessionId, byte[] okm) {
        return new Keys(getLong(sessionId, 0), Arrays.copyOfRange(okm, 0, 16), Arrays.copyOfRange(okm, 16, 32));
    }

    private static byte[] transcript(byte[] hello, byte[] serverStatic, byte[] serverEphemeral, byte[] sessionId) {
        return ControlCrypto.sha256(LABEL, hello, serverStatic, serverEphemeral, sessionId);
    }

    private static byte[] nonce(boolean fromClient, long counter) {
        byte[] nonce = new byte[12];
        nonce[0] = (byte) (fromClient ? 1 : 2);
        putLong(nonce, 4, counter);
        return nonce;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static void putLong(byte[] out, int at, long value) {
        for (int i = 7; i >= 0; i--) { out[at + i] = (byte) value; value >>>= 8; }
    }

    static long getLong(byte[] in, int at) {
        long value = 0;
        for (int i = 0; i < 8; i++) value = (value << 8) | (in[at + i] & 0xffL);
        return value;
    }
}
