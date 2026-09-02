package net.peercraft.network.modsync;

// Java 8 twin of src/main/.../network/modsync/FileReassembler.java — verbatim (all
// APIs used here exist on Java 8). Keep in sync with the original.

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.BitSet;

/**
 * Reassembles a jar streamed over {@link ModSyncProtocol} {@code T_FILE_CHUNK}s into a file
 * on disk. Chunks are index-addressed (not a seq stream), so out-of-order / duplicated /
 * lost datagrams are handled by writing each chunk at {@code index * chunkSize} and tracking
 * a {@link BitSet} of what's arrived — {@link #missingIndices(int)} feeds the selective ACK
 * the host resends against.
 *
 * <p>Pure I/O + hashing, no Minecraft and no networking. Not thread-safe: the owning
 * {@code ModSyncCoordinator} calls it from one thread.
 */
public final class FileReassembler implements AutoCloseable {

    private final Path partFile;
    private final int chunkSize;
    private final int chunkCount;
    private final long declaredSize;
    private final long maxBytes;
    private final BitSet received;
    private final FileChannel channel;
    private long bytesWritten;

    public FileReassembler(Path partFile, long declaredSize, int chunkCount, int chunkSize, long maxBytes) throws IOException {
        if (chunkSize <= 0 || chunkCount < 0 || declaredSize < 0) {
            throw new IllegalArgumentException("bad file params: size=" + declaredSize + " chunks=" + chunkCount + " chunkSize=" + chunkSize);
        }
        if (declaredSize > maxBytes) {
            throw new IOException("declared size " + declaredSize + " exceeds limit " + maxBytes);
        }
        this.partFile = partFile;
        this.chunkSize = chunkSize;
        this.chunkCount = chunkCount;
        this.declaredSize = declaredSize;
        this.maxBytes = maxBytes;
        this.received = new BitSet(chunkCount);
        Path parent = partFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.channel = FileChannel.open(partFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Accepts one chunk. Ignores a duplicate. Throws on an out-of-range index or an overrun past the size cap. */
    public void accept(int index, byte[] data) throws IOException {
        if (index < 0 || index >= chunkCount) {
            throw new IOException("chunk index out of range: " + index + " / " + chunkCount);
        }
        if (received.get(index)) {
            return;
        }
        long offset = (long) index * chunkSize;
        long end = offset + data.length;
        if (end > declaredSize || end > maxBytes) {
            throw new IOException("chunk " + index + " would overrun declared size " + declaredSize);
        }
        channel.write(ByteBuffer.wrap(data), offset);
        received.set(index);
        bytesWritten += data.length;
    }

    public boolean isComplete() {
        return received.cardinality() == chunkCount;
    }

    /** Lowest index not yet received (== chunkCount when complete) — the "next contiguous" the ACK reports. */
    public int nextContiguous() {
        int n = received.nextClearBit(0);
        return Math.min(n, chunkCount);
    }

    /** Up to {@code limit} still-missing chunk indices at or after {@link #nextContiguous()}, for a selective ACK. */
    public int[] missingIndices(int limit) {
        int[] tmp = new int[Math.max(0, limit)];
        int n = 0;
        for (int i = received.nextClearBit(0); i >= 0 && i < chunkCount && n < tmp.length; i = received.nextClearBit(i + 1)) {
            tmp[n++] = i;
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    /**
     * Truncates to the declared size and returns the assembled file's SHA-512. Call only
     * once {@link #isComplete()}.
     */
    public byte[] finishAndHash() throws IOException {
        channel.truncate(declaredSize);
        channel.force(true);
        try (FileChannel in = FileChannel.open(partFile, StandardOpenOption.READ)) {
            MessageDigest md = sha512();
            ByteBuffer buf = ByteBuffer.allocate(1 << 16);
            while (in.read(buf) != -1) {
                buf.flip();
                md.update(buf);
                buf.clear();
            }
            return md.digest();
        }
    }

    public Path partFile() {
        return partFile;
    }

    public long bytesWritten() {
        return bytesWritten;
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    public static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }
}
