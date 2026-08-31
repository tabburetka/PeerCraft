package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Shown after mod sync has installed the missing jars. Minecraft can't load them without a
 * relaunch, so the join is deliberately NOT continued — the player quits (or goes to the
 * title screen) and reconnects next launch, when the mods are present and the join goes
 * straight through.
 */
public class ModSyncRestartRequiredScreen extends Screen {

    private static final int MAX_LISTED = 12;

    private final List<String> installed;

    public ModSyncRestartRequiredScreen(List<String> installed) {
        super(Component.translatable("peercraft.modsync.restart.title"));
        this.installed = List.copyOf(installed);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height - 52;
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.modsync.restart.quit"), b -> this.minecraft.stop())
                .bounds(cx - 154, y, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.modsync.restart.back"), b -> toTitle())
                .bounds(cx + 4, y, 150, 20).build());
    }

    private void toTitle() {
        //? if <26.2
        this.minecraft.setScreen(new TitleScreen());
        //? if >=26.2
        /*this.minecraft.gui.setScreen(new TitleScreen());*/
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = 40;
        graphics.drawCenteredString(this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 18;
        graphics.drawCenteredString(this.font, Component.translatable("peercraft.modsync.restart.body", installed.size()), cx, y, 0xFF55FF55);
        y += 20;
        int shown = Math.min(MAX_LISTED, installed.size());
        for (int i = 0; i < shown; i++) {
            graphics.drawCenteredString(this.font, installed.get(i), cx, y, 0xFFFFFFFF);
            y += 12;
        }
        if (installed.size() > shown) {
            graphics.drawCenteredString(this.font, "… +" + (installed.size() - shown), cx, y, 0xFFAAAAAA);
        }
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = 40;
        graphics.centeredText(this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 18;
        graphics.centeredText(this.font, Component.translatable("peercraft.modsync.restart.body", installed.size()), cx, y, 0xFF55FF55);
        y += 20;
        int shown = Math.min(MAX_LISTED, installed.size());
        for (int i = 0; i < shown; i++) {
            graphics.centeredText(this.font, installed.get(i), cx, y, 0xFFFFFFFF);
            y += 12;
        }
        if (installed.size() > shown) {
            graphics.centeredText(this.font, "… +" + (installed.size() - shown), cx, y, 0xFFAAAAAA);
        }
    }*/
    //?}
}
