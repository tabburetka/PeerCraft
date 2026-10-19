package net.peercraft.client.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.config.PeerCraftConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerTabOverlay.class)
public abstract class SessionPlayerTabMixin {
    @ModifyConstant(method = "render", constant = @Constant(intValue = 13), require = 1)
    private int peercraft$pingWidth(int vanilla) {
        return PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode()) ? vanilla : 60;
    }
    @Inject(method = "renderPingIcon", at = @At("HEAD"), cancellable = true)
    private void peercraft$numericPing(PoseStack graphics, int columnWidth, int x, int y, PlayerInfo player, CallbackInfo ci) {
        if (PeerCraftConfig.MODE_DISABLED.equals(PeerCraftConfig.mode())) return;
        int ping = player.getLatency();
        String label = ping < 0 ? "—" : new TranslatableComponent("peercraft.session.ping", ping > 9999 ? "9999+" : Integer.toString(ping)).getString();
        int color = ping < 0 ? 0xFFA99D89 : ping < 150 ? 0xFF98C69B : 0xFFE8AF50;
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        font.drawShadow(graphics, label, x + columnWidth - font.width(label) - 2, y, color);
        ci.cancel();
    }
}
