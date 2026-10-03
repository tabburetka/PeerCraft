package net.peercraft.client.gui;

// Minecraft 1.16.5 Fabric backport of src/main/.../client/gui/ModSyncRestartRequiredScreen.java.
// Deltas: render(GuiGraphics) -> render(PoseStack); Button.builder -> Btn.builder;
// addRenderableWidget -> addButton; List.copyOf -> new ArrayList; this.minecraft.setScreen /
// this.minecraft.stop() are 1.16.5-native. Keep in sync with the original.

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shown after mod sync has installed the missing jars. Minecraft can't load them without a
 * relaunch, so the join is deliberately NOT continued.
 */
public class ModSyncRestartRequiredScreen extends PeerCraftDialogScreen {

    private static final int MAX_LISTED = 12;

    private final List<String> installed;

    public ModSyncRestartRequiredScreen(List<String> installed) {
        super(new TranslatableComponent("peercraft.modsync.restart.title"), 300);
        this.installed = Collections.unmodifiableList(new ArrayList<>(installed));
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int y = this.height - 52;
        dialogAction(new TranslatableComponent("peercraft.modsync.restart.quit"), (Button.OnPress) b -> this.minecraft.stop(), true, 0, 2);
        dialogAction(new TranslatableComponent("peercraft.modsync.restart.back"), (Button.OnPress) b -> toTitle(), false, 1, 2);
    }

    private void toTitle() {
        this.minecraft.setScreen(new TitleScreen());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(new TranslatableComponent("peercraft.modsync.restart.body", installed.size()).getString());
        lines.add(""); lines.addAll(installed);
        drawBody(poseStack, lines, PeerCraftUi.TEXT_TITLE);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }
}
