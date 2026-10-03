package net.peercraft.client.gui;

// Forge 1.7.10 backport of src/main/.../client/gui/ModSyncProgressScreen.java (twin of the
// src/client-1122 backport). Screen -> GuiScreen; Component -> String; clearWidgets()+init() ->
// buttonList.clear()+initGui(); minecraft.setScreen(new TitleScreen()) ->
// mc.displayGuiScreen(new GuiMainMenu()); shouldCloseOnEsc()/onClose() -> ESC handling in
// keyTyped; switch expression -> colon switch. 1.7.10 deltas vs the 1.12.2 twin:
// this.fontRenderer -> this.fontRendererObj; addButton() -> this.buttonList.add(...); no
// `throws IOException`. Text bars are unchanged. Keep in sync.

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.peercraft.network.modsync.ModSyncPlan;
import org.lwjgl.input.Keyboard;


/**
 * Per-jar and overall download/verify/install progress, drawn with brass-framed progress bars. A Cancel button
 * aborts the whole batch and returns to the previous screen; a hard failure swaps to an error
 * message.
 */
public class ModSyncProgressScreen extends PeerCraftDialogScreen {

    public enum State {DOWNLOADING, VERIFYING, INSTALLING}


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
        super(PeerCraftLang.tr("peercraft.modsync.progress.title"), 340, 400);
        this.plan = plan;
        this.onCancel = onCancel;
    }

    @Override
    public void initGui() {
        super.initGui();
        int desiredHeight = dialog.headerHeight + 6 + 8 * 12 + 22 + dialog.buttonHeight();
        dialog = new SteampunkDialog(width, height, desiredHeight, PeerCraftLang.tr("peercraft.modsync.progress.title"), 400);
        this.buttonList.clear();
        String label = errorKey != null ? PeerCraftLang.tr("peercraft.modsync.restart.back") : PeerCraftLang.tr("peercraft.modsync.confirm.cancel");
        dialogAction(label, errorKey != null ? this::toTitle : onCancel, false, 0, 1);
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
        return Math.round(Math.max(0, Math.min(1, frac)) * 100) + "%";
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
        java.util.List<String> paragraphs = new java.util.ArrayList<>();
        paragraphs.add(currentLine());
        if (errorKey == null) {
            paragraphs.add(fileBar()); paragraphs.add(""); paragraphs.add("");
            paragraphs.add(overallCount()); paragraphs.add(overallBar()); paragraphs.add("");
        }
        drawBody(paragraphs, errorKey != null ? PeerCraftUi.TEXT_ERROR : PeerCraftUi.TEXT_TITLE);
        if (errorKey == null) {
            int fileRow = PeerCraftUi.wrap(this.fontRendererObj, currentLine(), dialog.contentWidth() - 8).size()
                    + PeerCraftUi.wrap(this.fontRendererObj, fileBar(), dialog.contentWidth() - 8).size();
            int totalRow = fileRow + 2 + PeerCraftUi.wrap(this.fontRendererObj, overallCount(), dialog.contentWidth() - 8).size()
                    + PeerCraftUi.wrap(this.fontRendererObj, overallBar(), dialog.contentWidth() - 8).size();
            if (dialog.contentTop() + (totalRow + 1) * 12 <= bodyBottom()) {
                drawProgressBar(dialog.contentTop() + fileRow * 12 + 2, fileReceived, fileTotal);
                drawProgressBar(dialog.contentTop() + totalRow * 12 + 2, overallDone, plan.totalBytes());
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
    private void drawProgressBar(int y, long done, long total) {
        int x = dialog.contentX(), w = dialog.contentWidth();
        double fraction = total > 0 ? Math.max(0, Math.min(1, (double) done / total)) : 0;
        SteampunkDialog.frame(x, y, w, 6, net.peercraft.client.theme.SteampunkPalette.CONTROL,
                net.peercraft.client.theme.SteampunkPalette.BORDER);
        int filled = (int) Math.round(Math.max(0, w - 2) * fraction);
        if (filled > 0) drawRect(x + 1, y + 1, x + 1 + filled, y + 5, net.peercraft.client.theme.SteampunkPalette.PRIMARY);
    }

}
