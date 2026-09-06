package net.peercraft.client.gui;

// Forge 1.12.2 backport of src/main/.../client/gui/ModSyncSecurityNoticeScreen.java. Screen ->
// GuiScreen; Component -> String (PeerCraftLang); Button.builder -> IdButton.builder;
// addRenderableWidget -> addButton; render(GuiGraphics) -> drawScreen; onClose() -> ESC
// handling in keyTyped. Keep in sync with the original.

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

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
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.modsync.notice.accept"), onAccept)
                .bounds(cx - 204, y, 200, 20).build());
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.modsync.notice.decline"), onCancel)
                .bounds(cx + 4, y, 200, 20).build());
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
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
        this.drawCenteredString(this.fontRenderer, this.titleText, cx, y, 0xFFFFFFFF);
        y += 24;
        this.drawCenteredString(this.fontRenderer, PeerCraftLang.tr("peercraft.modsync.notice.body1"), cx, y, 0xFFCCCCCC);
        y += 16;
        this.drawCenteredString(this.fontRenderer, PeerCraftLang.tr("peercraft.modsync.notice.body2"), cx, y, 0xFFFF5555);
        y += 16;
        this.drawCenteredString(this.fontRenderer, PeerCraftLang.tr("peercraft.modsync.notice.body3"), cx, y, 0xFFCCCCCC);
        y += 16;
        this.drawCenteredString(this.fontRenderer, PeerCraftLang.tr("peercraft.modsync.notice.body4"), cx, y, 0xFFAAAAAA);
    }
}
