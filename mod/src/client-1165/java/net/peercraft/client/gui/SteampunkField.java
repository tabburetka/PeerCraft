package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.peercraft.client.theme.SteampunkPalette;

/** Keeps vanilla editing, selection and narration while painting the PeerCraft field. */
final class SteampunkField extends EditBox {
    SteampunkField(Font font, int x, int y, int width, int height, Component label) {
        super(font, x + 5, y, Math.max(1, width - 10), height, label);
        setBordered(false);
        setTextColor(SteampunkPalette.TEXT);
        setTextColorUneditable(SteampunkPalette.MUTED);
    }
    @Override public void renderButton(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        SteampunkDialog.frame(pose, x - 5, y, width + 10, height, SteampunkPalette.CONTROL,
                isFocused() ? SteampunkPalette.ACCENT : SteampunkPalette.BORDER);
        // Borderless vanilla EditBox draws its text directly at y rather than centering it.
        // Shift the vanilla renderer (including cursor/selection) without moving the hitbox.
        int frameY = y;
        y += Math.max(0, (height - 8) / 2);
        try {
            super.renderButton(pose, mouseX, mouseY, partialTick);
        } finally {
            y = frameY;
        }
    }
}
