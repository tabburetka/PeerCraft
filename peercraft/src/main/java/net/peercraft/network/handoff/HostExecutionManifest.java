package net.peercraft.network.handoff;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Execution dependencies, independent of which JARs mod-sync can redistribute. */
public final class HostExecutionManifest {
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ITEMS = 16_384;
    public static final class Mod {
        public final String id, version, parent;
        public final byte[] hash;
        public Mod(String id, String version, String parent, byte[] hash) {
            if (id == null || id.isEmpty() || version == null || parent == null || hash == null || hash.length != 64)
                throw new IllegalArgumentException("Incomplete execution dependency");
            this.id = id; this.version = version; this.parent = parent; this.hash = hash.clone();
        }
    }
    public static final class Profile {
        public final Set<String> clientOnlyExceptions;
        public final Set<String> permittedLoaderPairs;
        public Profile(Set<String> clientOnlyExceptions, Set<String> permittedLoaderPairs) {
            this.clientOnlyExceptions = Collections.unmodifiableSet(new HashSet<>(clientOnlyExceptions));
            this.permittedLoaderPairs = Collections.unmodifiableSet(new HashSet<>(permittedLoaderPairs));
        }
        public static Profile strict() { return new Profile(Collections.emptySet(), Collections.emptySet()); }
    }
    public final String minecraft, loader, loaderVersion;
    private final Map<String, Mod> mods;
    private final Map<String, byte[]> configs;
    public HostExecutionManifest(String minecraft, String loader, String loaderVersion, Collection<Mod> mods, Map<String, byte[]> configs) {
        if (minecraft == null || minecraft.isEmpty() || loader == null || loader.isEmpty() || loaderVersion == null)
            throw new IllegalArgumentException("Missing execution platform");
        if (mods.size() > MAX_ITEMS || configs.size() > MAX_ITEMS) throw new IllegalArgumentException("Manifest too large");
        this.minecraft = minecraft; this.loader = loader; this.loaderVersion = loaderVersion;
        Map<String, Mod> installed = new TreeMap<>();
        for (Mod mod : mods) if (installed.put(mod.id, new Mod(mod.id, mod.version, mod.parent, mod.hash)) != null)
            throw new IllegalArgumentException("Duplicate mod: " + mod.id);
        for (Mod mod : installed.values()) if (!mod.parent.isEmpty() && (!installed.containsKey(mod.parent) || mod.parent.equals(mod.id)))
            throw new IllegalArgumentException("Unknown nested parent: " + mod.id);
        for (Mod mod : installed.values()) {
            Set<String> seen = new HashSet<>(); Mod current = mod;
            while (!current.parent.isEmpty()) {
                if (!seen.add(current.id)) throw new IllegalArgumentException("Nested dependency cycle");
                current = installed.get(current.parent);
            }
        }
        this.mods = Collections.unmodifiableMap(installed);
        Map<String, byte[]> files = new TreeMap<>();
        for (Map.Entry<String, byte[]> item : configs.entrySet()) {
            validateConfig(item.getKey());
            if (item.getValue() == null || item.getValue().length != 64) throw new IllegalArgumentException("Invalid config hash");
            files.put(item.getKey(), item.getValue().clone());
        }
        this.configs = Collections.unmodifiableMap(files);
    }
    private static void validateConfig(String path) {
        if (path == null || path.isEmpty() || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0
                || Paths.get(path).isAbsolute() || !Paths.get(path).normalize().toString().replace(File.separatorChar, '/').equals(path)
                || path.equals("..") || path.startsWith("../")) throw new IllegalArgumentException("Invalid external config path");
    }
    /** Every difference is reported before freezing players; no files are installed or overwritten. */
    public List<String> differences(HostExecutionManifest successor, Profile profile) {
        List<String> result = new ArrayList<>();
        if (!minecraft.equals(successor.minecraft)) result.add("Minecraft version differs");
        if (!loader.equals(successor.loader)) {
            if (!profile.permittedLoaderPairs.contains(loader + "->" + successor.loader)) result.add("Loader differs");
        } else if (!loaderVersion.equals(successor.loaderVersion)) result.add("Loader version differs");
        Set<String> ids = new TreeSet<>(mods.keySet()); ids.addAll(successor.mods.keySet());
        for (String id : ids) {
            if (profile.clientOnlyExceptions.contains(id)) continue;
            Mod source = mods.get(id), target = successor.mods.get(id);
            if (source == null) result.add("Additional mandatory mod: " + id);
            else if (target == null) result.add("Missing mandatory mod: " + id);
            else {
                if (!source.version.equals(target.version)) result.add("Mod version differs: " + id);
                if (!Arrays.equals(source.hash, target.hash)) result.add("Mod hash differs: " + id);
                if (!source.parent.equals(target.parent)) result.add("Nested parent differs: " + id);
            }
        }
        // Only configured required external paths are compared; unrelated local files are allowed.
        for (Map.Entry<String, byte[]> file : configs.entrySet())
            if (!Arrays.equals(file.getValue(), successor.configs.get(file.getKey()))) result.add("External config differs: " + file.getKey());
        return Collections.unmodifiableList(result);
    }
    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream bounded = new FilterOutputStream(bytes) {
            private int size;
            private void reserve(int length) throws IOException {
                if (length < 0 || size > MAX_BYTES - length) throw new IOException("Manifest exceeds byte limit");
                size += length;
            }
            @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
            @Override public void write(byte[] data, int offset, int length) throws IOException { reserve(length); out.write(data, offset, length); }
        };
        try (DataOutputStream out = new DataOutputStream(bounded)) {
            out.writeInt(0x50434d32); out.writeUTF(minecraft); out.writeUTF(loader); out.writeUTF(loaderVersion);
            out.writeInt(mods.size());
            for (Mod mod : mods.values()) { out.writeUTF(mod.id); out.writeUTF(mod.version); out.writeUTF(mod.parent); out.write(mod.hash); }
            out.writeInt(configs.size());
            for (Map.Entry<String, byte[]> file : configs.entrySet()) { out.writeUTF(file.getKey()); out.write(file.getValue()); }
        }
        if (bytes.size() > MAX_BYTES) throw new IOException("Manifest exceeds byte limit");
        return bytes.toByteArray();
    }
    public static HostExecutionManifest decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Manifest exceeds byte limit");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x50434d32) throw new IOException("Unsupported execution manifest");
            String minecraft = in.readUTF(), loader = in.readUTF(), loaderVersion = in.readUTF();
            int count = count(in); List<Mod> mods = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String id = in.readUTF(), version = in.readUTF(), parent = in.readUTF();
                byte[] hash = new byte[64]; in.readFully(hash); mods.add(new Mod(id, version, parent, hash));
            }
            count = count(in); Map<String, byte[]> configs = new HashMap<>();
            for (int i = 0; i < count; i++) {
                String path = in.readUTF(); byte[] hash = new byte[64]; in.readFully(hash);
                if (configs.put(path, hash) != null) throw new IOException("Duplicate config path");
            }
            if (in.available() != 0) throw new IOException("Trailing manifest bytes");
            return new HostExecutionManifest(minecraft, loader, loaderVersion, mods, configs);
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid execution manifest", invalid); }
    }
    private static int count(DataInputStream in) throws IOException {
        int count = in.readInt(); if (count < 0 || count > MAX_ITEMS) throw new IOException("Invalid manifest item count"); return count;
    }
    public static byte[] hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            try (InputStream in = Files.newInputStream(file)) { byte[] buffer = new byte[64 * 1024]; int read;
                while ((read = in.read(buffer)) >= 0) if (read != 0) digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException unavailable) { throw new IOException("SHA-512 unavailable", unavailable); }
    }
    public static Map<String, byte[]> externalConfigs(Path configRoot, Collection<String> requiredPaths) throws IOException {
        Path root = configRoot.toRealPath(); Map<String, byte[]> result = new TreeMap<>();
        for (String path : requiredPaths) {
            try { validateConfig(path); } catch (IllegalArgumentException invalid) { throw new IOException(invalid); }
            Path file = root.resolve(path).toRealPath();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) throw new IOException("External config escapes its root");
            result.put(path, hash(file));
        }
        return result;
    }
}
