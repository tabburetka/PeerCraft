package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Shown when a handoff brings back a world this machine already has (matched by
 * {@code peercraft-world.json}'s {@code worldId}). Two ways to land it: update the local copy
 * in place, backing up the old one first (safe, default on ESC/close); or overwrite it outright
 * with no backup, gated behind its own {@link ConfirmScreen} warning since it's irreversible.
 */
public class HandoffReclaimConfirmScreen extends PeerCraftDialogScreen {

    private final String existingName;
    private final String backupName;
    private final Runnable onUpdate;
    private final Runnable onOverwrite;
    private boolean chosen;

    public HandoffReclaimConfirmScreen(String existingName, String backupName, Runnable onUpdate, Runnable onOverwrite) {
        super(Component.translatable("peercraft.handoff.reclaim.title"));
        this.existingName = existingName;
        this.backupName = backupName;
        this.onUpdate = onUpdate;
        this.onOverwrite = onOverwrite;
    }

    @Override
    protected void init() {
        super.init();
        int renderedLines = 0;
        for (String line : bodyLines()) renderedLines += Math.max(1,
                font.split(Component.literal(line), Math.max(1, dialog.contentWidth() - 8)).size());
        int desiredHeight = dialog.headerHeight + 6 + Math.max(1, renderedLines) * 12 + 22 + dialog.buttonHeight() + dialog.buttonPitch();
        dialog = new SteampunkDialog(width, height, desiredHeight, title);
        int cx = this.width / 2;
        this.addRenderableWidget(dialogAction(Component.translatable("peercraft.handoff.reclaim.update"), b -> choose(onUpdate), true, 0, 2));
        this.addRenderableWidget(dialogAction(Component.translatable("peercraft.handoff.reclaim.overwrite"), b -> confirmOverwrite(), false, 1, 2));
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
                Component.translatable("peercraft.handoff.reclaim.overwrite_confirm.title"),
                Component.translatable("peercraft.handoff.reclaim.overwrite_confirm.body", existingName),
                Component.translatable("peercraft.handoff.reclaim.overwrite_confirm.yes"),
                Component.translatable("peercraft.handoff.reclaim.overwrite_confirm.no")));
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
        // Closing without picking = safest default: back up, don't destroy anything.
        choose(onUpdate);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private List<String> bodyLines() {
        String body = Component.translatable("peercraft.handoff.reclaim.body", existingName, backupName).getString();
        return PeerCraftUi.wrap(this.font, body, Math.max(1, this.dialog.contentWidth() - 8));
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.dialog.contentTop();
        drawBody(graphics, bodyLines(), 0xFFCCCCCC);
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.dialog.contentTop();
        drawBody(graphics, bodyLines(), 0xFFCCCCCC);
    }*/
    //?}
}
