package net.peercraft.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
//? if <26.2
@Mixin(net.minecraft.client.gui.Gui.class)
//? if >=26.2
/*@Mixin(net.minecraft.client.gui.Hud.class)*/
public final class JoinRequestsHudMixin {
    //? if <26.1 {
    @Inject(method="render",at=@At("TAIL"))
    private void peercraft$requests(GuiGraphics g,net.minecraft.client.DeltaTracker delta,CallbackInfo ci){
        var mc=net.minecraft.client.Minecraft.getInstance();
        var client=net.peercraft.network.admission.AdmissionClient.INSTANCE;
        if(mc.player==null || System.currentTimeMillis()>client.toastUntil || client.noticeKey.isEmpty())return;
        int right=mc.getWindow().getGuiScaledWidth()-8,left=Math.max(8,right-250);
        g.fill(left,8,right,52,net.peercraft.client.theme.SteampunkPalette.PANEL);
        var lines=mc.font.split(net.minecraft.network.chat.Component.translatable(client.noticeKey),right-left-16);
        for(int i=0;i<Math.min(2,lines.size());i++)g.drawString(mc.font,lines.get(i),left+8,17+i*12,net.peercraft.client.theme.SteampunkPalette.ACCENT);
    }
    //?} else {
    /*@Inject(method="extractRenderState",at=@At("TAIL"))
    private void peercraft$requests(GuiGraphicsExtractor g,net.minecraft.client.DeltaTracker delta,CallbackInfo ci){
        var mc=net.minecraft.client.Minecraft.getInstance();
        var client=net.peercraft.network.admission.AdmissionClient.INSTANCE;
        if(mc.player==null || System.currentTimeMillis()>client.toastUntil || client.noticeKey.isEmpty())return;
        int right=mc.getWindow().getGuiScaledWidth()-8,left=Math.max(8,right-250);
        g.fill(left,8,right,52,net.peercraft.client.theme.SteampunkPalette.PANEL);
        var lines=mc.font.split(net.minecraft.network.chat.Component.translatable(client.noticeKey),right-left-16);
        for(int i=0;i<Math.min(2,lines.size());i++)g.text(mc.font,lines.get(i),left+8,17+i*12,net.peercraft.client.theme.SteampunkPalette.ACCENT);
    }*/
    //?}
}
