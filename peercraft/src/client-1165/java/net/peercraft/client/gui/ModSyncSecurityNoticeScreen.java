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
public class ModSyncSecurityNoticeScreen extends PeerCraftDialogScreen {

    private final Runnable onAccept;
    private final Runnable onCancel;

    public ModSyncSecurityNoticeScreen(Runnable onAccept, Runnable onCancel) {
        super(new TranslatableComponent("peercraft.modsync.notice.title"), 300);
        this.onAccept = onAccept;
        this.onCancel = onCancel;
    }

    @Override
    protected void init() {
        super.init();
        int lines = 0;
        for (int i = 1; i <= 4; i++) {
            lines += PeerCraftUi.wrap(font, new TranslatableComponent("peercraft.modsync.notice.body" + i).getString(), Math.max(1, dialog.contentWidth() - 8)).size();
        }
        dialog = new SteampunkDialog(width, height,
                dialog.headerHeight + 6 + (lines + 3) * 12 + 18 + 2 * dialog.buttonPitch() + 10, title);
        dialogAction(new TranslatableComponent("peercraft.modsync.notice.accept"), (Button.OnPress) b -> onAccept.run(), true, 0, 2);
        dialogAction(new TranslatableComponent("peercraft.modsync.notice.decline"), (Button.OnPress) b -> onCancel.run(), false, 1, 2);
    }

    @Override
    public void onClose() {
        onCancel.run();
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) { if (i > 1) lines.add(""); lines.add(new TranslatableComponent("peercraft.modsync.notice.body" + i).getString()); }
        drawBody(poseStack, lines, PeerCraftUi.TEXT_TITLE);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }
}
