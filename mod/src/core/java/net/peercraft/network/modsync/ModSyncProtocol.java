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
    public static final int MANIFEST_FLAG_REPLACEMENTS = 0x2;
    /** MANIFEST flag: the replacement extension is followed by the ids the joiner has and the host doesn't. */
    public static final int MANIFEST_FLAG_EXTRAS = 0x4;
    /** HELLO extension flag bits: 1 = replacements supported, 2 = joiner asks for {@code Manifest.extraIds}. */
    private static final int HELLO_FLAG_REPLACEMENTS = 0x1;
    private static final int HELLO_FLAG_EXTRAS = 0x2;
    private static final int EXTENSION_VERSION = 1;

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

    public static final class Hello {
        private final int protoVersion;
        private final Loader loader;
        private final List<ModRef> mods;
        private final boolean replacements;
        private final String minecraftVersion;
        private final boolean extras;

        public Hello(int protoVersion, Loader loader, List<ModRef> mods, boolean replacements, String minecraftVersion, boolean extras) {
            this.protoVersion = protoVersion;
            this.loader = loader;
            this.mods = mods;
            this.replacements = replacements;
            this.minecraftVersion = minecraftVersion;
            this.extras = extras;
        }

        public int protoVersion() {
            return protoVersion;
        }

        public Loader loader() {
            return loader;
        }

        public List<ModRef> mods() {
            return mods;
        }

        public boolean replacements() {
            return replacements;
        }

        public String minecraftVersion() {
            return minecraftVersion;
        }

        public boolean extras() {
            return extras;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Hello)) return false;
            Hello other = (Hello) o;
            return protoVersion == other.protoVersion
                    && java.util.Objects.equals(loader, other.loader)
                    && java.util.Objects.equals(mods, other.mods)
                    && replacements == other.replacements
                    && java.util.Objects.equals(minecraftVersion, other.minecraftVersion)
                    && extras == other.extras;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + Integer.hashCode(protoVersion);
            h = 31 * h + java.util.Objects.hashCode(loader);
            h = 31 * h + java.util.Objects.hashCode(mods);
            h = 31 * h + Boolean.hashCode(replacements);
            h = 31 * h + java.util.Objects.hashCode(minecraftVersion);
            h = 31 * h + Boolean.hashCode(extras);
            return h;
        }

        @Override
        public String toString() {
            return "Hello[protoVersion=" + protoVersion + ", loader=" + loader + ", mods=" + mods + ", replacements=" + replacements + ", minecraftVersion=" + minecraftVersion + ", extras=" + extras + "]";
        }

        public Hello(int protoVersion, Loader loader, List<ModRef> mods) { this(protoVersion, loader, mods, false, ""); }
        public Hello(int protoVersion, Loader loader, List<ModRef> mods, boolean replacements, String minecraftVersion) {
            this(protoVersion, loader, mods, replacements, minecraftVersion, false);
        }
    
    }

    public static final class ModRef {
        private final String id;
        private final String version;

        public ModRef(String id, String version) {
            this.id = id;
            this.version = version;
        }

        public String id() {
            return id;
        }

        public String version() {
            return version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ModRef)) return false;
            ModRef other = (ModRef) o;
            return java.util.Objects.equals(id, other.id)
                    && java.util.Objects.equals(version, other.version);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(id);
            h = 31 * h + java.util.Objects.hashCode(version);
            return h;
        }

        @Override
        public String toString() {
            return "ModRef[id=" + id + ", version=" + version + "]";
        }
    }

    public static final class Manifest {
        private final int protoVersion;
        private final int flags;
        private final List<ModEntry> mods;
        private final Loader loader;
        private final String minecraftVersion;
        private final List<String> extraIds;

        public Manifest(int protoVersion, int flags, List<ModEntry> mods, Loader loader, String minecraftVersion, List<String> extraIds) {
            this.protoVersion = protoVersion;
            this.flags = flags;
            this.mods = mods;
            this.loader = loader;
            this.minecraftVersion = minecraftVersion;
            this.extraIds = extraIds;
        }

        public int protoVersion() {
            return protoVersion;
        }

        public int flags() {
            return flags;
        }

        public List<ModEntry> mods() {
            return mods;
        }

        public Loader loader() {
            return loader;
        }

        public String minecraftVersion() {
            return minecraftVersion;
        }

        public List<String> extraIds() {
            return extraIds;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Manifest)) return false;
            Manifest other = (Manifest) o;
            return protoVersion == other.protoVersion
                    && flags == other.flags
                    && java.util.Objects.equals(mods, other.mods)
                    && java.util.Objects.equals(loader, other.loader)
                    && java.util.Objects.equals(minecraftVersion, other.minecraftVersion)
                    && java.util.Objects.equals(extraIds, other.extraIds);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + Integer.hashCode(protoVersion);
            h = 31 * h + Integer.hashCode(flags);
            h = 31 * h + java.util.Objects.hashCode(mods);
            h = 31 * h + java.util.Objects.hashCode(loader);
            h = 31 * h + java.util.Objects.hashCode(minecraftVersion);
            h = 31 * h + java.util.Objects.hashCode(extraIds);
            return h;
        }

        @Override
        public String toString() {
            return "Manifest[protoVersion=" + protoVersion + ", flags=" + flags + ", mods=" + mods + ", loader=" + loader + ", minecraftVersion=" + minecraftVersion + ", extraIds=" + extraIds + "]";
        }

        public Manifest(int protoVersion, int flags, List<ModEntry> mods) { this(protoVersion, flags, mods, Loader.UNKNOWN, ""); }
        public Manifest(int protoVersion, int flags, List<ModEntry> mods, Loader loader, String minecraftVersion) {
            this(protoVersion, flags, mods, loader, minecraftVersion, java.util.Collections.emptyList());
        }
        public boolean streamed() {
            return (flags & MANIFEST_FLAG_STREAMED) != 0;
        }
    
    }

    public static final class FileBegin {
        private final String modId;
        private final long size;
        private final int chunkCount;
        private final byte[] sha512;
        private final int chunkSize;

        public FileBegin(String modId, long size, int chunkCount, byte[] sha512, int chunkSize) {
            this.modId = modId;
            this.size = size;
            this.chunkCount = chunkCount;
            this.sha512 = sha512;
            this.chunkSize = chunkSize;
        }

        public String modId() {
            return modId;
        }

        public long size() {
            return size;
        }

        public int chunkCount() {
            return chunkCount;
        }

        public byte[] sha512() {
            return sha512;
        }

        public int chunkSize() {
            return chunkSize;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FileBegin)) return false;
            FileBegin other = (FileBegin) o;
            return java.util.Objects.equals(modId, other.modId)
                    && size == other.size
                    && chunkCount == other.chunkCount
                    && java.util.Objects.equals(sha512, other.sha512)
                    && chunkSize == other.chunkSize;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(modId);
            h = 31 * h + Long.hashCode(size);
            h = 31 * h + Integer.hashCode(chunkCount);
            h = 31 * h + java.util.Objects.hashCode(sha512);
            h = 31 * h + Integer.hashCode(chunkSize);
            return h;
        }

        @Override
        public String toString() {
            return "FileBegin[modId=" + modId + ", size=" + size + ", chunkCount=" + chunkCount + ", sha512=" + sha512 + ", chunkSize=" + chunkSize + "]";
        }
    }

    public static final class FileChunk {
        private final String modId;
        private final int index;
        private final byte[] data;

        public FileChunk(String modId, int index, byte[] data) {
            this.modId = modId;
            this.index = index;
            this.data = data;
        }

        public String modId() {
            return modId;
        }

        public int index() {
            return index;
        }

        public byte[] data() {
            return data;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FileChunk)) return false;
            FileChunk other = (FileChunk) o;
            return java.util.Objects.equals(modId, other.modId)
                    && index == other.index
                    && java.util.Objects.equals(data, other.data);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(modId);
            h = 31 * h + Integer.hashCode(index);
            h = 31 * h + java.util.Objects.hashCode(data);
            return h;
        }

        @Override
        public String toString() {
            return "FileChunk[modId=" + modId + ", index=" + index + ", data=" + data + "]";
        }
    }

    public static final class FileAck {
        private final String modId;
        private final int nextContiguous;
        private final int[] gapIndices;

        public FileAck(String modId, int nextContiguous, int[] gapIndices) {
            this.modId = modId;
            this.nextContiguous = nextContiguous;
            this.gapIndices = gapIndices;
        }

        public String modId() {
            return modId;
        }

        public int nextContiguous() {
            return nextContiguous;
        }

        public int[] gapIndices() {
            return gapIndices;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FileAck)) return false;
            FileAck other = (FileAck) o;
            return java.util.Objects.equals(modId, other.modId)
                    && nextContiguous == other.nextContiguous
                    && java.util.Objects.equals(gapIndices, other.gapIndices);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(modId);
            h = 31 * h + Integer.hashCode(nextContiguous);
            h = 31 * h + java.util.Objects.hashCode(gapIndices);
            return h;
        }

        @Override
        public String toString() {
            return "FileAck[modId=" + modId + ", nextContiguous=" + nextContiguous + ", gapIndices=" + gapIndices + "]";
        }
    }

    public static final class FileDone {
        private final String modId;
        private final boolean ok;

        public FileDone(String modId, boolean ok) {
            this.modId = modId;
            this.ok = ok;
        }

        public String modId() {
            return modId;
        }

        public boolean ok() {
            return ok;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FileDone)) return false;
            FileDone other = (FileDone) o;
            return java.util.Objects.equals(modId, other.modId)
                    && ok == other.ok;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(modId);
            h = 31 * h + Boolean.hashCode(ok);
            return h;
        }

        @Override
        public String toString() {
            return "FileDone[modId=" + modId + ", ok=" + ok + "]";
        }
    }

    public static final class Abort {
        private final String reasonKey;

        public Abort(String reasonKey) {
            this.reasonKey = reasonKey;
        }

        public String reasonKey() {
            return reasonKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Abort)) return false;
            Abort other = (Abort) o;
            return java.util.Objects.equals(reasonKey, other.reasonKey);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(reasonKey);
            return h;
        }

        @Override
        public String toString() {
            return "Abort[reasonKey=" + reasonKey + "]";
        }
    }

    private static final List<String> EMPTY_IDS = java.util.Collections.emptyList();

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

    /** Append-only extension: the original overload deliberately keeps its exact v1 bytes. */
    public static byte[] encodeHello(Loader loader, List<ModRef> mods, String minecraftVersion) {
        return encodeHello(loader, mods, minecraftVersion, false);
    }

    /** {@code wantsExtras}: ask the host to list the joiner's mods it doesn't have (for removal). */
    public static byte[] encodeHello(Loader loader, List<ModRef> mods, String minecraftVersion, boolean wantsExtras) {
        byte[] original = encodeHello(loader, mods);
        ByteBuffer buf = ByteBuffer.allocate(original.length + 4 + utf8(minecraftVersion).length);
        buf.put(original).put((byte) EXTENSION_VERSION).put((byte) (HELLO_FLAG_REPLACEMENTS | (wantsExtras ? HELLO_FLAG_EXTRAS : 0)));
        putStr(buf, minecraftVersion);
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
        boolean replacements = false, extras = false;
        String minecraft = "";
        if (buf.remaining() >= 4 && u8(buf) == EXTENSION_VERSION) {
            int helloFlags = u8(buf);
            replacements = (helloFlags & HELLO_FLAG_REPLACEMENTS) != 0;
            extras = (helloFlags & HELLO_FLAG_EXTRAS) != 0;
            minecraft = getStr(buf);
        }
        return new Hello(proto, loader, mods, replacements, minecraft, extras);
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

    public static byte[] encodeManifest(int flags, List<ModEntry> mods, Loader loader, String minecraftVersion) {
        return encodeManifest(flags, mods, loader, minecraftVersion, EMPTY_IDS);
    }

    /** Appends {@code extraIds} (only when non-empty) after the replacement extension. */
    public static byte[] encodeManifest(int flags, List<ModEntry> mods, Loader loader, String minecraftVersion, List<String> extraIds) {
        boolean extras = !extraIds.isEmpty();
        byte[] original = encodeManifest(flags | MANIFEST_FLAG_REPLACEMENTS | (extras ? MANIFEST_FLAG_EXTRAS : 0), mods);
        int size = original.length + 4 + utf8(minecraftVersion).length + 2;
        for (String id : extraIds) size += 2 + utf8(id).length;
        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.put(original).put((byte) EXTENSION_VERSION).put(loader.wire());
        putStr(buf, minecraftVersion);
        if (extras) {
            buf.putShort((short) extraIds.size());
            for (String id : extraIds) putStr(buf, id);
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
        Loader loader = Loader.UNKNOWN;
        String minecraft = "";
        List<String> extraIds = new ArrayList<>();
        if ((flags & MANIFEST_FLAG_REPLACEMENTS) != 0 && buf.remaining() >= 4 && u8(buf) == EXTENSION_VERSION) {
            loader = Loader.fromWire(u8(buf));
            minecraft = getStr(buf);
            if ((flags & MANIFEST_FLAG_EXTRAS) != 0 && buf.remaining() >= 2) {
                int extras = u16(buf);
                for (int i = 0; i < extras && buf.remaining() > 0; i++) extraIds.add(getStr(buf));
            }
        }
        return new Manifest(proto, flags, mods, loader, minecraft, extraIds);
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
