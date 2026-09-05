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
public class ModSyncPreparingScreen extends Screen {

    private static final int TRACK = 28;

    private volatile Component status = new TranslatableComponent("peercraft.modsync.prepare.connecting");
    private final Runnable onCancel;

    public ModSyncPreparingScreen(Runnable onCancel) {
        super(new TranslatableComponent("peercraft.modsync.prepare.title"));
        this.onCancel = onCancel;
    }

    public void setStatus(Component status) {
        this.status = status;
    }

    @Override
    protected void init() {
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.modsync.confirm.cancel"), (Button.OnPress) b -> onCancel.run())
                .bounds(this.width / 2 - 100, this.height - 44, 200, 20).build());
    }

    @Override
    public void onClose() {
        onCancel.run();
    }

    /** A single block bouncing back and forth inside a fixed-width track — visibly alive, no percentage. */
    private String indeterminateBar() {
        int span = TRACK - 3;
        int t = (int) ((System.currentTimeMillis() / 90) % (2L * span));
        int pos = t < span ? t : (2 * span - t);
        StringBuilder sb = new StringBuilder(TRACK);
        for (int i = 0; i < TRACK; i++) {
            sb.append(i >= pos && i < pos + 3 ? '█' : '░');
        }
        return sb.toString();
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        GuiComponent.drawCenteredString(poseStack, this.font, this.title, cx, this.height / 2 - 30, 0xFFFFFFFF);
        GuiComponent.drawCenteredString(poseStack, this.font, this.status, cx, this.height / 2 - 6, 0xFFAAAAAA);
        GuiComponent.drawCenteredString(poseStack, this.font, indeterminateBar(), cx, this.height / 2 + 14, 0xFFFFD966);
    }
}
