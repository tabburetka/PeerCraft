package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.util.List;

/**
 * Forge 1.7.10 backport of {@code src/main/.../client/gui/HandoffReclaimConfirmScreen.java}
 * (cf. the 1.12.2 twin). Delta: 1.7.10's {@code GuiScreen} does NOT implement
 * {@code GuiYesNoCallback} itself (unlike 1.12.2's) — declared explicitly here.
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

    private <T extends GuiButton> T addButton(T button) {
        this.buttonList.add(button);
        return button;
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
    protected void actionPerformed(GuiButton button) {
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
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) {
            choose(onUpdate);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private List<String> bodyLines() {
        return PeerCraftUi.wrap(this.fontRendererObj,
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
