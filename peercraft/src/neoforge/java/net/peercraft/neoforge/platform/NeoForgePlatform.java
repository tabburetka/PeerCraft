package net.peercraft.neoforge.platform;

import net.neoforged.fml.loading.FMLPaths;
import net.peercraft.platform.services.PeercraftPlatform;

import java.nio.file.Path;

public final class NeoForgePlatform implements PeercraftPlatform {
    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}
