package net.peercraft.network.modsync;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire codec for the mod-sync handshake that runs over the already-punched peer-to-peer
 * UDP link, AFTER {@code PunchCoordinator} succeeds and BEFORE any Minecraft bytes flow
 * (see {@code P2PBridge.beginClientPunch}). Every datagram starts with
 * {@code [magic:1 = 0xE2][type:1]} so {@code P2PBridge.handleIncomingPacket} can route it
 * ahead of {@code FramedPacket.decode} (which rejects a first byte that isn't its own
 * {@code VERSION}) and apart from rendezvous traffic ({@code RendezvousProtocol.MAGIC = 0xE1}).
 *
 * <p>This is a NEW, self-contained protocol — it does NOT touch {@code RendezvousProtocol}
 * or {@code FramedPacket}, and the rendezvous server never sees a byte of it. Backward
 * compatibility follows the same append-only rule as {@code RendezvousProtocol}: new fields
 * go at the END of a message and are read only when {@link #getStr}/{@code remaining()}
 * find them; {@link ModSyncProtocolTest} pins the exact bytes.
 *
 * <p>Pure: no {@code net.minecraft.*}, no I/O. Operates on {@code byte[]} only.
 */
public final class ModSyncProtocol {

    public static final byte MAGIC = (byte) 0xE2;

    /** Bumped only on an incompatible layout change; carried in HELLO and MANIFEST so a mismatch aborts cleanly. */
    public static final int PROTO_VERSION = 1;

    public static final byte T_HELLO = 0x01;
    public static final byte T_MANIFEST = 0x02;
    public static final byte T_MANIFEST_NONE = 0x03;
    public static final byte T_REQUEST_FILE = 0x04;
    public static final byte T_FILE_BEGIN = 0x05;
    public static final byte T_FILE_CHUNK = 0x06;
    public static final byte T_FILE_ACK = 0x07;
    public static final byte T_FILE_DONE = 0x08;
    public static final byte T_ABORT = 0x09;
    /** Keepalive — sent both ways while a handshake/transfer is in progress so the punched NAT mapping doesn't age out during quiet stretches (e.g. the joiner's Modrinth lookups). No payload; ignored on receipt. */
    public static final byte T_PING = 0x0A;

    /** MANIFEST flag: the mod list didn't fit one datagram and is being streamed as a file under {@link #MANIFEST_STREAM_ID}. */
    public static final int MANIFEST_FLAG_STREAMED = 0x1;

    /** Reserved {@code modId} the MANIFEST body uses when it has to travel through the file mechanism. */
    public static final String MANIFEST_STREAM_ID = "$manifest";

    public enum Loader {
        FABRIC, NEOFORGE, FORGE, UNKNOWN;

        public byte wire() {
            return (byte) ordinal();
        }

        public static Loader fromWire(int b) {
            Loader[] v = values();
            return (b >= 0 && b < v.length) ? v[b] : UNKNOWN;
        }
    }

    public record Hello(int protoVersion, Loader loader, List<ModRef> mods) {
    }

    public record ModRef(String id, String version) {
    }

    public record Manifest(int protoVersion, int flags, List<ModEntry> mods) {
        public boolean streamed() {
            return (flags & MANIFEST_FLAG_STREAMED) != 0;
        }
    }

    public record FileBegin(String modId, long size, int chunkCount, byte[] sha512, int chunkSize) {
    }

    public record FileChunk(String modId, int index, byte[] data) {
    }

    public record FileAck(String modId, int nextContiguous, int[] gapIndices) {
    }

    public record FileDone(String modId, boolean ok) {
    }

    public record Abort(String reasonKey) {
    }

    private ModSyncProtocol() {
    }

    /** @return the type byte, or -1 if this isn't a mod-sync datagram at all (wrong magic / too short). */
    public static int messageType(byte[] data, int length) {
        if (length < 2 || data[0] != MAGIC) {
            return -1;
        }
        return data[1] & 0xFF;
    }

    // ---- HELLO: joiner -> host ----

    public static byte[] encodeHello(Loader loader, List<ModRef> mods) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 1 + 1 + 2 + estimateRefs(mods));
        buf.put(MAGIC).put(T_HELLO);
        buf.put((byte) PROTO_VERSION);
        buf.put(loader.wire());
        buf.putShort((short) mods.size());
        for (ModRef m : mods) {
            putStr(buf, m.id());
            putStr(buf, m.version());
        }
        return trim(buf);
    }

    public static Hello decodeHello(byte[] data, int length) {
        ByteBuffer buf = body(data, length);
        int proto = u8(buf);
        Loader loader = Loader.fromWire(u8(buf));
        int count = u16(buf);
        List<ModRef> mods = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count && buf.remaining() > 0; i++) {
            String id = getStr(buf);
            String version = getStr(buf);
            mods.add(new ModRef(id, version));
        }
        return new Hello(proto, loader, mods);
    }

    // ---- MANIFEST: host -> joiner ----

    public static byte[] encodeManifest(int flags, List<ModEntry> mods) {
        int size = 2 + 1 + 1 + 2;
        for (ModEntry e : mods) {
            size += estimateEntry(e);
        }
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(MAGIC).put(T_MANIFEST);
        buf.put((byte) PROTO_VERSION);
        buf.put((byte) flags);
        buf.putShort((short) mods.size());
        for (ModEntry e : mods) {
            putStr(buf, e.id());
            putStr(buf, e.version());
            buf.putLong(e.sizeBytes());
            put64(buf, e.sha512());
            putStr(buf, e.fileName());
            buf.put(e.env().wire());
            putStr(buf, e.homepageUrl());
            putStr(buf, e.sourcesUrl());
        }
        return trim(buf);
    }

    public static Manifest decodeManifest(byte[] data, int length) {
        ByteBuffer buf = body(data, length);
        int proto = u8(buf);
        int flags = u8(buf);
        int count = u16(buf);
        List<ModEntry> mods = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count && buf.remaining() > 0; i++) {
            String id = getStr(buf);
            String version = getStr(buf);
            long modSize = buf.remaining() >= 8 ? buf.getLong() : 0L;
            byte[] sha = get64(buf);
            String fileName = getStr(buf);
            int env = u8(buf);
            String homepage = getStr(buf);
            String sources = getStr(buf);
            mods.add(new ModEntry(id, version, modSize, sha, fileName, ModEntry.Env.fromWire(env), homepage, sources));
        }
        return new Manifest(proto, flags, mods);
    }

    public static byte[] encodeManifestNone() {
        return new byte[]{MAGIC, T_MANIFEST_NONE};
    }

    public static byte[] encodePing() {
        return new byte[]{MAGIC, T_PING};
    }

    // ---- REQUEST_FILE: joiner -> host ----

    public static byte[] encodeRequestFile(String modId) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 2 + utf8(modId).length);
        buf.put(MAGIC).put(T_REQUEST_FILE);
        putStr(buf, modId);
        return trim(buf);
    }

    public static String decodeRequestFile(byte[] data, int length) {
        return getStr(body(data, length));
    }

    // ---- FILE_BEGIN: host -> joiner ----

    public static byte[] encodeFileBegin(String modId, long size, int chunkCount, byte[] sha512, int chunkSize) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 2 + utf8(modId).length + 8 + 4 + 64 + 2);
        buf.put(MAGIC).put(T_FILE_BEGIN);
        putStr(buf, modId);
        buf.putLong(size);
        buf.putInt(chunkCount);
        put64(buf, sha512);
        buf.putShort((short) chunkSize);
        return trim(buf);
    }

    public static FileBegin decodeFileBegin(byte[] data, int length) {
        ByteBuffer buf = body(data, length);
        String modId = getStr(buf);
        long size = buf.getLong();
        int chunkCount = buf.getInt();
        byte[] sha = get64(buf);
        int chunkSize = u16(buf);
        return new FileBegin(modId, size, chunkCount, sha, chunkSize);
    }

    // ---- FILE_CHUNK: host -> joiner ----

    public static byte[] encodeFileChunk(String modId, int index, byte[] chunk, int off, int len) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 2 + utf8(modId).length + 4 + 2 + len);
        buf.put(MAGIC).put(T_FILE_CHUNK);
        putStr(buf, modId);
        buf.putInt(index);
        buf.putShort((short) len);
        buf.put(chunk, off, len);
        return trim(buf);
    }

    public static FileChunk decodeFileChunk(byte[] data, int length) {
        ByteBuffer buf = body(data, length);
        String modId = getStr(buf);
        int index = buf.getInt();
        int len = u16(buf);
        byte[] chunk = new byte[Math.min(len, buf.remaining())];
        buf.get(chunk);
        return new FileChunk(modId, index, chunk);
    }

    // ---- FILE_ACK: joiner -> host ----

    public static byte[] encodeFileAck(String modId, int nextContiguous, int[] gapIndices) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 2 + utf8(modId).length + 4 + 2 + gapIndices.length * 4);
        buf.put(MAGIC).put(T_FILE_ACK);
        putStr(buf, modId);
        buf.putInt(nextContiguous);
        buf.putShort((short) gapIndices.length);
        for (int g : gapIndices) {
            buf.putInt(g);
        }
        return trim(buf);
    }

    public static FileAck decodeFileAck(byte[] data, int length) {
        ByteBuffer buf = body(data, length);
        String modId = getStr(buf);
        int next = buf.getInt();
        int gapCount = u16(buf);
        int[] gaps = new int[Math.max(0, Math.min(gapCount, buf.remaining() / 4))];
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = buf.getInt();
        }
        return new FileAck(modId, next, gaps);
    }

    // ---- FILE_DONE: joiner -> host ----

    public static byte[] encodeFileDone(String modId, boolean ok) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 2 + utf8(modId).length + 1);
        buf.put(MAGIC).put(T_FILE_DONE);
        putStr(buf, modId);
        buf.put((byte) (ok ? 1 : 0));
        return trim(buf);
    }

    public static FileDone decodeFileDone(byte[] data, int length) {
        ByteBuffer buf = body(data, length);
        String modId = getStr(buf);
        boolean ok = buf.remaining() >= 1 && buf.get() != 0;
        return new FileDone(modId, ok);
    }

    // ---- ABORT: either side ----

    public static byte[] encodeAbort(String reasonKey) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 2 + utf8(reasonKey).length);
        buf.put(MAGIC).put(T_ABORT);
        putStr(buf, reasonKey);
        return trim(buf);
    }

    public static Abort decodeAbort(byte[] data, int length) {
        return new Abort(getStr(body(data, length)));
    }

    // ---- helpers ----

    private static ByteBuffer body(byte[] data, int length) {
        return ByteBuffer.wrap(data, 2, length - 2);
    }

    private static byte[] trim(ByteBuffer buf) {
        byte[] out = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, out, 0, out.length);
        return out;
    }

    private static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    /** 2-byte unsigned length prefix + UTF-8 bytes. */
    private static void putStr(ByteBuffer buf, String s) {
        byte[] b = utf8(s);
        buf.putShort((short) b.length);
        buf.put(b);
    }

    /** Tolerant of a short/absent buffer (returns "") so appended trailer fields decode on older senders. */
    private static String getStr(ByteBuffer buf) {
        if (buf.remaining() < 2) {
            return "";
        }
        int len = buf.getShort() & 0xFFFF;
        if (buf.remaining() < len) {
            return "";
        }
        byte[] b = new byte[len];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
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

    private static int u8(ByteBuffer buf) {
        try {
            return buf.get() & 0xFF;
        } catch (BufferUnderflowException e) {
            return 0;
        }
    }

    private static int u16(ByteBuffer buf) {
        if (buf.remaining() < 2) {
            return 0;
        }
        return buf.getShort() & 0xFFFF;
    }

    private static int estimateRefs(List<ModRef> mods) {
        int n = 0;
        for (ModRef m : mods) {
            n += 4 + utf8(m.id()).length + utf8(m.version()).length;
        }
        return n;
    }

    private static int estimateEntry(ModEntry e) {
        return 2 + utf8(e.id()).length
                + 2 + utf8(e.version()).length
                + 8 + 64
                + 2 + utf8(e.fileName()).length
                + 1
                + 2 + utf8(e.homepageUrl()).length
                + 2 + utf8(e.sourcesUrl()).length;
    }
}
