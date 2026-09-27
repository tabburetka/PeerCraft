package net.peercraft.world;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.zip.*;

/** Offline region editing, including post-1.17 entities/ regions and external .mcc chunks. */
final class MigrationRegions {
    static void stage(Path file, UUID oldId, UUID newId, MigrationTransaction tx) throws IOException {
        Path staged = tx.temporary();
        Files.copy(file, staged, StandardCopyOption.REPLACE_EXISTING);
        boolean changed = false;
        try (RandomAccessFile region = new RandomAccessFile(staged.toFile(), "rw")) {
            if (region.length() < 8192) throw new IOException("Truncated region: " + file);
            for (int slot = 0; slot < 1024; slot++) {
                region.seek(slot * 4L);
                int location = region.readInt();
                if (location == 0) continue;
                int sector = location >>> 8, sectors = location & 255;
                if (sector < 2 || sectors == 0 || (sector + (long) sectors) * 4096 > region.length())
                    throw new IOException("Invalid region location: " + file);
                region.seek(sector * 4096L);
                int length = region.readInt(), compression = region.readUnsignedByte();
                if (length < 1 || length > sectors * 4096 - 4) throw new IOException("Invalid region chunk length");
                boolean external = (compression & 128) != 0;
                int codec = compression & 127;
                Path externalFile = external ? externalPath(file, slot) : null;
                byte[] payload;
                if (external) {
                    if (Files.size(externalFile) > MigrationNbt.MAX_BYTES) throw new IOException("External chunk too large");
                    payload = Files.readAllBytes(externalFile);
                } else {
                    payload = new byte[length - 1]; region.readFully(payload);
                }
                MigrationNbt nbt;
                try (InputStream input = decompress(payload, codec)) { nbt = MigrationNbt.read(input); }
                if (!nbt.replaceOwners(oldId, newId)) continue;
                // Write changed chunks as zlib, which all supported versions understand.
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DeflaterOutputStream out = new DeflaterOutputStream(bytes)) { nbt.write(out); }
                byte[] updated = bytes.toByteArray();
                if (external) {
                    tx.stage(externalFile, updated);
                    region.seek(sector * 4096L + 4); region.writeByte(128 | 2);
                } else {
                    int required = (updated.length + 5 + 4095) / 4096;
                    if (required > 255) throw new IOException("Migrated chunk exceeds inline region limit: " + file);
                    long offset = ((region.length() + 4095) / 4096) * 4096;
                    if (offset / 4096 > 0xffffff) throw new IOException("Region offset overflow");
                    region.setLength(offset + required * 4096L);
                    region.seek(offset); region.writeInt(updated.length + 1); region.writeByte(2); region.write(updated);
                    region.seek(slot * 4L); region.writeInt(((int) (offset / 4096) << 8) | required);
                }
                changed = true;
            }
        }
        if (changed) tx.stage(file, staged); else Files.delete(staged);
    }

    private static InputStream decompress(byte[] bytes, int codec) throws IOException {
        InputStream in = new ByteArrayInputStream(bytes);
        switch (codec) {
            case 1: return new GZIPInputStream(in);
            case 2: return new InflaterInputStream(in);
            case 3: return in;
            case 4:
                // Minecraft versions supporting LZ4 already ship this library; legacy builds do not need it.
                try {
                    return (InputStream) Class.forName("net.jpountz.lz4.LZ4BlockInputStream")
                            .getConstructor(InputStream.class).newInstance(in);
                } catch (ReflectiveOperationException e) { throw new IOException("Cannot decode LZ4 region", e); }
            default: throw new IOException("Unsupported region compression " + codec);
        }
    }

    private static Path externalPath(Path region, int slot) throws IOException {
        String[] name = region.getFileName().toString().split("\\.");
        try {
            int x = Integer.parseInt(name[1]) * 32 + slot % 32;
            int z = Integer.parseInt(name[2]) * 32 + slot / 32;
            return region.resolveSibling("c." + x + "." + z + ".mcc");
        } catch (RuntimeException e) { throw new IOException("Invalid region name " + region, e); }
    }
}
