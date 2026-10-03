package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;

import java.io.IOException;
import java.util.List;

/**
 * Forge 1.12.2 backport of {@code src/main/.../client/gui/HandoffStatusScreen.java} (cf. the
 * 1.16.5 twin). The "back to title" teardown mirrors vanilla's own {@code GuiIngameMenu}
 * disconnect button exactly (decompiled: {@code world.sendQuittingDisconnectingPacket()} then
 * {@code loadWorld(null)}, no separate "saving level" screen on 1.12.2). {@code closeHandled}
 * guards {@link #onGuiClosed()} against firing twice — see {@code HandoffOfferScreen}'s class doc.
 */
public class HandoffStatusScreen extends PeerCraftDialogScreen {

    private final GuiScreen backScreen;
    private final String successorName;

    private volatile String statusKey = "peercraft.handoff.status.offering";
    private volatile String terminalKey;
    private volatile boolean success;
    private volatile long sentBytes;
    private volatile long totalBytes;
    private boolean closeHandled;

    public HandoffStatusScreen(GuiScreen backScreen, String successorName) {
        super(PeerCraftLang.tr("peercraft.handoff.picker.title"), 320, 400);
        this.backScreen = backScreen;
        this.successorName = successorName;
    }

    @Override
    public void initGui() {
        super.initGui();
        int desiredHeight = dialog.headerHeight + 6 + Math.max(3, lines().size()) * 12
                + 22 + (terminalKey == null ? 0 : dialog.buttonHeight());
        dialog = new SteampunkDialog(width, height, desiredHeight, PeerCraftLang.tr("peercraft.handoff.picker.title"), 400);
        this.buttonList.clear();
        if (terminalKey == null) return;
        if (success) dialogAction(PeerCraftLang.tr("menu.returnToMenu"), this::toTitle, true, 0, 1);
        else dialogAction(PeerCraftLang.tr("peercraft.modsync.restart.back"), this::backToScreen, false, 0, 1);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    public void onStatus(String key) {
        run(() -> {
            this.statusKey = key;
            rebuild();
        });
    }

    public void onSendProgress(long sent, long total) {
        this.sentBytes = sent;
        this.totalBytes = total;
    }

    public void onDone(String key) {
        run(() -> {
            this.terminalKey = key;
            this.success = true;
            rebuild();
        });
    }

    public void onAborted(String key) {
        run(() -> {
            this.terminalKey = key;
            this.success = false;
            rebuild();
        });
    }

    private void run(Runnable r) {
        if (this.mc != null) {
            this.mc.addScheduledTask(r);
        } else {
            r.run();
        }
    }

    private void rebuild() {
        if (this.mc == null || this.fontRenderer == null) return;
        this.initGui();
    }

    private void toTitle() {
        closeHandled = true;
        if (this.mc.world != null) {
            this.mc.world.sendQuittingDisconnectingPacket();
            this.mc.loadWorld((WorldClient) null);
        }
        PeerCraftUi.setScreen(this.mc, new GuiMainMenu());
    }

    private void backToScreen() {
        closeHandled = true;
        PeerCraftUi.setScreen(this.mc, backScreen);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1 && !(terminalKey != null && !success)) {
            return; // ESC blocked while in progress or on the success screen (must click Return to Menu)
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void onGuiClosed() {
        if (closeHandled) {
            return;
        }
        closeHandled = true;
        if (terminalKey != null && !success) {
            PeerCraftUi.setScreen(this.mc, backScreen);
        }
    }

    private List<String> lines() {
        String key = terminalKey != null ? terminalKey : statusKey;
        String text = PeerCraftLang.tr(key, successorName);
        List<String> out = PeerCraftUi.wrap(this.fontRenderer, text, dialog.contentWidth() - 8);
        long total = this.totalBytes;
        if (terminalKey == null && total > 0) {
            long sent = Math.min(this.sentBytes, total);
            int pct = (int) Math.round(100.0 * sent / total);
            out.add(PeerCraftUi.humanSize(sent) + " / " + PeerCraftUi.humanSize(total) + "  (" + pct + "%)");
        }
        return out;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        drawBody(lines(), terminalKey == null ? PeerCraftUi.TEXT_TITLE : success ? PeerCraftUi.TEXT_SUCCESS : PeerCraftUi.TEXT_ERROR);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
