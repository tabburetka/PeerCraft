package net.peercraft.client.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(net.minecraft.client.gui.Gui.class)
public final class JoinRequestsHudMixin {
    @Inject(method="render",at=@At("TAIL"))
    private void peercraft$requests(com.mojang.blaze3d.vertex.PoseStack g,float delta,CallbackInfo ci){
        net.minecraft.client.Minecraft mc=net.minecraft.client.Minecraft.getInstance();
        net.peercraft.network.admission.AdmissionClient client=net.peercraft.network.admission.AdmissionClient.INSTANCE;
        if(mc.player==null || System.currentTimeMillis()>client.toastUntil || client.noticeKey.isEmpty())return;
        int right=mc.getWindow().getGuiScaledWidth()-8,left=Math.max(8,right-250);
        net.minecraft.client.gui.GuiComponent.fill(g,left,8,right,52,net.peercraft.client.theme.SteampunkPalette.PANEL);
        int i=0;for(String line:net.peercraft.client.gui.PeerCraftUi.wrap(mc.font,new net.minecraft.network.chat.TranslatableComponent(client.noticeKey).getString(),right-left-16)){
            if(i>=2)break;mc.font.draw(g,line,left+8,17+i++*12,net.peercraft.client.theme.SteampunkPalette.ACCENT);
        }
    }
}
