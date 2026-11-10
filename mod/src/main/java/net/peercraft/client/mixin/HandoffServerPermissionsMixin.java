package net.peercraft.client.mixin;

//? if <1.21.9
import com.mojang.authlib.GameProfile;
//? if >=1.21.9
/*import net.minecraft.server.players.NameAndId;*/
import net.minecraft.server.MinecraftServer;
import net.peercraft.client.handoff.HandoffCommandOwner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// 26.3 moved the singleplayer-owner check out of MinecraftServer.getProfilePermissions into
// IntegratedServer's override of it.
//? if <26.3 {
@Mixin(MinecraftServer.class)
//?} else {
/*@Mixin(net.minecraft.client.server.IntegratedServer.class)
*///?}
public abstract class HandoffServerPermissionsMixin {
    //? if <1.21.9 {
    @Redirect(method = "getProfilePermissions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;isSingleplayerOwner(Lcom/mojang/authlib/GameProfile;)Z"))
    private boolean peercraft$commandOwner(MinecraftServer server, GameProfile profile) {
        Boolean override = HandoffCommandOwner.override(server, profile.getId());
        return override != null ? override : server.isSingleplayerOwner(profile);
    }
    //?} else if <26.3 {
    /*@Redirect(method = "getProfilePermissions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;isSingleplayerOwner(Lnet/minecraft/server/players/NameAndId;)Z"))
    private boolean peercraft$commandOwner(MinecraftServer server, NameAndId profile) {
        Boolean override = HandoffCommandOwner.override(server, profile.id());
        return override != null ? override : server.isSingleplayerOwner(profile);
    }*/
    //?} else {
    /*@Redirect(method = "getProfilePermissions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/server/IntegratedServer;isSingleplayerOwner(Lnet/minecraft/server/players/NameAndId;)Z"))
    private boolean peercraft$commandOwner(net.minecraft.client.server.IntegratedServer server, NameAndId profile) {
        Boolean override = HandoffCommandOwner.override(server, profile.id());
        return override != null ? override : server.isSingleplayerOwner(profile);
    }*/
    //?}
}
