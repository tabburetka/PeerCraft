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
public class ModSyncPreparingScreen extends GuiScreen {

    private static final int TRACK = 28;

    private final String titleText = PeerCraftLang.tr("peercraft.modsync.prepare.title");
    private volatile String status = PeerCraftLang.tr("peercraft.modsync.prepare.connecting");
    private final Runnable onCancel;

    public ModSyncPreparingScreen(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    /** {@code status} is already a resolved display string (ClientModSyncAgent calls PeerCraftLang.tr). */
    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.modsync.confirm.cancel"), onCancel)
                .bounds(this.width / 2 - 100, this.height - 44, 200, 20).build());
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

    /** A single block bouncing back and forth inside a fixed-width track — visibly alive, no percentage. */
    private String indeterminateBar() {
        int span = TRACK - 3;
        int t = (int) ((System.currentTimeMillis() / 90) % (2L * span));
        int pos = t < span ? t : (2 * span - t);
        StringBuilder sb = new StringBuilder(TRACK);
        for (int i = 0; i < TRACK; i++) {
            sb.append(i >= pos && i < pos + 3 ? '█' : '░');
        }
        return sb.toString();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        int cx = this.width / 2;
        this.drawCenteredString(this.fontRendererObj, this.titleText, cx, this.height / 2 - 30, 0xFFFFFFFF);
        this.drawCenteredString(this.fontRendererObj, this.status, cx, this.height / 2 - 6, 0xFFAAAAAA);
        this.drawCenteredString(this.fontRendererObj, indeterminateBar(), cx, this.height / 2 + 14, 0xFFFFD966);
        this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.modsync.confirm.trust_reminder"),
                cx, this.height / 2 + 40, 0xFFFF5555);
    }
}
