package net.peercraft.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code MinecraftServer.storageSource} so the handoff code can find the running
 * world's directory on disk ({@code LevelStorageAccess.getLevelPath(LevelResource.ROOT)}) to
 * archive it for the successor. There is no public accessor for the level directory otherwise.
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess peercraft$storageSource();
}
