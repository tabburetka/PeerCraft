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
                out.add(new PlatformMod(mc.getModId(), mc.getVersion(), jar, "both", "", "", false));
            }
        } catch (RuntimeException ignored) {
            // Called too early / loader not ready — an empty list is fine (selfVersion() falls back).
        }
        return out;
    }
}
