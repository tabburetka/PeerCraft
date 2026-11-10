package net.peercraft.forge189;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.peercraft.platform.services.PeercraftPlatform;
import net.peercraft.platform.services.PlatformMod;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.8.9 Forge implementation of the loader seam (cf. the 1.12.2 one). Registered via
 * {@code META-INF/services/net.peercraft.platform.services.PeercraftPlatform}.
 *
 * <p>{@link #getInstalledMods()} is only used to read PeerCraft's own version (and as a fallback
 * mod list) — mod-sync enumerates the real jar set through {@code ModJarScanner}, not this.
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
                out.add(new PlatformMod(mc.getModId(), mc.getVersion(), src != null ? src.toPath() : null, "both", "", "", false));
            }
        } catch (RuntimeException ignored) {
            // Called too early / loader not ready — an empty list is fine (selfVersion() falls back).
        }
        boolean minecraft = false;
        for (PlatformMod mod : out) if ("minecraft".equalsIgnoreCase(mod.id())) minecraft = true;
        if (!minecraft) out.add(new PlatformMod("minecraft", "1.8.9", null, "both", "", "", false));
        return net.peercraft.network.handoff.NestedModParents.containedDependencies(out);
    }
}
