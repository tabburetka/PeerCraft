package net.peercraft.client.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code MinecraftServer.saveAllWorlds(boolean)} for the handoff world archiver — on
 * 1.7.10 that method is {@code protected} (it's {@code public} on 1.12.2, no invoker needed
 * there); {@code getActiveAnvilConverter()}/{@code getFolderName()} are already public on both,
 * so no accessor is needed for locating the world directory itself.
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerInvoker {

    @Invoker("saveAllWorlds")
    void peercraft$saveAllWorlds(boolean dontLog);
}
