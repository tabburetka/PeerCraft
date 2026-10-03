package net.peercraft.client.gui;

// Forge 1.7.10 backport of src/main/.../client/gui/ModSyncPreparingScreen.java (twin of the
// src/client-1122 backport). Screen -> GuiScreen; Component -> String (resolved via
// PeerCraftLang, and setStatus takes an already-resolved String from ClientModSyncAgent);
// render(GuiGraphics) -> drawScreen; onClose() -> ESC handling in keyTyped. 1.7.10 deltas vs
// the 1.12.2 twin: this.fontRenderer -> this.fontRendererObj; GuiScreen.addButton() does not
// exist pre-1.12 -> this.buttonList.add(...); actionPerformed/keyTyped have no `throws
// IOException`. Keep in sync with the original.

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

/**
 * Shown the instant the mod-sync handshake starts, so the gap between clicking Connect and the
 * confirm screen isn't a blank, seemingly-frozen screen. Carries a status line the agent
 * updates through the phases and an indeterminate "working" bar.
 */
public class ModSyncPreparingScreen extends PeerCraftDialogScreen {


    private final String titleText = PeerCraftLang.tr("peercraft.modsync.prepare.title");
    private volatile String status = PeerCraftLang.tr("peercraft.modsync.prepare.connecting");
    private final Runnable onCancel;

    public ModSyncPreparingScreen(Runnable onCancel) {
        super(PeerCraftLang.tr("peercraft.modsync.prepare.title"), 340, 400);
        this.onCancel = onCancel;
    }

    /** {@code status} is already a resolved display string (ClientModSyncAgent calls PeerCraftLang.tr). */
    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();
        int reminderLines = PeerCraftUi.wrap(fontRendererObj, PeerCraftLang.tr("peercraft.modsync.confirm.trust_reminder"), dialog.contentWidth()).size();
        dialog = new SteampunkDialog(width, height,
                dialog.headerHeight + 6 + 38 + 12 + reminderLines * 12 + 18 + dialog.buttonPitch() + 10,
                titleText, 400);

        dialogAction(PeerCraftLang.tr("peercraft.modsync.confirm.cancel"), onCancel, false, 0, 1);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            onCancel.run();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        int y = dialog.contentTop();
        dialog.status(fontRendererObj, status, y, 26, net.peercraft.client.theme.SteampunkPalette.MUTED);
        int trackY = y + 30, trackWidth = dialog.contentWidth();
        int blockWidth = Math.max(12, trackWidth / 8);
        int span = Math.max(1, trackWidth - blockWidth - 2);
        int phase = (int) ((System.currentTimeMillis() / 12) % (2L * span));
        int blockX = dialog.contentX() + 1 + (phase < span ? phase : 2 * span - phase);
        SteampunkDialog.frame(dialog.contentX(), trackY, trackWidth, 6,
                net.peercraft.client.theme.SteampunkPalette.CONTROL, net.peercraft.client.theme.SteampunkPalette.BORDER);
        drawRect(blockX, trackY + 1, blockX + blockWidth, trackY + 5, net.peercraft.client.theme.SteampunkPalette.ACCENT);
        int reminderHeight = Math.max(0, dialog.top + dialog.height - 28 - dialog.buttonPitch() - trackY - 16);
        dialog.status(fontRendererObj, PeerCraftLang.tr("peercraft.modsync.confirm.trust_reminder"), trackY + 16, reminderHeight, 0xFFFF5555);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
