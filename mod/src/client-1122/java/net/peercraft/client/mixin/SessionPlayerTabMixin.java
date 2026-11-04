package net.peercraft.client.mixin;

import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.peercraft.client.gui.PeerCraftLang;
import net.peercraft.config.PeerCraftConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiPlayerTabOverlay.class)
public abstract class SessionPlayerTabMixin {
    @ModifyConstant(method = "renderPlayerlist", constant = @Constant(intValue = 13), require = 1)
    private int peercraft$pingWidth(int vanilla) {
        return PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode()) ? vanilla : 60;
    }
    @Inject(method = "drawPing", at = @At("HEAD"), cancellable = true)
    private void peercraft$numericPing(int columnWidth, int x, int y, NetworkPlayerInfo player, CallbackInfo ci) {
        if (PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())) return;
        int ping = player.getResponseTime();
        String label = ping < 0 ? "—" : PeerCraftLang.tr("peercraft.session.ping", ping > 9999 ? "9999+" : Integer.toString(ping));
        int color = ping < 0 ? 0xFFA99D89 : ping < 150 ? 0xFF98C69B : 0xFFE8AF50;
        net.minecraft.client.gui.FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        font.drawStringWithShadow(label, x + columnWidth - font.getStringWidth(label) - 2, y, color);
        ci.cancel();
    }
}
