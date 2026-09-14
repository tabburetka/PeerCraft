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
public class HandoffReclaimConfirmScreen extends Screen {

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
        int cx = this.width / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.handoff.reclaim.update"), b -> choose(onUpdate))
                .bounds(cx - 155, this.height - 44, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.handoff.reclaim.overwrite"), b -> confirmOverwrite())
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
        return PeerCraftUi.wrap(this.font, body, Math.min(this.width - 60, 380));
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 40;
        graphics.drawCenteredString(this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 22;
        for (String line : bodyLines()) {
            graphics.drawCenteredString(this.font, line, cx, y, 0xFFCCCCCC);
            y += 12;
        }
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 40;
        graphics.centeredText(this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 22;
        for (String line : bodyLines()) {
            graphics.centeredText(this.font, line, cx, y, 0xFFCCCCCC);
            y += 12;
        }
    }*/
    //?}
}
