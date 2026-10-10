package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;

import java.util.List;

/**
 * Forge 1.7.10 backport of {@code src/main/.../client/gui/HandoffStatusScreen.java} (cf. the
 * 1.12.2 twin: {@code mc.world} -&gt; {@code mc.theWorld}, mechanical otherwise). Same
 * vanilla-{@code GuiIngameMenu}-mirrored disconnect ("Save and Quit" case 1: no separate
 * saving screen on 1.7.10 either) and {@code closeHandled} guard as the 1.12.2 twin.
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

    private <T extends GuiButton> T addButton(T button) {
        this.buttonList.add(button);
        return button;
    }

    @Override
    public void initGui() {
        super.initGui();
        int desiredHeight = dialog.headerHeight + 6 + Math.max(3, lines().size()) * 12
                + 22 + (terminalKey == null ? 0 : dialog.buttonHeight());
        dialog = new SteampunkDialog(width, height, desiredHeight, PeerCraftLang.tr("peercraft.handoff.picker.title"), 400);
        this.buttonList.clear();
        if (terminalKey == null) return;
        if (success) dialogAction(net.minecraft.client.resources.I18n.format("menu.returnToMenu"), this::toTitle, true, 0, 1);
        else dialogAction(PeerCraftLang.tr("peercraft.modsync.restart.back"), this::backToScreen, false, 0, 1);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
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
            this.mc.func_152344_a(r);
        } else {
            r.run();
        }
    }

    private void rebuild() {
        if (this.mc == null || this.fontRendererObj == null) return;
        this.initGui();
    }

    private void toTitle() {
        closeHandled = true;
        if (this.mc.theWorld != null) {
            this.mc.theWorld.sendQuittingDisconnectingPacket();
            this.mc.loadWorld((WorldClient) null);
        }
        PeerCraftUi.setScreen(this.mc, new GuiMainMenu());
    }

    private void backToScreen() {
        closeHandled = true;
        PeerCraftUi.setScreen(this.mc, backScreen);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1 && !(terminalKey != null && !success)) {
            return;
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
        List<String> out = PeerCraftUi.wrap(this.fontRendererObj, text, dialog.contentWidth() - 8);
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
