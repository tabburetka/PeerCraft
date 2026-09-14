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
public class HandoffStatusScreen extends GuiScreen {

    private final GuiScreen backScreen;
    private final String successorName;

    private volatile String statusKey = "peercraft.handoff.status.offering";
    private volatile String terminalKey;
    private volatile boolean success;
    private volatile long sentBytes;
    private volatile long totalBytes;
    private boolean closeHandled;

    public HandoffStatusScreen(GuiScreen backScreen, String successorName) {
        this.backScreen = backScreen;
        this.successorName = successorName;
    }

    private <T extends GuiButton> T addButton(T button) {
        this.buttonList.add(button);
        return button;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int cx = this.width / 2;
        if (terminalKey == null) {
            return;
        }
        if (success) {
            this.addButton(IdButton.builder(PeerCraftLang.tr("menu.returnToMenu"), this::toTitle)
                    .bounds(cx - 100, this.height - 40, 200, 20).build());
        } else {
            this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.modsync.restart.back"), this::backToScreen)
                    .bounds(cx - 100, this.height - 40, 200, 20).build());
        }
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
        List<String> out = PeerCraftUi.wrap(this.fontRendererObj, text, Math.min(this.width - 60, 360));
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
        super.drawScreen(mouseX, mouseY, partialTicks);
        int cx = this.width / 2;
        int y = this.height / 2 - 24;
        this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.handoff.picker.title"), cx, y, 0xFFFFFFFF);
        y += 22;
        int color = terminalKey == null ? 0xFFCCCCCC : (success ? 0xFF55FF55 : 0xFFFF5555);
        for (String line : lines()) {
            this.drawCenteredString(this.fontRendererObj, line, cx, y, color);
            y += 12;
        }
    }
}
