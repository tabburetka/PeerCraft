package net.peercraft.network.handoff;

import net.peercraft.platform.services.PlatformMod;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;

/** Legacy FML exposes extracted dependencies as files. Match only declared ContainedDeps bytes. */
public final class NestedModParents {
    private NestedModParents() { }
    public static List<PlatformMod> containedDependencies(List<PlatformMod> installed) {
        Map<String, String> parents = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        Map<Path, byte[]> hashes = new HashMap<>();
        List<PlatformMod> sorted = new ArrayList<>(installed); sorted.sort(Comparator.comparing(PlatformMod::id));
        Set<Path> seen = new HashSet<>();
        for (PlatformMod parent : sorted) {
            Path file = parent.jarPath();
            if (file == null || !Files.isRegularFile(file) || !seen.add(file)) continue;
            try (JarFile jar = new JarFile(file.toFile())) {
                if (jar.getManifest() == null) continue;
                String deps = jar.getManifest().getMainAttributes().getValue("ContainedDeps");
                if (deps == null) continue;
                String[] names = deps.split(","); if (names.length > 256) throw new IOException("Too many contained dependencies");
                long remaining = 512L * 1024 * 1024;
                for (String name : names) {
                    JarEntry entry = jar.getJarEntry(name.trim());
                    if (entry == null || entry.isDirectory() || entry.getSize() < 0 || entry.getSize() > remaining) throw new IOException("Invalid declared dependency");
                    remaining -= entry.getSize(); byte[] nested;
                    try (InputStream in = jar.getInputStream(entry)) { nested = hash(in, entry.getSize()); }
                    for (PlatformMod child : sorted) {
                        Path path = child.jarPath(); if (path == null || path.equals(file) || !Files.isRegularFile(path)) continue;
                        byte[] digest = hashes.get(path);
                        if (digest == null) { digest = HostExecutionManifest.hash(path); hashes.put(path, digest); }
                        if (MessageDigest.isEqual(nested, digest)) {
                            String old = parents.putIfAbsent(child.id(), parent.id());
                            if (old != null && !old.equals(parent.id())) ambiguous.add(child.id());
                        }
                    }
                }
            } catch (IOException malformed) {
                // Fail closed in manifest capture instead of silently omitting malformed parents.
                ambiguous.add(parent.id());
            }
        }
        List<PlatformMod> result = new ArrayList<>();
        for (PlatformMod mod : installed) {
            String parent = ambiguous.contains(mod.id()) ? "" : parents.getOrDefault(mod.id(), mod.parentId());
            result.add(new PlatformMod(mod.id(), mod.version(), mod.jarPath(), mod.environment(), mod.homepageUrl(),
                    mod.sourcesUrl(), mod.nested() || parents.containsKey(mod.id()) || ambiguous.contains(mod.id()), parent));
        }
        return result;
    }
    private static byte[] hash(InputStream in, long limit) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512"); byte[] bytes = new byte[65536]; long read = 0; int n;
            while ((n = in.read(bytes)) != -1) { read += n; if (read > limit) throw new IOException("Contained dependency grew"); digest.update(bytes, 0, n); }
            if (read != limit) throw new IOException("Truncated contained dependency"); return digest.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
}
