package net.peercraft.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import net.peercraft.client.gui.PeerCraftMultiplayerScreen;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.nio.IntBuffer;

/** Removes only the PeerCraft Favorites list's vanilla background and edge fades. */
@Mixin(GuiSlot.class)
public abstract class PeerCraftLegacyListThemeMixin {
    @Unique private boolean peercraft$clipped;
    @Unique private boolean peercraft$previousScissor;
    // LWJGL 2 requires room for 16 values even though GL_SCISSOR_BOX returns four.
    @Unique private final IntBuffer peercraft$previousBox = BufferUtils.createIntBuffer(16);
    @Unique private PeerCraftMultiplayerScreen peercraft$owner() {
        if (!(Minecraft.getMinecraft().currentScreen instanceof PeerCraftMultiplayerScreen)) return null;
        PeerCraftMultiplayerScreen screen = (PeerCraftMultiplayerScreen) Minecraft.getMinecraft().currentScreen;
        return screen.ownsServerList(this) ? screen : null;
    }
    @Inject(method = "drawContainerBackground", at = @At("HEAD"), cancellable = true, remap = false)
    private void peercraft$container(Tessellator tessellator, CallbackInfo ci) {
        if (peercraft$owner() != null) ci.cancel();
    }
    @Inject(method = "overlayBackground", at = @At("HEAD"), cancellable = true)
    private void peercraft$overlay(int start, int end, int startAlpha, int endAlpha, CallbackInfo ci) {
        if (peercraft$owner() != null) ci.cancel();
    }
    @Inject(method = "drawScreen", at = @At("HEAD"))
    private void peercraft$clip(int x, int y, float partialTicks, CallbackInfo ci) {
        PeerCraftMultiplayerScreen owner = peercraft$owner();
        if (owner == null) return;
        GuiSlot list = (GuiSlot) (Object) this;
        Minecraft mc = Minecraft.getMinecraft();
        int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        peercraft$previousScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        peercraft$previousBox.clear();
        GL11.glGetInteger(GL11.GL_SCISSOR_BOX, peercraft$previousBox);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        int left = owner.favoritesLeft() * scale, bottom = mc.displayHeight - list.bottom * scale;
        int right = owner.favoritesRight() * scale, top = mc.displayHeight - list.top * scale;
        if (peercraft$previousScissor) {
            left = Math.max(left, peercraft$previousBox.get(0));
            bottom = Math.max(bottom, peercraft$previousBox.get(1));
            right = Math.min(right, peercraft$previousBox.get(0) + peercraft$previousBox.get(2));
            top = Math.min(top, peercraft$previousBox.get(1) + peercraft$previousBox.get(3));
        }
        GL11.glScissor(left, bottom, Math.max(0, right - left), Math.max(0, top - bottom));
        peercraft$clipped = true;
    }
    @Inject(method = "drawScreen", at = @At("RETURN"))
    private void peercraft$restore(int x, int y, float partialTicks, CallbackInfo ci) {
        if (!peercraft$clipped) return;
        PeerCraftMultiplayerScreen owner = peercraft$owner();
        GuiSlot list = (GuiSlot) (Object) this;
        int maximum = list.func_148135_f();
        int height = list.bottom - list.top;
        if (owner != null && maximum > 0 && height > 8) {
            int thumb = Math.min(height - 8, Math.max(32, height * height / (maximum + height - 4)));
            int yThumb = list.top + Math.max(0, Math.min(maximum, list.getAmountScrolled())) * (height - thumb) / maximum;
            int xThumb = owner.favoritesScrollbarX();
            net.minecraft.client.gui.Gui.drawRect(xThumb, list.top, xThumb + 6, list.bottom, 0xFF2C241B);
            net.minecraft.client.gui.Gui.drawRect(xThumb, yThumb, xThumb + 6, yThumb + thumb, 0xFFBB8B4B);
        }
        GL11.glScissor(peercraft$previousBox.get(0), peercraft$previousBox.get(1),
                peercraft$previousBox.get(2), peercraft$previousBox.get(3));
        if (!peercraft$previousScissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        peercraft$clipped = false;
    }
    @Unique private int peercraft$flushFade(Tessellator tessellator) {
        if (peercraft$owner() == null) { return tessellator.draw(); }
        // Flush the existing batch without displaying it; never leave Tessellator drawing.
        IntBuffer box = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_SCISSOR_BOX, box);
        GL11.glScissor(0, 0, 0, 0);
        try { return tessellator.draw(); } finally { GL11.glScissor(box.get(0), box.get(1), box.get(2), box.get(3)); }
    }
    @Redirect(method = "drawScreen", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()I", ordinal = 0))
    private int peercraft$topFade(Tessellator tessellator) { return peercraft$flushFade(tessellator); }
    @Redirect(method = "drawScreen", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()I", ordinal = 1))
    private int peercraft$bottomFade(Tessellator tessellator) { return peercraft$flushFade(tessellator); }
    @Redirect(method = "drawScreen", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()I", ordinal = 2))
    private int peercraft$track(Tessellator tessellator) { return peercraft$flushFade(tessellator); }
    @Redirect(method = "drawScreen", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()I", ordinal = 3))
    private int peercraft$thumb(Tessellator tessellator) { return peercraft$flushFade(tessellator); }
    @Redirect(method = "drawScreen", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()I", ordinal = 4))
    private int peercraft$highlight(Tessellator tessellator) { return peercraft$flushFade(tessellator); }
}
