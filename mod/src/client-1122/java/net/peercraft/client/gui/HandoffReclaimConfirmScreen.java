package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.io.IOException;
import java.util.List;

/**
 * Forge 1.12.2 backport of {@code src/main/.../client/gui/HandoffReclaimConfirmScreen.java}
 * (cf. the 1.16.5 twin). 1.12.2's confirm dialog is {@code GuiYesNo} (implements
 * {@code GuiYesNoCallback}, which every {@code GuiScreen} already does) — this screen
 * implements {@code confirmClicked(boolean, int)} directly instead of a lambda callback.
 */
public class HandoffReclaimConfirmScreen extends PeerCraftDialogScreen {

    private final String existingName;
    private final String backupName;
    private final Runnable onUpdate;
    private final Runnable onOverwrite;
    private boolean chosen;

    public HandoffReclaimConfirmScreen(String existingName, String backupName, Runnable onUpdate, Runnable onOverwrite) {
        super(PeerCraftLang.tr("peercraft.handoff.reclaim.title"), 320, 400);
        this.existingName = existingName;
        this.backupName = backupName;
        this.onUpdate = onUpdate;
        this.onOverwrite = onOverwrite;
    }

    @Override
    public void initGui() {
        super.initGui();
        int desiredHeight = dialog.headerHeight + 6 + Math.max(1, bodyLines().size()) * 12
                + 22 + dialog.buttonHeight() + dialog.buttonPitch();
        dialog = new SteampunkDialog(width, height, desiredHeight, PeerCraftLang.tr("peercraft.handoff.reclaim.title"), 400);
        this.buttonList.clear();
        dialogAction(PeerCraftLang.tr("peercraft.handoff.reclaim.update"), () -> choose(onUpdate), true, 0, 2);
        dialogAction(PeerCraftLang.tr("peercraft.handoff.reclaim.overwrite"), this::confirmOverwrite, false, 1, 2);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    private void confirmOverwrite() {
        PeerCraftUi.setScreen(this.mc, new PeerCraftConfirmScreen(result -> {
            if (result) choose(onOverwrite); else PeerCraftUi.setScreen(this.mc, this);
        }, PeerCraftLang.tr("peercraft.handoff.reclaim.overwrite_confirm.title"),
                PeerCraftLang.tr("peercraft.handoff.reclaim.overwrite_confirm.body", existingName),
                PeerCraftLang.tr("peercraft.handoff.reclaim.overwrite_confirm.yes"),
                PeerCraftLang.tr("peercraft.handoff.reclaim.overwrite_confirm.no")));
    }

    private void choose(Runnable action) {
        if (chosen) {
            return;
        }
        chosen = true;
        action.run();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            choose(onUpdate); // closing without picking = safest default: back up, don't destroy anything
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private List<String> bodyLines() {
        return PeerCraftUi.wrap(this.fontRenderer,
                PeerCraftLang.tr("peercraft.handoff.reclaim.body", existingName, backupName),
                dialog.contentWidth() - 8);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        drawBody(bodyLines(), PeerCraftUi.TEXT_TITLE);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
