package net.peercraft.forge1122;

import net.minecraftforge.fml.common.Loader;
import net.peercraft.platform.services.PeercraftPlatform;

import java.nio.file.Path;

/**
 * 1.12.2 Forge implementation of the loader seam (cf. {@code FabricPlatform} /
 * {@code NeoForgePlatform}). Registered via
 * {@code META-INF/services/net.peercraft.platform.services.PeercraftPlatform}.
 */
public final class ForgePlatform implements PeercraftPlatform {

    @Override
    public Path getConfigDir() {
        return Loader.instance().getConfigDir().toPath();
    }
}
