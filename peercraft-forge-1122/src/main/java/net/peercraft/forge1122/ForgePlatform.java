package net.peercraft.forge1122;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.peercraft.platform.services.PeercraftPlatform;
import net.peercraft.platform.services.PlatformMod;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.12.2 Forge implementation of the loader seam (cf. {@code FabricPlatform} /
 * {@code NeoForgePlatform}). Registered via
 * {@code META-INF/services/net.peercraft.platform.services.PeercraftPlatform}.
 *
 * <p>Mod sync is backported here too. {@link #getInstalledMods()} is only used to read
 * PeerCraft's own version (and as a fallback mod list) — mod-sync enumerates the real jar set
 * through {@code ModJarScanner}, not this.
 */
public final class ForgePlatform implements PeercraftPlatform {

    @Override
    public Path getConfigDir() {
        return Loader.instance().getConfigDir().toPath();
    }

    @Override
    public Path getModsDir() {
        return Loader.instance().getConfigDir().toPath().resolveSibling("mods");
    }

    @Override
    public List<PlatformMod> getInstalledMods() {
        List<PlatformMod> out = new ArrayList<PlatformMod>();
        try {
            for (ModContainer mc : Loader.instance().getActiveModList()) {
                File src = mc.getSource();
                Path jar = src != null ? src.toPath() : null;
                // MixinBooter's synthetic FML container inherits the dummy minecraft.jar
                // source. Hash its actual container implementation JAR for handoff checks.
                if ("mixinbooter".equalsIgnoreCase(mc.getModId())
                        && (jar == null || !java.nio.file.Files.isRegularFile(jar))) {
                    try {
                        java.net.URL resource = mc.getClass().getResource("/zone/rong/mixinbooter/MixinBooterPlugin.class");
                        java.net.URL location = resource != null && "jar".equals(resource.getProtocol())
                                ? ((java.net.JarURLConnection) resource.openConnection()).getJarFileURL() : null;
                        if (location != null && "file".equals(location.getProtocol())) {
                            Path actual = java.nio.file.Paths.get(location.toURI());
                            if (java.nio.file.Files.isRegularFile(actual)) jar = actual;
                        }
                    } catch (java.io.IOException | java.net.URISyntaxException | SecurityException invalidSource) {
                        // Keep the unresolved source: manifest capture must fail closed.
                    }
                }
                out.add(new PlatformMod(mc.getModId(), mc.getVersion(), jar, "both", "", "", false));
            }
        } catch (RuntimeException ignored) {
            // Called too early / loader not ready — an empty list is fine (selfVersion() falls back).
        }
        boolean minecraft = false;
        for (PlatformMod mod : out) if ("minecraft".equalsIgnoreCase(mod.id())) minecraft = true;
        if (!minecraft) out.add(new PlatformMod("minecraft", "1.12.2", null, "both", "", "", false));
        return net.peercraft.network.handoff.NestedModParents.containedDependencies(out);
    }
}
