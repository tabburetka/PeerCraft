package net.peercraft.client.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.peercraft.client.handoff.HandoffCommandOwner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MinecraftServer.class)
public abstract class HandoffServerPermissionsMixin {
    @Redirect(method = "getProfilePermissions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;isSingleplayerOwner(Lcom/mojang/authlib/GameProfile;)Z"))
    private boolean peercraft$commandOwner(MinecraftServer server, GameProfile profile) {
        Boolean override = HandoffCommandOwner.override(server, profile.getId());
        return override != null ? override : server.isSingleplayerOwner(profile);
    }
}
