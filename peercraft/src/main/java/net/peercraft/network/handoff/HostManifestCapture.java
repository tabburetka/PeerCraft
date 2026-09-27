package net.peercraft.network.handoff;

import net.peercraft.platform.services.PlatformMod;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Hashes every execution dependency; mod-sync exclusion/environment flags are deliberately irrelevant. */
public final class HostManifestCapture {
    private static final Set<String> PLATFORM_IDS = new HashSet<>(Arrays.asList(
            "minecraft", "java", "fabricloader", "forge", "Forge", "neoforge", "FML", "mcp"));
    private HostManifestCapture() { }
    public static HostExecutionManifest capture(String minecraft, String loader, String loaderVersion,
            Collection<PlatformMod> installed, Path configRoot, Collection<String> externalConfigPaths) throws IOException {
        Map<String, PlatformMod> mods = new TreeMap<>();
        for (PlatformMod mod : installed) {
            if (mod.id() == null || mod.id().isEmpty() || mod.version() == null) throw new IOException("Unknown installed dependency");
            if (PLATFORM_IDS.contains(mod.id())) continue;
            if (mods.put(mod.id(), mod) != null) throw new IOException("Duplicate installed dependency: " + mod.id());
        }
        List<HostExecutionManifest.Mod> execution = new ArrayList<>();
        Map<Path, byte[]> hashes = new HashMap<>();
        for (PlatformMod mod : mods.values()) {
            Set<String> seen = new HashSet<>(); PlatformMod parent = mod;
            while (parent.parentId() != null && !parent.parentId().isEmpty()) {
                if (!seen.add(parent.id())) throw new IOException("Cyclic nested dependency: " + mod.id());
                parent = mods.get(parent.parentId());
                if (parent == null) throw new IOException("Unknown parent JAR: " + mod.id());
            }
            if (mod.nested() && (mod.parentId() == null || mod.parentId().isEmpty()))
                throw new IOException("Unknown parent JAR: " + mod.id());
            Path jar = parent.jarPath();
            if (jar == null) throw new IOException("Unknown mandatory JAR: " + mod.id());
            byte[] hash = hashes.get(jar);
            if (hash == null) { hash = HostExecutionManifest.hash(jar); hashes.put(jar, hash); }
            execution.add(new HostExecutionManifest.Mod(mod.id(), mod.version(),
                    mod.parentId() == null ? "" : mod.parentId(), hash));
        }
        try {
            return new HostExecutionManifest(minecraft, loader, loaderVersion, execution,
                    externalConfigPaths.isEmpty() ? Collections.emptyMap() : HostExecutionManifest.externalConfigs(configRoot, externalConfigPaths));
        } catch (IllegalArgumentException invalid) { throw new IOException("Incomplete host execution manifest", invalid); }
    }
}
