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
public class HandoffReclaimConfirmScreen extends Screen {

    private final String existingName;
    private final String backupName;
    private final Runnable onUpdate;
    private final Runnable onOverwrite;
    private boolean chosen;

    public HandoffReclaimConfirmScreen(String existingName, String backupName, Runnable onUpdate, Runnable onOverwrite) {
        super(new TranslatableComponent("peercraft.handoff.reclaim.title"));
        this.existingName = existingName;
        this.backupName = backupName;
        this.onUpdate = onUpdate;
        this.onOverwrite = onOverwrite;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.handoff.reclaim.update"), b -> choose(onUpdate))
                .bounds(cx - 155, this.height - 44, 150, 20).build());
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.handoff.reclaim.overwrite"), b -> confirmOverwrite())
                .bounds(cx + 5, this.height - 44, 150, 20).build());
    }

    private void confirmOverwrite() {
        Screen self = this;
        PeerCraftUi.setScreen(this.minecraft, new ConfirmScreen(
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
        return PeerCraftUi.wrap(this.font, body, Math.min(this.width - 60, 380));
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 40;
        GuiComponent.drawCenteredString(poseStack, this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 22;
        for (String line : bodyLines()) {
            GuiComponent.drawCenteredString(poseStack, this.font, line, cx, y, 0xFFCCCCCC);
            y += 12;
        }
    }
}
