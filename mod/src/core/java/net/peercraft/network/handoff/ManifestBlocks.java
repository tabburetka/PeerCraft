package net.peercraft.network.handoff;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.BitSet;

/** Bounded reassembly of authenticated manifest blocks, tolerant of UDP reordering/duplicates. */
public final class ManifestBlocks {
    public static final int BLOCK_BYTES = 1000;
    private final byte[] bytes, expectedHash;
    private final BitSet received;
    private final int count;
    public ManifestBlocks(int size, byte[] expectedHash) throws IOException {
        if (size <= 0 || size > HostExecutionManifest.MAX_BYTES || expectedHash == null || expectedHash.length != 64)
            throw new IOException("Invalid manifest size/hash");
        this.bytes = new byte[size]; this.expectedHash = expectedHash.clone();
        count = (size + BLOCK_BYTES - 1) / BLOCK_BYTES; received = new BitSet(count);
    }
    /** Authentication and attempt identity must be checked by the control channel before this call. */
    public synchronized void accept(int index, byte[] block) throws IOException {
        if (index < 0 || index >= count || block == null) throw new IOException("Invalid manifest block");
        int offset = index * BLOCK_BYTES, length = Math.min(BLOCK_BYTES, bytes.length - offset);
        if (block.length != length) throw new IOException("Invalid manifest block length");
        if (received.get(index)) {
            for (int i = 0; i < length; i++) if (bytes[offset + i] != block[i]) throw new IOException("Conflicting manifest duplicate");
            return;
        }
        System.arraycopy(block, 0, bytes, offset, length); received.set(index);
    }
    public synchronized boolean complete() { return received.cardinality() == count; }
    public synchronized HostExecutionManifest verified() throws IOException {
        if (!complete()) throw new IOException("Incomplete execution manifest");
        if (!MessageDigest.isEqual(expectedHash, hash(bytes))) throw new IOException("Execution manifest hash mismatch");
        return HostExecutionManifest.decode(bytes);
    }
    public synchronized BitSet missing() {
        BitSet missing = new BitSet(count); missing.set(0, count); missing.andNot(received); return missing;
    }
    public static byte[] block(byte[] encoded, int index) throws IOException {
        if (encoded.length <= 0 || encoded.length > HostExecutionManifest.MAX_BYTES || index < 0
                || index >= (encoded.length + BLOCK_BYTES - 1) / BLOCK_BYTES) throw new IOException("Invalid manifest block");
        int offset = index * BLOCK_BYTES; return Arrays.copyOfRange(encoded, offset, Math.min(encoded.length, offset + BLOCK_BYTES));
    }
    public static byte[] hash(byte[] bytes) throws IOException {
        try { return MessageDigest.getInstance("SHA-512").digest(bytes); }
        catch (NoSuchAlgorithmException unavailable) { throw new IOException(unavailable); }
    }
}
