package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.peercraft.network.modsync.ModSyncPlan;

/**
 * Per-jar and overall download/verify/install progress, drawn with brass-framed progress bars (works on every
 * version without touching the render-primitive API). A Cancel button aborts the whole batch
 * and returns to the previous screen; a hard failure swaps to an error message.
 */
public class ModSyncProgressScreen extends PeerCraftDialogScreen {

    public enum State {DOWNLOADING, VERIFYING, INSTALLING}


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
        super(Component.translatable("peercraft.modsync.progress.title"));
        this.plan = plan;
        this.onCancel = onCancel;
    }

    @Override
    protected void init() {
        super.init();
        int desiredHeight = dialog.headerHeight + 6 + 8 * 12 + 18 + dialog.buttonPitch();
        dialog = new SteampunkDialog(width, height, desiredHeight, title);
        Component label = errorKey != null
                ? Component.translatable("peercraft.modsync.restart.back")
                : Component.translatable("peercraft.modsync.confirm.cancel");
        Button.OnPress action = errorKey != null ? b -> toTitle() : b -> onCancel.run();
        this.addRenderableWidget(dialogAction(label, action, true, 0, 1));
    }

    @Override
    public void onClose() {
        if (errorKey == null) {
            onCancel.run();
        }
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
        this.clearWidgets();
        this.init();
    }

    private void toTitle() {
        //? if <26.2
        this.minecraft.setScreen(new TitleScreen());
        //? if >=26.2
        /*this.minecraft.gui.setScreen(new TitleScreen());*/
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return errorKey != null;
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

    private Component currentLine() {
        if (errorKey != null) {
            return Component.translatable(errorKey);
        }
        String key = switch (currentState) {
            case VERIFYING -> "peercraft.modsync.progress.verifying";
            case INSTALLING -> "peercraft.modsync.progress.installing";
            default -> "peercraft.modsync.progress.mod";
        };
        return Component.translatable(key, currentModId.isEmpty() ? "…" : currentModId);
    }

    private String fileBar() {
        return fileTotal > 0
                ? bar((double) fileReceived / fileTotal) + "   " + humanSize(fileReceived) + " / " + humanSize(fileTotal)
                : bar(0);
    }

    private Component overallCount() {
        return Component.translatable("peercraft.modsync.progress.overall", Math.min(plan.count(), completed), plan.count());
    }

    private String overallBar() {
        return bar(plan.totalBytes() > 0 ? (double) overallDone / plan.totalBytes() : 0);
    }

    private int progressRows(String text) {
        return Math.max(1, font.split(Component.literal(text), Math.max(1, dialog.contentWidth() - 8)).size());
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        java.util.List<String> paragraphs = new java.util.ArrayList<>();
        paragraphs.add(currentLine().getString());
        if (errorKey == null) {
            paragraphs.add(fileBar()); paragraphs.add(""); paragraphs.add("");
            paragraphs.add(overallCount().getString()); paragraphs.add(overallBar()); paragraphs.add("");
        }
        drawBody(graphics, paragraphs, errorKey != null ? PeerCraftUi.TEXT_ERROR : PeerCraftUi.TEXT_TITLE);
        if (errorKey == null) {
            int fileRow = progressRows(currentLine().getString())
                    + progressRows(fileBar());
            int totalRow = fileRow + 2 + progressRows(overallCount().getString())
                    + progressRows(overallBar());
            if (dialog.contentTop() + (totalRow + 1) * 12 <= dialog.top + dialog.height - 18 - dialog.buttonPitch()) {
                drawProgressBar(graphics, dialog.contentTop() + fileRow * 12 + 2, fileReceived, fileTotal);
                drawProgressBar(graphics, dialog.contentTop() + totalRow * 12 + 2, overallDone, plan.totalBytes());
            }
        }
    }
    private void drawProgressBar(GuiGraphics graphics, int y, long done, long total) {
        int x = dialog.contentX(), w = dialog.contentWidth();
        double fraction = total > 0 ? Math.max(0, Math.min(1, (double) done / total)) : 0;
        graphics.fill(x, y, x + w, y + 6, net.peercraft.client.theme.SteampunkPalette.BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + 5, net.peercraft.client.theme.SteampunkPalette.CONTROL);
        int filled = (int) Math.round(Math.max(0, w - 2) * fraction);
        if (filled > 0) graphics.fill(x + 1, y + 1, x + 1 + filled, y + 5, net.peercraft.client.theme.SteampunkPalette.PRIMARY);
    }
    //?} else {
    /*    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        java.util.List<String> paragraphs = new java.util.ArrayList<>();
        paragraphs.add(currentLine().getString());
        if (errorKey == null) {
            paragraphs.add(fileBar()); paragraphs.add(""); paragraphs.add("");
            paragraphs.add(overallCount().getString()); paragraphs.add(overallBar()); paragraphs.add("");
        }
        drawBody(graphics, paragraphs, errorKey != null ? PeerCraftUi.TEXT_ERROR : PeerCraftUi.TEXT_TITLE);
        if (errorKey == null) {
            int fileRow = progressRows(currentLine().getString())
                    + progressRows(fileBar());
            int totalRow = fileRow + 2 + progressRows(overallCount().getString())
                    + progressRows(overallBar());
            if (dialog.contentTop() + (totalRow + 1) * 12 <= dialog.top + dialog.height - 18 - dialog.buttonPitch()) {
                drawProgressBar(graphics, dialog.contentTop() + fileRow * 12 + 2, fileReceived, fileTotal);
                drawProgressBar(graphics, dialog.contentTop() + totalRow * 12 + 2, overallDone, plan.totalBytes());
            }
        }
    }
    private void drawProgressBar(GuiGraphicsExtractor graphics, int y, long done, long total) {
        int x = dialog.contentX(), w = dialog.contentWidth();
        double fraction = total > 0 ? Math.max(0, Math.min(1, (double) done / total)) : 0;
        graphics.fill(x, y, x + w, y + 6, net.peercraft.client.theme.SteampunkPalette.BORDER);
        graphics.fill(x + 1, y + 1, x + w - 1, y + 5, net.peercraft.client.theme.SteampunkPalette.CONTROL);
        int filled = (int) Math.round(Math.max(0, w - 2) * fraction);
        if (filled > 0) graphics.fill(x + 1, y + 1, x + 1 + filled, y + 5, net.peercraft.client.theme.SteampunkPalette.PRIMARY);
    }
*/
    //?}
}
