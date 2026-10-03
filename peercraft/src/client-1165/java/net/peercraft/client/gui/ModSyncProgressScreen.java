package net.peercraft.client.gui;

// Minecraft 1.16.5 Fabric backport of src/main/.../client/gui/ModSyncProgressScreen.java.
// Deltas: render(GuiGraphics) -> render(PoseStack); Button.builder -> Btn.builder;
// addRenderableWidget -> addButton; this.clearWidgets() -> clear this.buttons/this.children;
// switch expression -> classic switch; Component.translatable -> new TranslatableComponent.
// Keep in sync with the original.

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
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
        super(new TranslatableComponent("peercraft.modsync.progress.title"), 300);
        this.plan = plan;
        this.onCancel = onCancel;
    }

    @Override
    protected void init() {
        super.init();
        int desiredHeight = dialog.headerHeight + 6 + 8 * 12 + 22 + dialog.buttonHeight();
        dialog = new SteampunkDialog(width, height, desiredHeight, title);
        Component label = errorKey != null
                ? new TranslatableComponent("peercraft.modsync.restart.back")
                : new TranslatableComponent("peercraft.modsync.confirm.cancel");
        Button.OnPress action = errorKey != null ? b -> toTitle() : b -> onCancel.run();
        dialogAction(label, action, false, 0, 1);
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
        this.buttons.clear();
        this.children.clear();
        this.init();
    }

    private void toTitle() {
        this.minecraft.setScreen(new TitleScreen());
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
            return new TranslatableComponent(errorKey);
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
        return new TranslatableComponent(key, currentModId.isEmpty() ? "…" : currentModId);
    }

    private String fileBar() {
        return fileTotal > 0
                ? bar((double) fileReceived / fileTotal) + "   " + humanSize(fileReceived) + " / " + humanSize(fileTotal)
                : bar(0);
    }

    private Component overallCount() {
        return new TranslatableComponent("peercraft.modsync.progress.overall", Math.min(plan.count(), completed), plan.count());
    }

    private String overallBar() {
        return bar(plan.totalBytes() > 0 ? (double) overallDone / plan.totalBytes() : 0);
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        java.util.List<String> paragraphs = new java.util.ArrayList<>();
        paragraphs.add(currentLine().getString());
        if (errorKey == null) {
            paragraphs.add(fileBar()); paragraphs.add(""); paragraphs.add("");
            paragraphs.add(overallCount().getString()); paragraphs.add(overallBar()); paragraphs.add("");
        }
        drawBody(poseStack, paragraphs, errorKey != null ? PeerCraftUi.TEXT_ERROR : PeerCraftUi.TEXT_TITLE);
        if (errorKey == null) {
            int fileRow = PeerCraftUi.wrap(font, currentLine().getString(), dialog.contentWidth() - 10).size()
                    + PeerCraftUi.wrap(font, fileBar(), dialog.contentWidth() - 10).size();
            int totalRow = fileRow + 2 + PeerCraftUi.wrap(font, overallCount().getString(), dialog.contentWidth() - 10).size()
                    + PeerCraftUi.wrap(font, overallBar(), dialog.contentWidth() - 10).size();
            if (dialog.contentTop() + (totalRow + 1) * 12 <= bodyBottom()) {
                drawProgressBar(poseStack, dialog.contentTop() + fileRow * 12 + 2, fileReceived, fileTotal);
                drawProgressBar(poseStack, dialog.contentTop() + totalRow * 12 + 2, overallDone, plan.totalBytes());
            }
        }
        super.render(poseStack, mouseX, mouseY, partialTick);
    }
    private void drawProgressBar(PoseStack poseStack, int y, long done, long total) {
        int x = dialog.contentX(), w = dialog.contentWidth();
        double fraction = total > 0 ? Math.max(0, Math.min(1, (double) done / total)) : 0;
        GuiComponent.fill(poseStack, x, y, x + w, y + 6, net.peercraft.client.theme.SteampunkPalette.BORDER);
        GuiComponent.fill(poseStack, x + 1, y + 1, x + w - 1, y + 5, net.peercraft.client.theme.SteampunkPalette.CONTROL);
        int filled = (int) Math.round(Math.max(0, w - 2) * fraction);
        if (filled > 0) GuiComponent.fill(poseStack, x + 1, y + 1, x + 1 + filled, y + 5, net.peercraft.client.theme.SteampunkPalette.PRIMARY);
    }
}
