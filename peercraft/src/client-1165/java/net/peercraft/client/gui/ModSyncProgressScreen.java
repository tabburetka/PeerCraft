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
 * Per-jar and overall download/verify/install progress, drawn as text bars (works on every
 * version without touching the render-primitive API). A Cancel button aborts the whole batch
 * and returns to the previous screen; a hard failure swaps to an error message.
 */
public class ModSyncProgressScreen extends Screen {

    public enum State {DOWNLOADING, VERIFYING, INSTALLING}

    private static final int BAR = 28;

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
        super(new TranslatableComponent("peercraft.modsync.progress.title"));
        this.plan = plan;
        this.onCancel = onCancel;
    }

    @Override
    protected void init() {
        Component label = errorKey != null
                ? new TranslatableComponent("peercraft.modsync.restart.back")
                : new TranslatableComponent("peercraft.modsync.confirm.cancel");
        Button.OnPress action = errorKey != null ? b -> toTitle() : b -> onCancel.run();
        this.addButton(Btn.builder(label, action)
                .bounds(this.width / 2 - 100, this.height - 40, 200, 20).build());
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
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 52;
        GuiComponent.drawCenteredString(poseStack, this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 18;
        GuiComponent.drawCenteredString(poseStack, this.font, currentLine(), cx, y, errorKey != null ? 0xFFFF5555 : 0xFFFFFFFF);
        if (errorKey == null) {
            y += 14;
            GuiComponent.drawCenteredString(poseStack, this.font, fileBar(), cx, y, 0xFFAAAAAA);
            y += 22;
            GuiComponent.drawCenteredString(poseStack, this.font, overallCount(), cx, y, 0xFFFFFFFF);
            y += 14;
            GuiComponent.drawCenteredString(poseStack, this.font, overallBar(), cx, y, 0xFFFFD966);
        }
    }
}
