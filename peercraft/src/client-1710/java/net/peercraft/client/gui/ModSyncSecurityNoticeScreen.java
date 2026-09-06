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
public class ModSyncSecurityNoticeScreen extends GuiScreen {

    private final String titleText = PeerCraftLang.tr("peercraft.modsync.notice.title");
    private final Runnable onAccept;
    private final Runnable onCancel;

    public ModSyncSecurityNoticeScreen(Runnable onAccept, Runnable onCancel) {
        this.onAccept = onAccept;
        this.onCancel = onCancel;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int cx = this.width / 2;
        int y = this.height - 52;
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.modsync.notice.accept"), onAccept)
                .bounds(cx - 204, y, 200, 20).build());
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.modsync.notice.decline"), onCancel)
                .bounds(cx + 4, y, 200, 20).build());
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
        super.drawScreen(mouseX, mouseY, partialTicks);
        int cx = this.width / 2;
        int y = 48;
        this.drawCenteredString(this.fontRendererObj, this.titleText, cx, y, 0xFFFFFFFF);
        y += 24;
        this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.modsync.notice.body1"), cx, y, 0xFFCCCCCC);
        y += 16;
        this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.modsync.notice.body2"), cx, y, 0xFFFF5555);
        y += 16;
        this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.modsync.notice.body3"), cx, y, 0xFFCCCCCC);
        y += 16;
        this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.modsync.notice.body4"), cx, y, 0xFFAAAAAA);
    }
}
