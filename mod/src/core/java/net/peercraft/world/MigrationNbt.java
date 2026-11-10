package net.peercraft.world;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Lossless typed NBT for offline migration, shared with Java 8 backports. No data fixing. */
final class MigrationNbt {
    static final int MAX_BYTES = 64 * 1024 * 1024;
    final int type;
    final Object value;
    private String rootName = "";

    MigrationNbt(int type, Object value) { this.type = type; this.value = value; }

    @SuppressWarnings("unchecked")
    Map<String, MigrationNbt> compound() { return (Map<String, MigrationNbt>) value; }

    MigrationNbt get(String key) { return type == 10 ? compound().get(key) : null; }

    static MigrationNbt read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(new FilterInputStream(stream) {
            private long remaining = MAX_BYTES;
            @Override public int read() throws IOException {
                if (--remaining < 0) throw new IOException("NBT exceeds migration limit");
                return super.read();
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = in.read(b, off, (int) Math.min(len, Math.max(1, remaining)));
                if (n > 0 && (remaining -= n) < 0) throw new IOException("NBT exceeds migration limit");
                return n;
            }
        });
        int type = in.readUnsignedByte();
        if (type != 10) throw new IOException("Expected compound NBT root");
        String name = in.readUTF();
        MigrationNbt root = readPayload(in, type, 0);
        root.rootName = name;
        // Reading through EOF also verifies gzip/zlib trailers instead of accepting a
        // truncated stream whose compound happened to finish before its checksum.
        if (in.read() != -1) throw new IOException("Trailing data after NBT root");
        return root;
    }

    static MigrationNbt compressed(InputStream in) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(in)) { return read(gzip); }
    }

    byte[] compressed() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) { write(out); }
        return bytes.toByteArray();
    }

    void write(OutputStream stream) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        out.writeByte(type);
        out.writeUTF(rootName);
        writePayload(out);
        out.flush();
    }

    private static int length(DataInput in, int width) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_BYTES / width) throw new IOException("Invalid NBT collection length");
        return n;
    }

    private static MigrationNbt readPayload(DataInput in, int type, int depth) throws IOException {
        if (depth > 128) throw new IOException("NBT nesting exceeds migration limit");
        Object value;
        switch (type) {
            case 1: value = in.readByte(); break;
            case 2: value = in.readShort(); break;
            case 3: value = in.readInt(); break;
            case 4: value = in.readLong(); break;
            case 5: value = in.readFloat(); break;
            case 6: value = in.readDouble(); break;
            case 7:
                byte[] bytes = new byte[length(in, 1)]; in.readFully(bytes); value = bytes; break;
            case 8: value = in.readUTF(); break;
            case 9:
                int element = in.readUnsignedByte();
                int count = length(in, 16);
                if (element > 12 || (element == 0 && count != 0)) throw new IOException("Invalid NBT list type");
                List<MigrationNbt> list = new ArrayList<MigrationNbt>();
                // Keep the element type even for empty lists.
                list.add(new MigrationNbt(element, null));
                for (int i = 0; i < count; i++) list.add(readPayload(in, element, depth + 1));
                value = list; break;
            case 10:
                Map<String, MigrationNbt> map = new LinkedHashMap<String, MigrationNbt>();
                int child;
                while ((child = in.readUnsignedByte()) != 0) {
                    String key = in.readUTF();
                    if (map.containsKey(key)) throw new IOException("Duplicate NBT key: " + key);
                    map.put(key, readPayload(in, child, depth + 1));
                }
                value = map; break;
            case 11:
                int[] ints = new int[length(in, 4)];
                for (int i = 0; i < ints.length; i++) ints[i] = in.readInt();
                value = ints; break;
            case 12:
                long[] longs = new long[length(in, 8)];
                for (int i = 0; i < longs.length; i++) longs[i] = in.readLong();
                value = longs; break;
            default: throw new IOException("Unknown NBT type " + type);
        }
        return new MigrationNbt(type, value);
    }

    @SuppressWarnings("unchecked")
    private void writePayload(DataOutput out) throws IOException {
        switch (type) {
            case 1: out.writeByte((Byte) value); break;
            case 2: out.writeShort((Short) value); break;
            case 3: out.writeInt((Integer) value); break;
            case 4: out.writeLong((Long) value); break;
            case 5: out.writeFloat((Float) value); break;
            case 6: out.writeDouble((Double) value); break;
            case 7: out.writeInt(((byte[]) value).length); out.write((byte[]) value); break;
            case 8: out.writeUTF((String) value); break;
            case 9:
                List<MigrationNbt> list = (List<MigrationNbt>) value;
                out.writeByte(list.get(0).type); out.writeInt(list.size() - 1);
                for (int i = 1; i < list.size(); i++) list.get(i).writePayload(out);
                break;
            case 10:
                for (Map.Entry<String, MigrationNbt> entry : compound().entrySet()) {
                    out.writeByte(entry.getValue().type); out.writeUTF(entry.getKey());
                    entry.getValue().writePayload(out);
                }
                out.writeByte(0); break;
            case 11:
                out.writeInt(((int[]) value).length);
                for (int i : (int[]) value) out.writeInt(i); break;
            case 12:
                out.writeInt(((long[]) value).length);
                for (long i : (long[]) value) out.writeLong(i); break;
            default: throw new IOException("Unknown NBT type " + type);
        }
    }

    static UUID uuid(MigrationNbt tag) {
        if (tag == null) return null;
        if (tag.type == 11 && ((int[]) tag.value).length == 4) {
            int[] a = (int[]) tag.value;
            return new UUID(((long) a[0] << 32) | (a[1] & 0xffffffffL),
                    ((long) a[2] << 32) | (a[3] & 0xffffffffL));
        }
        if (tag.type == 8) {
            try { return UUID.fromString((String) tag.value); } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    UUID playerUuid() {
        UUID id = uuid(get("UUID"));
        if (id != null) return id;
        MigrationNbt most = get("UUIDMost"), least = get("UUIDLeast");
        return most != null && least != null && most.type == 4 && least.type == 4
                ? new UUID((Long) most.value, (Long) least.value) : null;
    }

    static MigrationNbt uuidTag(UUID id, int type) {
        if (type == 8) return new MigrationNbt(8, id.toString());
        long m = id.getMostSignificantBits(), l = id.getLeastSignificantBits();
        return new MigrationNbt(11, new int[]{(int) (m >> 32), (int) m, (int) (l >> 32), (int) l});
    }

    void setPlayerUuid(UUID id) {
        if (get("UUIDMost") != null || get("UUIDLeast") != null) {
            compound().put("UUIDMost", new MigrationNbt(4, id.getMostSignificantBits()));
            compound().put("UUIDLeast", new MigrationNbt(4, id.getLeastSignificantBits()));
        }
        if (get("UUID") != null || get("UUIDMost") == null) compound().put("UUID", uuidTag(id, 11));
    }

    /** Only ownership references are changed; entity IDs, leashes and unrelated UUIDs stay intact. */
    @SuppressWarnings("unchecked")
    boolean replaceOwners(UUID oldId, UUID newId) {
        boolean changed = false;
        if (type == 10) {
            // Vanilla versions use OwnerUUID (string) or Owner (int array); some mods use OwnerUuid.
            for (String key : new String[]{"Owner", "OwnerUUID", "OwnerUuid", "owner"}) {
                MigrationNbt tag = get(key);
                if (oldId.equals(uuid(tag))) { compound().put(key, uuidTag(newId, tag.type)); changed = true; }
            }
            for (MigrationNbt child : compound().values()) changed |= child.replaceOwners(oldId, newId);
        } else if (type == 9) {
            List<MigrationNbt> list = (List<MigrationNbt>) value;
            for (int i = 1; i < list.size(); i++) changed |= list.get(i).replaceOwners(oldId, newId);
        }
        return changed;
    }
}
