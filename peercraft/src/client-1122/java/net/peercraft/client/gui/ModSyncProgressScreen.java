package net.peercraft.client.gui;

// Forge 1.12.2 backport of src/main/.../client/gui/ModSyncProgressScreen.java. Screen ->
// GuiScreen; Component -> String; clearWidgets()+init() -> buttonList.clear()+initGui();
// minecraft.setScreen(new TitleScreen()) -> mc.displayGuiScreen(new GuiMainMenu());
// shouldCloseOnEsc()/onClose() -> ESC handling in keyTyped; switch expression -> colon switch.
// Text bars are unchanged (they never touched the render-primitive API). Keep in sync.

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.peercraft.network.modsync.ModSyncPlan;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/**
 * Per-jar and overall download/verify/install progress, drawn as text bars. A Cancel button
 * aborts the whole batch and returns to the previous screen; a hard failure swaps to an error
 * message.
 */
public class ModSyncProgressScreen extends GuiScreen {

    public enum State {DOWNLOADING, VERIFYING, INSTALLING}

    private static final int BAR = 28;

    private final String titleText = PeerCraftLang.tr("peercraft.modsync.progress.title");
    private final ModSyncPlan plan;
    private final Runnable onCancel;

    private volatile String currentModId = "";
    private volatile State currentState = State.DOWNLOADING;
    private volatile long fileReceived;
    private volatile long fileTotal;
    private volatile long overallDone;
    private volatile int completed;
    private volatile String errorKey;

    public ModSyncProgressScreen(ModSyncPlan plan, Runnable onCancel) {
        this.plan = plan;
        this.onCancel = onCancel;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        String label = errorKey != null
                ? PeerCraftLang.tr("peercraft.modsync.restart.back")
                : PeerCraftLang.tr("peercraft.modsync.confirm.cancel");
        Runnable action = errorKey != null ? this::toTitle : onCancel;
        this.addButton(IdButton.builder(label, action)
                .bounds(this.width / 2 - 100, this.height - 40, 200, 20).build());
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
            if (errorKey != null) {
                toTitle();
            }
            // else: shouldCloseOnEsc() == false while downloading — ignore
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    public void setCurrentMod(String modId) {
        this.currentModId = modId;
        this.currentState = State.DOWNLOADING;
        this.fileReceived = 0;
        this.fileTotal = 0;
    }

    public void setState(String modId, State state) {
        if (modId.equals(this.currentModId)) {
            this.currentState = state;
        }
    }

    public void updateProgress(String modId, long received, long total) {
        if (modId.equals(this.currentModId)) {
            this.fileReceived = received;
            this.fileTotal = total;
        }
    }

    /** Called by the agent as each mod finishes: bytes installed so far + mods done. */
    public void setOverall(long bytesDone, int modsDone) {
        this.overallDone = bytesDone;
        this.completed = modsDone;
    }

    public void showError(String key) {
        this.errorKey = key;
        this.buttonList.clear();
        this.initGui();
    }

    private void toTitle() {
        this.mc.displayGuiScreen(new GuiMainMenu());
    }

    private static String bar(double frac) {
        double f = Math.max(0, Math.min(1, frac));
        int fill = (int) Math.round(f * BAR);
        StringBuilder sb = new StringBuilder(BAR + 8).append('[');
        for (int i = 0; i < BAR; i++) {
            sb.append(i < fill ? '█' : '░');
        }
        return sb.append("] ").append((int) Math.round(f * 100)).append('%').toString();
    }

    private static String humanSize(long b) {
        if (b < 1024) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024) return String.format("%.0f KB", kb);
        return String.format("%.1f MB", kb / 1024.0);
    }

    private String currentLine() {
        if (errorKey != null) {
            return PeerCraftLang.tr(errorKey);
        }
        String key;
        switch (currentState) {
            case VERIFYING:
                key = "peercraft.modsync.progress.verifying";
                break;
            case INSTALLING:
                key = "peercraft.modsync.progress.installing";
                break;
            default:
                key = "peercraft.modsync.progress.mod";
                break;
        }
        return PeerCraftLang.tr(key, currentModId.isEmpty() ? "…" : currentModId);
    }

    private String fileBar() {
        return fileTotal > 0
                ? bar((double) fileReceived / fileTotal) + "   " + humanSize(fileReceived) + " / " + humanSize(fileTotal)
                : bar(0);
    }

    private String overallCount() {
        return PeerCraftLang.tr("peercraft.modsync.progress.overall", Math.min(plan.count(), completed), plan.count());
    }

    private String overallBar() {
        return bar(plan.totalBytes() > 0 ? (double) overallDone / plan.totalBytes() : 0);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        int cx = this.width / 2;
        int y = this.height / 2 - 52;
        this.drawCenteredString(this.fontRenderer, this.titleText, cx, y, 0xFFFFFFFF);
        y += 18;
        this.drawCenteredString(this.fontRenderer, currentLine(), cx, y, errorKey != null ? 0xFFFF5555 : 0xFFFFFFFF);
        if (errorKey == null) {
            y += 14;
            this.drawCenteredString(this.fontRenderer, fileBar(), cx, y, 0xFFAAAAAA);
            y += 22;
            this.drawCenteredString(this.fontRenderer, overallCount(), cx, y, 0xFFFFFFFF);
            y += 14;
            this.drawCenteredString(this.fontRenderer, overallBar(), cx, y, 0xFFFFD966);
        }
    }
}
