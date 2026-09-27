package net.peercraft.client.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.server.IntegratedPlayerList;
import net.minecraft.client.server.IntegratedServer;
import net.peercraft.network.p2p.LocalPlayerIdentity;
//? if >=1.21.9
/*import net.minecraft.server.players.NameAndId;*/
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Data.Player remains vanilla-written, but only the real local owner's UUID may update it. */
@Mixin(IntegratedPlayerList.class)
public abstract class PlayerDataSaveMixin {
    //? if <26.1 {
    //? if <1.21.9 {
    @Redirect(method = "save", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/server/IntegratedServer;isSingleplayerOwner(Lcom/mojang/authlib/GameProfile;)Z"))
    private boolean peercraft$saveActualOwner(IntegratedServer server, GameProfile profile) {
        return LocalPlayerIdentity.current() != null && LocalPlayerIdentity.current().equals(profile.getId());
    }
    //?} else {
    /*@Redirect(method = "save", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/server/IntegratedServer;isSingleplayerOwner(Lnet/minecraft/server/players/NameAndId;)Z"))
    private boolean peercraft$saveActualOwner(IntegratedServer server, NameAndId profile) {
        return LocalPlayerIdentity.current() != null && LocalPlayerIdentity.current().equals(profile.id());
    }
    *///?}
    //?}
}
