package net.peercraft.client.gui;

// Minecraft 1.16.5 Fabric backport of src/main/.../client/gui/ModSyncPreparingScreen.java.
// Deltas: render(GuiGraphics) -> render(PoseStack); graphics.drawCenteredString ->
// GuiComponent.drawCenteredString(poseStack, ...); Button.builder -> Btn.builder;
// addRenderableWidget -> addButton; Component.translatable -> new TranslatableComponent.
// Keep in sync with the original.

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;

/**
 * Shown the instant the mod-sync handshake starts, so the gap between clicking Connect and
 * the confirm screen (receiving the host's mod list, then resolving download sources for it)
 * isn't a blank, seemingly-frozen screen. Carries a status line the agent updates through the
 * phases and an indeterminate "working" bar.
 */
public class ModSyncPreparingScreen extends PeerCraftDialogScreen {


    private volatile Component status = new TranslatableComponent("peercraft.modsync.prepare.connecting");
    private final Runnable onCancel;

    public ModSyncPreparingScreen(Runnable onCancel) {
        super(new TranslatableComponent("peercraft.modsync.prepare.title"), 300);
        this.onCancel = onCancel;
    }

    public void setStatus(Component status) {
        this.status = status;
    }

    @Override
    protected void init() {
        super.init();
        int reminderLines = PeerCraftUi.wrap(font, new TranslatableComponent("peercraft.modsync.confirm.trust_reminder").getString(), dialog.contentWidth()).size();
        dialog = new SteampunkDialog(width, height,
                dialog.headerHeight + 6 + 38 + 12 + reminderLines * 12 + 18 + dialog.buttonPitch() + 10,
                title);

        dialogAction(new TranslatableComponent("peercraft.modsync.confirm.cancel"), (Button.OnPress) b -> onCancel.run(), false, 0, 1);
    }

    @Override
    public void onClose() {
        onCancel.run();
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        int y = dialog.contentTop();
        dialog.status(poseStack, font, status, y, 26, net.peercraft.client.theme.SteampunkPalette.MUTED);
        int trackY = y + 30, trackWidth = dialog.contentWidth();
        int blockWidth = Math.max(12, trackWidth / 8);
        int span = Math.max(1, trackWidth - blockWidth - 2);
        int phase = (int) ((System.currentTimeMillis() / 12) % (2L * span));
        int blockX = dialog.contentX() + 1 + (phase < span ? phase : 2 * span - phase);
        SteampunkDialog.frame(poseStack, dialog.contentX(), trackY, trackWidth, 6,
                net.peercraft.client.theme.SteampunkPalette.CONTROL, net.peercraft.client.theme.SteampunkPalette.BORDER);
        GuiComponent.fill(poseStack, blockX, trackY + 1, blockX + blockWidth, trackY + 5, net.peercraft.client.theme.SteampunkPalette.ACCENT);
        int reminderHeight = Math.max(0, dialog.top + dialog.height - 28 - dialog.buttonPitch() - trackY - 16);
        dialog.status(poseStack, font, new TranslatableComponent("peercraft.modsync.confirm.trust_reminder"), trackY + 16, reminderHeight, 0xFFFF5555);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }
}
