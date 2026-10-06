package net.peercraft.client.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.peercraft.client.handoff.HandoffCommandOwner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerList.class)
public abstract class HandoffPlayerListPermissionsMixin {
    @Redirect(method = "isOp", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;isSingleplayerOwner(Lcom/mojang/authlib/GameProfile;)Z"))
    private boolean peercraft$commandOwner(MinecraftServer target, GameProfile profile) {
        Boolean override = HandoffCommandOwner.override(target, profile.getId());
        return override != null ? override : target.isSingleplayerOwner(profile);
    }
}
