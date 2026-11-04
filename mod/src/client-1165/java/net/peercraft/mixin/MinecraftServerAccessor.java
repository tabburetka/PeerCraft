package net.peercraft.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../mixin/MinecraftServerAccessor.java}. Same
 * field name ({@code storageSource}) and type on 1.16.5's official mappings — no delta.
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess peercraft$storageSource();
}
