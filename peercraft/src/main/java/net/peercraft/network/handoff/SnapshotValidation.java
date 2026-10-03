package net.peercraft.network.handoff;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/** Validate Minecraft save structure without modifying files or building an in-memory world. */
public final class SnapshotValidation {
    private static final long MAX_NBT_BYTES = 64L * 1024 * 1024;
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.(mca|mcr)");
    private SnapshotValidation() { }
    public static void validate(Path world) throws IOException {
        validateLevel(world.resolve("level.dat"));
        try (Stream<Path> files = Files.walk(world)) {
            Iterator<Path> paths = files.iterator(); long count = 0;
            while (paths.hasNext()) {
                Path file = paths.next(); if (++count > 1_000_000) throw new IOException("Snapshot has too many entries");
                if (Thread.currentThread().isInterrupted()) throw new IOException("Snapshot verification cancelled");
                if (Files.isSymbolicLink(file)) throw new IOException("Snapshot contains a symbolic link");
                if (!Files.isRegularFile(file)) continue;
                String parent = file.getParent().getFileName().toString();
                if ((parent.equals("region") || parent.equals("entities") || parent.equals("poi"))
                        && REGION.matcher(file.getFileName().toString()).matches()) validateRegion(file);
            }
        }
    }
    public static void validateLevel(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing level.dat");
        try (InputStream raw = Files.newInputStream(file); GZIPInputStream gzip = new GZIPInputStream(raw)) {
            InputStream bounded = new FilterInputStream(gzip) {
                long remaining = MAX_NBT_BYTES;
                public int read() throws IOException { if (remaining-- <= 0) throw new IOException("NBT exceeds snapshot limit"); return in.read(); }
                public int read(byte[] bytes, int off, int len) throws IOException {
                    if (remaining <= 0) throw new IOException("NBT exceeds snapshot limit");
                    int read = in.read(bytes, off, (int)Math.min(len, remaining)); if (read > 0) remaining -= read; return read;
                }
            };
            DataInputStream in = new DataInputStream(bounded);
            if (in.readUnsignedByte() != 10) throw new IOException("Invalid level.dat root");
            in.readUTF(); boolean data = false; long[] tags = {0};
            int type;
            while ((type = in.readUnsignedByte()) != 0) {
                String name = in.readUTF(); if (name.equals("Data")) data = type == 10;
                payload(in, type, 1, tags);
            }
            if (!data || in.read() != -1) throw new IOException("Incomplete or invalid level.dat");
        }
    }
    private static void payload(DataInputStream in, int type, int depth, long[] tags) throws IOException {
        if (depth > 128 || ++tags[0] > 1_000_000) throw new IOException("NBT structure exceeds snapshot limit");
        switch (type) {
            case 1: in.readByte(); break;
            case 2: in.readShort(); break;
            case 3: in.readInt(); break;
            case 4: in.readLong(); break;
            case 5: in.readFloat(); break;
            case 6: in.readDouble(); break;
            case 7: skip(in, length(in, 1)); break;
            case 8: in.readUTF(); break;
            case 9: {
                int element = in.readUnsignedByte(), count = length(in, 1);
                if (element > 12 || (element == 0 && count != 0)) throw new IOException("Invalid NBT list");
                for (int i = 0; i < count; i++) payload(in, element, depth + 1, tags); break;
            }
            case 10: {
                int child; while ((child = in.readUnsignedByte()) != 0) { in.readUTF(); payload(in, child, depth + 1, tags); } break;
            }
            case 11: skip(in, (long)length(in, 4) * 4); break;
            case 12: skip(in, (long)length(in, 8) * 8); break;
            default: throw new IOException("Unknown NBT type");
        }
    }
    private static int length(DataInputStream in, int width) throws IOException {
        int length = in.readInt(); if (length < 0 || length > MAX_NBT_BYTES / width) throw new IOException("Invalid NBT length"); return length;
    }
    private static void skip(DataInputStream in, long length) throws IOException {
        byte[] bytes = new byte[8192];
        while (length > 0) { int read = in.read(bytes, 0, (int)Math.min(bytes.length, length)); if (read < 0) throw new EOFException(); length -= read; }
    }
    public static void validateRegion(Path region) throws IOException {
        long size = Files.size(region);
        // Minecraft may leave an unopened, empty region file (notably in poi/).
        // It contains no header or chunks; any non-empty partial header is corrupt.
        if (size == 0) return;
        if (size < 8192 || size % 4096 != 0) throw new IOException("Truncated region file: " + region.getFileName());
        Matcher name = REGION.matcher(region.getFileName().toString());
        if (!name.matches()) throw new IOException("Invalid region name");
        long x, z;
        try { x = Math.multiplyExact(Long.parseLong(name.group(1)), 32); z = Math.multiplyExact(Long.parseLong(name.group(2)), 32); }
        catch (ArithmeticException | NumberFormatException invalid) { throw new IOException("Invalid region coordinates", invalid); }
        BitSet allocated = new BitSet(); allocated.set(0, 2);
        try (FileChannel file = FileChannel.open(region, StandardOpenOption.READ)) {
            ByteBuffer locations = ByteBuffer.allocate(4096); readFully(file, locations, 0); locations.flip();
            for (int index = 0; index < 1024; index++) {
                int location = locations.getInt(), sector = location >>> 8, sectors = location & 255;
                if (location == 0) continue;
                if (sector < 2 || sectors == 0 || ((long)sector + sectors) * 4096 > size
                        || allocated.nextSetBit(sector) >= 0 && allocated.nextSetBit(sector) < sector + sectors)
                    throw new IOException("Invalid/overlapping region allocation");
                allocated.set(sector, sector + sectors);
                ByteBuffer header = ByteBuffer.allocate(5); readFully(file, header, (long)sector * 4096); header.flip();
                int length = header.getInt(), compression = header.get() & 255;
                int format = compression & 127;
                if (length < 1 || length > sectors * 4096 - 4 || format < 1 || format > 4)
                    throw new IOException("Invalid region chunk header");
                if ((compression & 128) != 0) {
                    Path external = region.getParent().resolve("c." + (x + index % 32) + "." + (z + index / 32) + ".mcc");
                    if (length != 1 || !Files.isRegularFile(external, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing external chunk data");
                }
            }
        }
    }
    private static void readFully(FileChannel file, ByteBuffer bytes, long position) throws IOException {
        while (bytes.hasRemaining()) { int read = file.read(bytes, position); if (read < 0) throw new EOFException(); position += read; }
    }
}
