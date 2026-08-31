package net.peercraft.forge1710;

import cpw.mods.fml.common.Loader;
import net.peercraft.platform.services.PeercraftPlatform;
import net.peercraft.platform.services.PlatformMod;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * 1.7.10 Forge implementation of the loader seam (twin of the 1.12.2 {@code ForgePlatform}).
 * Registered via {@code META-INF/services/net.peercraft.platform.services.PeercraftPlatform}.
 * Only the FML package prefix differs from the 1.12.2 backport ({@code cpw.mods.fml}).
 *
 * <p>Mod sync is not backported — {@link #getInstalledMods()} is a stub so the interface
 * still compiles here.
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
