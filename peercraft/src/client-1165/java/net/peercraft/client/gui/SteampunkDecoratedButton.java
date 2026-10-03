package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;

/** Preserves the original vanilla callback and selection-dependent state. */
final class SteampunkDecoratedButton extends SteampunkButton {
    private final Button original;
    SteampunkDecoratedButton(Button original, int x, int y, int width, int height) {
        super(x, y, width, height, original.getMessage(), b -> original.onPress());
        this.original = original;
    }
    @Override public void renderButton(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        setMessage(original.getMessage());
        active = original.active;
        super.renderButton(pose, mouseX, mouseY, partialTick);
    }
}
