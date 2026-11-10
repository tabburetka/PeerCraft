package net.peercraft.client.mixin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ServerSelectionList;
import net.peercraft.client.gui.PeerCraftMultiplayerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerSelectionList.class)
public abstract class PeerCraftLegacyServerListMixin {
    @Inject(method = "getListWidth", at = @At("HEAD"), cancellable = true)
    private void peercraft$width(CallbackInfoReturnable<Integer> cir) {
        if (Minecraft.getMinecraft().currentScreen instanceof PeerCraftMultiplayerScreen) {
            PeerCraftMultiplayerScreen screen = (PeerCraftMultiplayerScreen) Minecraft.getMinecraft().currentScreen;
            if (screen.ownsServerList(this)) cir.setReturnValue(screen.favoritesListWidth());
        }
    }
    @Inject(method = "getScrollBarX", at = @At("HEAD"), cancellable = true)
    private void peercraft$scrollbar(CallbackInfoReturnable<Integer> cir) {
        if (Minecraft.getMinecraft().currentScreen instanceof PeerCraftMultiplayerScreen) {
            PeerCraftMultiplayerScreen screen = (PeerCraftMultiplayerScreen) Minecraft.getMinecraft().currentScreen;
            if (screen.ownsServerList(this)) cir.setReturnValue(screen.favoritesScrollbarX());
        }
    }
}
