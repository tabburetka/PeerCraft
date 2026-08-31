package net.peercraft.forge1122;

import net.minecraftforge.fml.common.Loader;
import net.peercraft.platform.services.PeercraftPlatform;
import net.peercraft.platform.services.PlatformMod;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * 1.12.2 Forge implementation of the loader seam (cf. {@code FabricPlatform} /
 * {@code NeoForgePlatform}). Registered via
 * {@code META-INF/services/net.peercraft.platform.services.PeercraftPlatform}.
 *
 * <p>Mod sync is not backported — {@link #getInstalledMods()} is a stub so the interface
 * (which now names it) still compiles here.
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
        return Collections.emptyList();
    }
}
