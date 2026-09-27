package net.peercraft.client.mixin;

import net.minecraft.server.MinecraftServer;
import net.peercraft.network.handoff.ServerThreadTasks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class HandoffServerTickMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void peercraft$handoffTasks(CallbackInfo ci) { ServerThreadTasks.drain(this); }
}
