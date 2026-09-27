package net.peercraft.client.mixin;

import net.minecraft.server.integrated.IntegratedPlayerList;
import net.minecraft.entity.player.EntityPlayerMP;
import net.peercraft.network.p2p.LocalPlayerIdentity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Preserve vanilla owner snapshots; never let a same-name guest overwrite them. */
@Mixin(IntegratedPlayerList.class)
public abstract class PlayerDataSaveMixin {
    @Redirect(method = "writePlayerData", at = @At(value = "INVOKE", target = "Ljava/lang/String;equals(Ljava/lang/Object;)Z"))
    private boolean peercraft$saveActualOwner(String name, Object owner, EntityPlayerMP player) {
        return LocalPlayerIdentity.current() != null && LocalPlayerIdentity.current().equals(player.getUniqueID());
    }
}
