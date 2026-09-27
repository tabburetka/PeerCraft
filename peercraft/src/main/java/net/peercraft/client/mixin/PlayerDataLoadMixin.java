package net.peercraft.client.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
//? if >=1.21.9
/*import net.minecraft.server.players.NameAndId;*/
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Disable ONLY the embedded-owner load shortcut. Vanilla saving remains untouched. */
@Mixin(PlayerList.class)
public abstract class PlayerDataLoadMixin {
    //? if <1.21.9 {
    @Redirect(method = "load", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;isSingleplayerOwner(Lcom/mojang/authlib/GameProfile;)Z"))
    private boolean peercraft$loadByUuid(MinecraftServer server, GameProfile profile) {
        return false;
    }
    //?} else {
    /*@Redirect(method = "loadPlayerData", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;isSingleplayerOwner(Lnet/minecraft/server/players/NameAndId;)Z"))
    private boolean peercraft$loadByUuid(MinecraftServer server, NameAndId profile) {
        return false;
    }
    *///?}
}
