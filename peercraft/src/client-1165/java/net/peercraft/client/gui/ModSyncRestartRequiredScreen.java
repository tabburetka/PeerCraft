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
public class ModSyncRestartRequiredScreen extends Screen {

    private static final int MAX_LISTED = 12;

    private final List<String> installed;

    public ModSyncRestartRequiredScreen(List<String> installed) {
        super(new TranslatableComponent("peercraft.modsync.restart.title"));
        this.installed = Collections.unmodifiableList(new ArrayList<>(installed));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height - 52;
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.modsync.restart.quit"), (Button.OnPress) b -> this.minecraft.stop())
                .bounds(cx - 154, y, 150, 20).build());
        this.addButton(Btn.builder(new TranslatableComponent("peercraft.modsync.restart.back"), (Button.OnPress) b -> toTitle())
                .bounds(cx + 4, y, 150, 20).build());
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
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = 40;
        GuiComponent.drawCenteredString(poseStack, this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 18;
        GuiComponent.drawCenteredString(poseStack, this.font,
                new TranslatableComponent("peercraft.modsync.restart.body", installed.size()), cx, y, 0xFF55FF55);
        y += 20;
        int shown = Math.min(MAX_LISTED, installed.size());
        for (int i = 0; i < shown; i++) {
            GuiComponent.drawCenteredString(poseStack, this.font, installed.get(i), cx, y, 0xFFFFFFFF);
            y += 12;
        }
        if (installed.size() > shown) {
            GuiComponent.drawCenteredString(poseStack, this.font, "… +" + (installed.size() - shown), cx, y, 0xFFAAAAAA);
        }
    }
}
