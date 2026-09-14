package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.gui.GuiYesNoCallback;

import java.util.List;

/**
 * Forge 1.7.10 backport of {@code src/main/.../client/gui/HandoffReclaimConfirmScreen.java}
 * (cf. the 1.12.2 twin). Delta: 1.7.10's {@code GuiScreen} does NOT implement
 * {@code GuiYesNoCallback} itself (unlike 1.12.2's) — declared explicitly here.
 */
public class HandoffReclaimConfirmScreen extends GuiScreen implements GuiYesNoCallback {

    private final String existingName;
    private final String backupName;
    private final Runnable onUpdate;
    private final Runnable onOverwrite;
    private boolean chosen;

    public HandoffReclaimConfirmScreen(String existingName, String backupName, Runnable onUpdate, Runnable onOverwrite) {
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
        this.buttonList.clear();
        int cx = this.width / 2;
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.handoff.reclaim.update"),
                        () -> choose(onUpdate))
                .bounds(cx - 155, this.height - 44, 150, 20).build());
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.handoff.reclaim.overwrite"),
                        this::confirmOverwrite)
                .bounds(cx + 5, this.height - 44, 150, 20).build());
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    private void confirmOverwrite() {
        PeerCraftUi.setScreen(this.mc, new GuiYesNo(this,
                PeerCraftLang.tr("peercraft.handoff.reclaim.overwrite_confirm.title"),
                PeerCraftLang.tr("peercraft.handoff.reclaim.overwrite_confirm.body", existingName),
                PeerCraftLang.tr("peercraft.handoff.reclaim.overwrite_confirm.yes"),
                PeerCraftLang.tr("peercraft.handoff.reclaim.overwrite_confirm.no"), 0));
    }

    @Override
    public void confirmClicked(boolean result, int id) {
        if (result) {
            choose(onOverwrite);
        } else {
            PeerCraftUi.setScreen(this.mc, this);
        }
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
                Math.min(this.width - 60, 380));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        int cx = this.width / 2;
        int y = this.height / 2 - 40;
        this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.handoff.reclaim.title"), cx, y, 0xFFFFFFFF);
        y += 22;
        for (String line : bodyLines()) {
            this.drawCenteredString(this.fontRendererObj, line, cx, y, 0xFFCCCCCC);
            y += 12;
        }
    }
}
