package net.peercraft.client.mixin;

import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Disable the name-based embedded-player shortcut only while reading player data. */
@Mixin(PlayerList.class)
public abstract class PlayerDataLoadMixin {
    @Redirect(method = "load", at = @At(value = "INVOKE", target = "Ljava/lang/String;equals(Ljava/lang/Object;)Z"))
    private boolean peercraft$loadByUuid(String name, Object owner) { return false; }
}
