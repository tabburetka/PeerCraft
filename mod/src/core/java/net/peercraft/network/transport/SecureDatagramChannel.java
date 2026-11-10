package net.peercraft.network.transport;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authenticated, bounded datagram fragmentation. Keep this object when a TURN allocation
 * changes: reconstructing it with the same keys and IDs would reuse GCM nonces.
 */
public final class SecureDatagramChannel {
    public static final int MAX_WIRE_BYTES = 1200;
    public static final int MAX_PAYLOAD_BYTES = 65507;
    public static final int HEADER_BYTES = 60;
    public static final int FRAGMENT_BYTES = MAX_WIRE_BYTES - 4 - HEADER_BYTES - 16;
    private static final int MAGIC = 0xE4504301;
    private static final int MAX_ASSEMBLIES = 64;
    private static final long ASSEMBLY_TTL_MILLIS = 10000L;
    private static final int REPLAY_WINDOW = 4096;
    private final SecretKeySpec sendKey, receiveKey;
    private final byte[] sendPrefix, receivePrefix;
    private final UUID linkId, attemptId;
    private final ReplayWindow receivedNonces = new ReplayWindow();
    private final ReplayWindow receivedMessages = new ReplayWindow();
    private final Map<Long, Assembly> assemblies = new HashMap<Long, Assembly>();
    private long nextNonce = 1, nextMessage = 1;

    public SecureDatagramChannel(byte[] sendKey, byte[] receiveKey, UUID linkId, UUID attemptId) {
        if (sendKey == null || receiveKey == null || sendKey.length != 16 || receiveKey.length != 16)
            throw new IllegalArgumentException("Directional AES keys must each contain 16 bytes");
        if (Arrays.equals(sendKey, receiveKey)) throw new IllegalArgumentException("Directional keys must differ");
        if (linkId == null || attemptId == null) throw new IllegalArgumentException("Missing channel identity");
        this.sendKey = new SecretKeySpec(sendKey.clone(), "AES");
        this.receiveKey = new SecretKeySpec(receiveKey.clone(), "AES");
        this.linkId = linkId; this.attemptId = attemptId;
        this.sendPrefix = prefix(sendKey, linkId, attemptId);
        this.receivePrefix = prefix(receiveKey, linkId, attemptId);
    }

    public UUID linkId() { return linkId; }
    public UUID attemptId() { return attemptId; }

    /** Includes framing and authentication; TURN adds at most its four-byte channel header. */
    public synchronized List<byte[]> encode(byte[] payload) throws IOException {
        if (payload == null || payload.length > MAX_PAYLOAD_BYTES) throw new IOException("Datagram exceeds payload limit");
        int count = Math.max(1, (payload.length + FRAGMENT_BYTES - 1) / FRAGMENT_BYTES);
        if (nextMessage <= 0 || nextMessage == Long.MAX_VALUE || nextNonce <= 0 || nextNonce > Long.MAX_VALUE - count)
            throw new IOException("Channel nonce space exhausted; create a new attempt and keys");
        long messageId = nextMessage++;
        List<byte[]> result = new ArrayList<byte[]>(count);
        for (int index = 0; index < count; index++) {
            int offset = index * FRAGMENT_BYTES;
            int length = Math.min(FRAGMENT_BYTES, payload.length - offset);
            long nonce = nextNonce++;
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            header.putInt(MAGIC); putUuid(header, linkId); putUuid(header, attemptId);
            header.putLong(nonce).putLong(messageId).putShort((short) index).putShort((short) count).putInt(payload.length);
            try {
                Cipher cipher = cipher(Cipher.ENCRYPT_MODE, sendKey, sendPrefix, nonce);
                cipher.updateAAD(header.array());
                byte[] encrypted = cipher.doFinal(payload, offset, length);
                byte[] packet = Arrays.copyOf(header.array(), HEADER_BYTES + encrypted.length);
                System.arraycopy(encrypted, 0, packet, HEADER_BYTES, encrypted.length);
                result.add(packet);
            } catch (GeneralSecurityException ex) { throw new IOException("Datagram encryption failed", ex); }
        }
        return result;
    }

    /** Malformed, unauthenticated, stale or duplicate fragments produce no application packet. */
    public synchronized byte[] accept(byte[] packet, long nowMillis) {
        expire(nowMillis);
        if (!isPacket(packet)) return null;
        ByteBuffer header = ByteBuffer.wrap(packet);
        header.getInt();
        if (!linkId.equals(getUuid(header)) || !attemptId.equals(getUuid(header))) return null;
        long nonce = header.getLong(), messageId = header.getLong();
        int index = header.getShort() & 0xffff, count = header.getShort() & 0xffff, total = header.getInt();
        if (nonce <= 0 || messageId <= 0 || !receivedNonces.permits(nonce) || !receivedMessages.permits(messageId)) return null;
        if (total < 0 || total > MAX_PAYLOAD_BYTES || count != Math.max(1, (total + FRAGMENT_BYTES - 1) / FRAGMENT_BYTES)
                || index >= count) return null;
        int expected = Math.min(FRAGMENT_BYTES, total - index * FRAGMENT_BYTES);
        if (packet.length != HEADER_BYTES + expected + 16) return null;
        byte[] fragment;
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, receiveKey, receivePrefix, nonce);
            cipher.updateAAD(packet, 0, HEADER_BYTES);
            fragment = cipher.doFinal(packet, HEADER_BYTES, packet.length - HEADER_BYTES);
        } catch (GeneralSecurityException ex) { return null; }
        receivedNonces.mark(nonce);
        Assembly assembly = assemblies.get(messageId);
        if (assembly == null) {
            if (assemblies.size() >= MAX_ASSEMBLIES) return null;
            assembly = new Assembly(count, total, nowMillis);
            assemblies.put(messageId, assembly);
        }
        if (assembly.total != total || assembly.parts.length != count || assembly.parts[index] != null) return null;
        assembly.parts[index] = fragment;
        if (++assembly.received != count) return null;
        byte[] payload = new byte[total];
        for (int i = 0; i < count; i++) System.arraycopy(assembly.parts[i], 0, payload, i * FRAGMENT_BYTES, assembly.parts[i].length);
        assemblies.remove(messageId); receivedMessages.mark(messageId);
        return payload;
    }

    public static boolean isPacket(byte[] packet) {
        return packet != null && packet.length >= HEADER_BYTES + 16 && packet.length <= MAX_WIRE_BYTES - 4
                && ByteBuffer.wrap(packet).getInt() == MAGIC;
    }
    public static UUID peekLinkId(byte[] packet) { return isPacket(packet) ? getUuid((ByteBuffer) ByteBuffer.wrap(packet).position(4)) : null; }
    public static UUID peekAttemptId(byte[] packet) { return isPacket(packet) ? getUuid((ByteBuffer) ByteBuffer.wrap(packet).position(20)) : null; }
    public synchronized int pendingAssemblies() { return assemblies.size(); }

    private void expire(long now) {
        Iterator<Map.Entry<Long, Assembly>> it = assemblies.entrySet().iterator();
        while (it.hasNext()) {
            Assembly assembly = it.next().getValue();
            if (now < assembly.startedAt || now - assembly.startedAt >= ASSEMBLY_TTL_MILLIS) it.remove();
        }
    }
    private static Cipher cipher(int mode, SecretKeySpec key, byte[] prefix, long counter) throws GeneralSecurityException {
        ByteBuffer nonce = ByteBuffer.allocate(12); nonce.put(prefix).putLong(counter);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce.array())); return cipher;
    }
    private static byte[] prefix(byte[] key, UUID link, UUID attempt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(key); ByteBuffer ids = ByteBuffer.allocate(32); putUuid(ids, link); putUuid(ids, attempt);
            return Arrays.copyOf(digest.digest(ids.array()), 4);
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void putUuid(ByteBuffer out, UUID id) { out.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()); }
    private static UUID getUuid(ByteBuffer in) { return new UUID(in.getLong(), in.getLong()); }
    private static final class Assembly {
        final byte[][] parts; final int total; final long startedAt; int received;
        Assembly(int count, int total, long now) { parts = new byte[count][]; this.total = total; startedAt = now; }
    }
    private static final class ReplayWindow {
        long highest; BitSet seen = new BitSet(REPLAY_WINDOW);
        boolean permits(long value) {
            if (value > highest) return true;
            long delta = highest - value;
            return delta < REPLAY_WINDOW && !seen.get((int) delta);
        }
        void mark(long value) {
            if (value > highest) {
                long distance = value - highest;
                BitSet shifted = new BitSet(REPLAY_WINDOW);
                if (distance < REPLAY_WINDOW) {
                    for (int bit = seen.nextSetBit(0); bit >= 0 && bit + distance < REPLAY_WINDOW; bit = seen.nextSetBit(bit + 1))
                        shifted.set(bit + (int) distance);
                }
                seen = shifted; highest = value;
            }
            seen.set((int) (highest - value));
        }
    }
}
