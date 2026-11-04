package net.peercraft.client.mixin;

//? if <1.21.9
import com.mojang.authlib.GameProfile;
//? if >=1.21.9
/*import net.minecraft.server.players.NameAndId;*/
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.peercraft.client.handoff.HandoffCommandOwner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerList.class)
public abstract class HandoffPlayerListPermissionsMixin {
    //? if <1.21.9 {
    @Redirect(method = "isOp", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;isSingleplayerOwner(Lcom/mojang/authlib/GameProfile;)Z"))
    private boolean peercraft$commandOwner(MinecraftServer target, GameProfile profile) {
        Boolean override = HandoffCommandOwner.override(target, profile.getId());
        return override != null ? override : target.isSingleplayerOwner(profile);
    }
    //?} else {
    /*@Redirect(method = "isOp", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;isSingleplayerOwner(Lnet/minecraft/server/players/NameAndId;)Z"))
    private boolean peercraft$commandOwner(MinecraftServer target, NameAndId profile) {
        Boolean override = HandoffCommandOwner.override(target, profile.id());
        return override != null ? override : target.isSingleplayerOwner(profile);
    }*/
    //?}
}
