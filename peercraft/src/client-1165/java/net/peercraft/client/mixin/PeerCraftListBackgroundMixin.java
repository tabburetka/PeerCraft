package net.peercraft.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.peercraft.client.gui.PeerCraftMultiplayerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Background/edge flags are disabled by the screen; constrain vanilla rows to its panel. */
@Mixin(ServerSelectionList.class)
public abstract class PeerCraftListBackgroundMixin {
    @Inject(method = "getRowWidth", at = @At("HEAD"), cancellable = true)
    private void peercraft$rowWidth(CallbackInfoReturnable<Integer> ci) {
        if (Minecraft.getInstance().screen instanceof PeerCraftMultiplayerScreen)
            ci.setReturnValue(((PeerCraftMultiplayerScreen) Minecraft.getInstance().screen).peercraft$listRowWidth());
    }
    @Inject(method = "getScrollbarPosition", at = @At("HEAD"), cancellable = true)
    private void peercraft$scrollbar(CallbackInfoReturnable<Integer> ci) {
        if (Minecraft.getInstance().screen instanceof PeerCraftMultiplayerScreen)
            ci.setReturnValue(((PeerCraftMultiplayerScreen) Minecraft.getInstance().screen).peercraft$listScrollbarX());
    }
}
