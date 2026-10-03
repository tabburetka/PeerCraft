package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.List;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../client/gui/HandoffReclaimConfirmScreen.java}.
 * Same widgets; 1.16.5's {@code ConfirmScreen} keeps the same 5-arg
 * (callback, title, message, yesButton, noButton) constructor.
 */
public class HandoffReclaimConfirmScreen extends PeerCraftDialogScreen {

    private final String existingName;
    private final String backupName;
    private final Runnable onUpdate;
    private final Runnable onOverwrite;
    private boolean chosen;

    public HandoffReclaimConfirmScreen(String existingName, String backupName, Runnable onUpdate, Runnable onOverwrite) {
        super(new TranslatableComponent("peercraft.handoff.reclaim.title"), 300);
        this.existingName = existingName;
        this.backupName = backupName;
        this.onUpdate = onUpdate;
        this.onOverwrite = onOverwrite;
    }

    @Override
    protected void init() {
        super.init();
        int desiredHeight = dialog.headerHeight + 6 + Math.max(1, bodyLines().size()) * 12
                + 22 + dialog.buttonHeight() + dialog.buttonPitch();
        dialog = new SteampunkDialog(width, height, desiredHeight, title);
        int cx = this.width / 2;
        dialogAction(new TranslatableComponent("peercraft.handoff.reclaim.update"), b -> choose(onUpdate), true, 0, 2);
        dialogAction(new TranslatableComponent("peercraft.handoff.reclaim.overwrite"), b -> confirmOverwrite(), false, 1, 2);
    }

    private void confirmOverwrite() {
        Screen self = this;
        PeerCraftUi.setScreen(this.minecraft, new PeerCraftConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        choose(onOverwrite);
                    } else {
                        PeerCraftUi.setScreen(HandoffReclaimConfirmScreen.this.minecraft, self);
                    }
                },
                new TranslatableComponent("peercraft.handoff.reclaim.overwrite_confirm.title"),
                new TranslatableComponent("peercraft.handoff.reclaim.overwrite_confirm.body", existingName),
                new TranslatableComponent("peercraft.handoff.reclaim.overwrite_confirm.yes"),
                new TranslatableComponent("peercraft.handoff.reclaim.overwrite_confirm.no")));
    }

    private void choose(Runnable action) {
        if (chosen) {
            return;
        }
        chosen = true;
        action.run();
    }

    @Override
    public void onClose() {
        choose(onUpdate);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private List<String> bodyLines() {
        String body = new TranslatableComponent("peercraft.handoff.reclaim.body", existingName, backupName).getString();
        return PeerCraftUi.wrap(this.font, body, dialog.contentWidth() - 10);
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        drawBody(poseStack, bodyLines(), PeerCraftUi.TEXT_TITLE);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }
}
