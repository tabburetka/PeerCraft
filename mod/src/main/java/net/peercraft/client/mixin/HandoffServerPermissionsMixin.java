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

@Mixin(MinecraftServer.class)
public abstract class HandoffServerPermissionsMixin {
    //? if <1.21.9 {
    @Redirect(method = "getProfilePermissions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;isSingleplayerOwner(Lcom/mojang/authlib/GameProfile;)Z"))
    private boolean peercraft$commandOwner(MinecraftServer server, GameProfile profile) {
        Boolean override = HandoffCommandOwner.override(server, profile.getId());
        return override != null ? override : server.isSingleplayerOwner(profile);
    }
    //?} else {
    /*@Redirect(method = "getProfilePermissions", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;isSingleplayerOwner(Lnet/minecraft/server/players/NameAndId;)Z"))
    private boolean peercraft$commandOwner(MinecraftServer server, NameAndId profile) {
        Boolean override = HandoffCommandOwner.override(server, profile.id());
        return override != null ? override : server.isSingleplayerOwner(profile);
    }*/
    //?}
}
