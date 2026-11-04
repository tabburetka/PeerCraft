package net.peercraft.network.handoff;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Wire codec for shipping the world-save archive from the host to the handoff successor — a
 * fourth datagram family ({@code magic 0xE4}) alongside rendezvous ({@code 0xE1}), mod-sync
 * ({@code 0xE2}) and the handoff handshake ({@code 0xE3}).
 *
 * <p>It deliberately mirrors mod-sync's {@code T_FILE_*} shape (index-addressed chunks,
 * SHA-512, selective-ACK repair) and reuses {@code FileReassembler} on the receive side — but
 * under its own magic byte so it doesn't collide with the per-peer {@code ModSyncCoordinator}
 * routing in {@code P2PBridge}, and so it can carry its own world-scale caps / timeouts.
 *
 * <p>{@code transferId} is the handoff {@code offerId}, so a stray datagram from an unrelated
 * attempt is ignored. Java-8-source-clean (no records / arrow-switch) for the 1.16.5 build.
 */
public final class WorldTransferProtocol {

    public static final byte MAGIC = (byte) 0xE4;

    public static final byte T_BEGIN = 0x01;
    public static final byte T_CHUNK = 0x02;
    public static final byte T_ACK = 0x03;
    public static final byte T_DONE = 0x04;
    public static final byte T_ABORT = 0x05;

    /** Payload bytes per CHUNK — same as mod-sync, well under P2PBridge's 8000-byte per-datagram ceiling. */
    public static final int CHUNK_PAYLOAD = 6000;

    public static final class Begin {
        private final long transferId;
        private final long size;
        private final int chunkCount;
        private final byte[] sha512;
        private final int chunkSize;

        public Begin(long transferId, long size, int chunkCount, byte[] sha512, int chunkSize) {
            this.transferId = transferId;
            this.size = size;
            this.chunkCount = chunkCount;
            this.sha512 = sha512;
            this.chunkSize = chunkSize;
        }

        public long transferId() { return transferId; }
        public long size() { return size; }
        public int chunkCount() { return chunkCount; }
        public byte[] sha512() { return sha512; }
        public int chunkSize() { return chunkSize; }
    }

    public static final class Chunk {
        private final long transferId;
        private final int index;
        private final byte[] data;

        public Chunk(long transferId, int index, byte[] data) {
            this.transferId = transferId;
            this.index = index;
            this.data = data;
        }

        public long transferId() { return transferId; }
        public int index() { return index; }
        public byte[] data() { return data; }
    }

    public static final class Ack {
        private final long transferId;
        private final int nextContiguous;
        private final int[] gapIndices;

        public Ack(long transferId, int nextContiguous, int[] gapIndices) {
            this.transferId = transferId;
            this.nextContiguous = nextContiguous;
            this.gapIndices = gapIndices;
        }

        public long transferId() { return transferId; }
        public int nextContiguous() { return nextContiguous; }
        public int[] gapIndices() { return gapIndices; }
    }

    public static final class Done {
        private final long transferId;
        private final boolean ok;

        public Done(long transferId, boolean ok) {
            this.transferId = transferId;
            this.ok = ok;
        }

        public long transferId() { return transferId; }
        public boolean ok() { return ok; }
    }

    public static final class Abort {
        private final long transferId;
        private final String reasonKey;

        public Abort(long transferId, String reasonKey) {
            this.transferId = transferId;
            this.reasonKey = reasonKey;
        }

        public long transferId() { return transferId; }
        public String reasonKey() { return reasonKey; }
    }

    private WorldTransferProtocol() {
    }

    /** @return the type byte, or -1 if this isn't a world-transfer datagram (wrong magic / too short). */
    public static int messageType(byte[] data, int length) {
        if (length < 2 || data[0] != MAGIC) {
            return -1;
        }
        return data[1] & 0xFF;
    }

    // ---- BEGIN: host -> successor ----

    public static byte[] encodeBegin(long transferId, long size, int chunkCount, byte[] sha512, int chunkSize) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 8 + 8 + 4 + 64 + 2);
        buf.put(MAGIC).put(T_BEGIN);
        buf.putLong(transferId);
        buf.putLong(size);
        buf.putInt(chunkCount);
        put64(buf, sha512);
        buf.putShort((short) chunkSize);
        return buf.array();
    }

    public static Begin decodeBegin(byte[] data, int length) {
        ByteBuffer buf = body(data, length);
        long transferId = buf.getLong();
        long size = buf.getLong();
        int chunkCount = buf.getInt();
        byte[] sha = get64(buf);
        int chunkSize = buf.getShort() & 0xFFFF;
        return new Begin(transferId, size, chunkCount, sha, chunkSize);
    }

    // ---- CHUNK: host -> successor ----

    public static byte[] encodeChunk(long transferId, int index, byte[] chunk, int off, int len) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 8 + 4 + 2 + len);
        buf.put(MAGIC).put(T_CHUNK);
        buf.putLong(transferId);
        buf.putInt(index);
        buf.putShort((short) len);
        buf.put(chunk, off, len);
        return buf.array();
    }

    public static Chunk decodeChunk(byte[] data, int length) {
        ByteBuffer buf = body(data, length);
        long transferId = buf.getLong();
        int index = buf.getInt();
        int len = buf.getShort() & 0xFFFF;
        byte[] chunk = new byte[Math.min(len, buf.remaining())];
        buf.get(chunk);
        return new Chunk(transferId, index, chunk);
    }

    // ---- ACK: successor -> host ----

    public static byte[] encodeAck(long transferId, int nextContiguous, int[] gapIndices) {
        int gaps = gapIndices == null ? 0 : gapIndices.length;
        ByteBuffer buf = ByteBuffer.allocate(2 + 8 + 4 + 2 + gaps * 4);
        buf.put(MAGIC).put(T_ACK);
        buf.putLong(transferId);
        buf.putInt(nextContiguous);
        buf.putShort((short) gaps);
        for (int i = 0; i < gaps; i++) {
            buf.putInt(gapIndices[i]);
        }
        return buf.array();
    }

    public static Ack decodeAck(byte[] data, int length) {
        ByteBuffer buf = body(data, length);
        long transferId = buf.getLong();
        int next = buf.getInt();
        int gapCount = buf.getShort() & 0xFFFF;
        int[] gaps = new int[Math.max(0, Math.min(gapCount, buf.remaining() / 4))];
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = buf.getInt();
        }
        return new Ack(transferId, next, gaps);
    }

    // ---- DONE: successor -> host ----

    public static byte[] encodeDone(long transferId, boolean ok) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 8 + 1);
        buf.put(MAGIC).put(T_DONE);
        buf.putLong(transferId);
        buf.put((byte) (ok ? 1 : 0));
        return buf.array();
    }

    public static Done decodeDone(byte[] data, int length) {
        ByteBuffer buf = body(data, length);
        long transferId = buf.getLong();
        boolean ok = buf.remaining() >= 1 && buf.get() != 0;
        return new Done(transferId, ok);
    }

    // ---- ABORT: either side ----

    public static byte[] encodeAbort(long transferId, String reasonKey) {
        byte[] key = reasonKey == null ? new byte[0] : reasonKey.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + 8 + 2 + key.length);
        buf.put(MAGIC).put(T_ABORT);
        buf.putLong(transferId);
        buf.putShort((short) key.length);
        buf.put(key);
        return buf.array();
    }

    public static Abort decodeAbort(byte[] data, int length) {
        ByteBuffer buf = body(data, length);
        long transferId = buf.getLong();
        String key = "";
        if (buf.remaining() >= 2) {
            int len = buf.getShort() & 0xFFFF;
            if (buf.remaining() >= len) {
                byte[] b = new byte[len];
                buf.get(b);
                key = new String(b, StandardCharsets.UTF_8);
            }
        }
        return new Abort(transferId, key);
    }

    // ---- helpers ----

    private static ByteBuffer body(byte[] data, int length) {
        return ByteBuffer.wrap(data, 2, length - 2);
    }

    private static void put64(ByteBuffer buf, byte[] sha) {
        byte[] fixed = new byte[64];
        if (sha != null) {
            System.arraycopy(sha, 0, fixed, 0, Math.min(sha.length, 64));
        }
        buf.put(fixed);
    }

    private static byte[] get64(ByteBuffer buf) {
        byte[] sha = new byte[64];
        if (buf.remaining() >= 64) {
            buf.get(sha);
        }
        return sha;
    }
}
