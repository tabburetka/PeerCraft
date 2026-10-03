package net.peercraft.client.gui;

// Forge 1.7.10 backport of src/main/.../client/gui/ModSyncSecurityNoticeScreen.java (twin of
// the src/client-1122 backport). Screen -> GuiScreen; Component -> String (PeerCraftLang);
// Button.builder -> IdButton.builder; addRenderableWidget -> this.buttonList.add(...);
// render(GuiGraphics) -> drawScreen; onClose() -> ESC handling in keyTyped. 1.7.10 deltas vs
// the 1.12.2 twin: this.fontRenderer -> this.fontRendererObj; GuiScreen.addButton() does not
// exist pre-1.12 -> this.buttonList.add(...); actionPerformed/keyTyped have no `throws
// IOException`. Keep in sync with the original.

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

/**
 * Shown once, the first time this player would run mod sync, before anything is fetched:
 * mod sync installs jars chosen by the host, and a jar is arbitrary code. The player either
 * acknowledges the risk (a flag in {@code settings.json} is set and this screen never returns)
 * or cancels the join. A short red reminder stays on the preparing/confirm screens after that.
 */
public class ModSyncSecurityNoticeScreen extends PeerCraftDialogScreen {

    private final String titleText = PeerCraftLang.tr("peercraft.modsync.notice.title");
    private final Runnable onAccept;
    private final Runnable onCancel;

    public ModSyncSecurityNoticeScreen(Runnable onAccept, Runnable onCancel) {
        super(PeerCraftLang.tr("peercraft.modsync.notice.title"), 340, 400);
        this.onAccept = onAccept;
        this.onCancel = onCancel;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();
        int lines = 0;
        for (int i = 1; i <= 4; i++) {
            lines += PeerCraftUi.wrap(this.fontRendererObj, PeerCraftLang.tr("peercraft.modsync.notice.body" + i), Math.max(1, dialog.contentWidth() - 8)).size();
        }
        dialog = new SteampunkDialog(width, height,
                dialog.headerHeight + 6 + (lines + 3) * 12 + 8 + 2 * dialog.buttonPitch() + 12,
                titleText, 400);

        dialogAction(PeerCraftLang.tr("peercraft.modsync.notice.accept"), onAccept, true, 0, 2);
        dialogAction(PeerCraftLang.tr("peercraft.modsync.notice.decline"), onCancel, false, 1, 2);
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
        java.util.List<String> paragraphs = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            if (i > 1) paragraphs.add("");
            paragraphs.add(PeerCraftLang.tr("peercraft.modsync.notice.body" + i));
        }
        drawBody(paragraphs, PeerCraftUi.TEXT_TITLE);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
