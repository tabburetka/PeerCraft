package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.List;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../client/gui/HandoffStatusScreen.java}. The
 * "back to title" teardown has no {@code Minecraft.disconnect(Screen)} on 1.16.5 — vanilla's own
 * "Save and Quit to Title" (decompiled from {@code PauseScreen}) does
 * {@code level.disconnect()} then {@code Minecraft.clearLevel(Screen)} with a
 * {@code GenericDirtMessageScreen("menu.savingLevel")}, then sets the title screen. Mirrored here.
 */
public class HandoffStatusScreen extends PeerCraftDialogScreen {

    private final Screen backScreen;
    private final String successorName;

    private volatile String statusKey = "peercraft.handoff.status.offering";
    private volatile String terminalKey;
    private volatile boolean success;
    private volatile long sentBytes;
    private volatile long totalBytes;

    public HandoffStatusScreen(Screen backScreen, String successorName) {
        super(new TranslatableComponent("peercraft.handoff.picker.title"), 300);
        this.backScreen = backScreen;
        this.successorName = successorName;
    }

    @Override
    protected void init() {
        super.init();
        int desiredHeight = dialog.headerHeight + 6 + Math.max(3, lines().size()) * 12
                + 22 + (terminalKey == null ? 0 : dialog.buttonHeight());
        dialog = new SteampunkDialog(width, height, desiredHeight, title);
        this.buttons.clear();
        this.children.clear();
        int cx = this.width / 2;
        if (terminalKey == null) {
            return;
        }
        if (success) {
            dialogAction(new TranslatableComponent("menu.returnToMenu"), b -> toTitle(), false, 0, 1);
        } else {
            dialogAction(new TranslatableComponent("peercraft.modsync.restart.back"),
                            b -> PeerCraftUi.setScreen(this.minecraft, backScreen), false, 0, 1);
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
        if (this.minecraft != null) {
            this.minecraft.execute(r);
        } else {
            r.run();
        }
    }

    private void rebuild() {
        if (this.minecraft == null || this.font == null) return;
        this.init();
    }

    private void toTitle() {
        this.minecraft.level.disconnect();
        this.minecraft.clearLevel(new GenericDirtMessageScreen(new TranslatableComponent("menu.savingLevel")));
        PeerCraftUi.setScreen(this.minecraft, new TitleScreen());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return terminalKey != null && !success;
    }

    @Override
    public void onClose() {
        if (terminalKey != null && !success) {
            PeerCraftUi.setScreen(this.minecraft, backScreen);
        }
    }

    private List<String> lines() {
        String key = terminalKey != null ? terminalKey : statusKey;
        String text = new TranslatableComponent(key, successorName).getString();
        List<String> out = PeerCraftUi.wrap(this.font, text, dialog.contentWidth() - 10);
        long total = this.totalBytes;
        if (terminalKey == null && total > 0) {
            long sent = Math.min(this.sentBytes, total);
            int pct = (int) Math.round(100.0 * sent / total);
            out.add(PeerCraftUi.humanSize(sent) + " / " + PeerCraftUi.humanSize(total) + "  (" + pct + "%)");
        }
        return out;
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        drawBody(poseStack, lines(), terminalKey == null ? PeerCraftUi.TEXT_TITLE : (success ? PeerCraftUi.TEXT_SUCCESS : PeerCraftUi.TEXT_ERROR));
        super.render(poseStack, mouseX, mouseY, partialTick);
    }
}
