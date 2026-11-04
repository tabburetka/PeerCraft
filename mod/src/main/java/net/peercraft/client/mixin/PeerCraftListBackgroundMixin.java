package net.peercraft.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.peercraft.client.gui.PeerCraftMultiplayerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the PeerCraft panel own the background; vanilla lists elsewhere retain their chrome. */
@Mixin(AbstractSelectionList.class)
public abstract class PeerCraftListBackgroundMixin {
    //? if >=1.21.1 && <26.1 {
    @Inject(method = {"renderListBackground", "renderListSeparators"}, at = @At("HEAD"), cancellable = true)
    private void peercraft$skipVanillaChrome(net.minecraft.client.gui.GuiGraphics graphics, CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof PeerCraftMultiplayerScreen) {
            ci.cancel();
        }
    }
    //?}
    //? if >=26.1 && <26.2 {
    /*@Inject(method = {"extractListBackground", "extractListSeparators"}, at = @At("HEAD"), cancellable = true)
    private void peercraft$skipVanillaChrome(net.minecraft.client.gui.GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof PeerCraftMultiplayerScreen) ci.cancel();
    }*/
    //?}
    //? if >=26.2 {
    /*@Inject(method = {"extractListBackground", "extractListSeparators"}, at = @At("HEAD"), cancellable = true)
    private void peercraft$skipVanillaChrome(net.minecraft.client.gui.GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (Minecraft.getInstance().gui.screen() instanceof PeerCraftMultiplayerScreen) ci.cancel();
    }*/
    //?}
}
