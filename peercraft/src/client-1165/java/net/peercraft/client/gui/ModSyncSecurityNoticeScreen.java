package net.peercraft.client.gui;

// Minecraft 1.16.5 Fabric backport of src/main/.../client/gui/ModSyncSecurityNoticeScreen.java.
// Deltas: render(GuiGraphics) -> render(PoseStack); graphics.drawCenteredString ->
// GuiComponent.drawCenteredString(poseStack, ...); Button.builder -> Btn.builder;
// addRenderableWidget -> addButton; Component.translatable -> new TranslatableComponent.
// Keep in sync with the original.

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;

/**
 * Shown once, the first time this player would run mod sync, before anything is fetched:
 * mod sync installs jars chosen by the host, and a jar is arbitrary code. The player either
 * acknowledges the risk (a flag in {@code settings.json} is set and this screen never returns)
 * or cancels the join. A short red reminder stays on the preparing/confirm screens after that.
 */
public class ModSyncSecurityNoticeScreen extends Screen {

    private final Runnable onAccept;
    private final Runnable onCancel;

    public ModSyncSecurityNoticeScreen(Runnable onAccept, Runnable onCancel) {
        super(new TranslatableComponent("peercraft.modsync.notice.title"));
        this.onAccept = onAccept;
        this.onCancel = onCancel;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height - 52;
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.modsync.notice.accept"), (Button.OnPress) b -> onAccept.run())
                .bounds(cx - 204, y, 200, 20).build());
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.modsync.notice.decline"), (Button.OnPress) b -> onCancel.run())
                .bounds(cx + 4, y, 200, 20).build());
    }

    @Override
    public void onClose() {
        onCancel.run();
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = 48;
        GuiComponent.drawCenteredString(poseStack, this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 24;
        GuiComponent.drawCenteredString(poseStack, this.font, new TranslatableComponent("peercraft.modsync.notice.body1"), cx, y, 0xFFCCCCCC);
        y += 16;
        GuiComponent.drawCenteredString(poseStack, this.font, new TranslatableComponent("peercraft.modsync.notice.body2"), cx, y, 0xFFFF5555);
        y += 16;
        GuiComponent.drawCenteredString(poseStack, this.font, new TranslatableComponent("peercraft.modsync.notice.body3"), cx, y, 0xFFCCCCCC);
        y += 16;
        GuiComponent.drawCenteredString(poseStack, this.font, new TranslatableComponent("peercraft.modsync.notice.body4"), cx, y, 0xFFAAAAAA);
    }
}
