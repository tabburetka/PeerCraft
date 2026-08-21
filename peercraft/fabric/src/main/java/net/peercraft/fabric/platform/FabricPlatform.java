package net.peercraft.fabric.platform;

import net.fabricmc.loader.api.FabricLoader;
import net.peercraft.platform.services.PeercraftPlatform;

import java.nio.file.Path;

public final class FabricPlatform implements PeercraftPlatform {
    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }
}
