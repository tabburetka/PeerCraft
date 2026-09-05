package net.peercraft.client.gui;

// Forge 1.12.2 backport of src/main/.../client/gui/ModSyncRestartRequiredScreen.java. Screen ->
// GuiScreen; Component -> String; minecraft.stop() -> mc.shutdown(); minecraft.setScreen(new
// TitleScreen()) -> mc.displayGuiScreen(new GuiMainMenu()); shouldCloseOnEsc()==false -> ESC
// swallowed in keyTyped; List.copyOf -> unmodifiable ArrayList. Keep in sync with the original.

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shown after mod sync has installed the missing jars. Minecraft can't load them without a
 * relaunch, so the join is deliberately NOT continued.
 */
public class ModSyncRestartRequiredScreen extends GuiScreen {

    private static final int MAX_LISTED = 12;

    private final String titleText = PeerCraftLang.tr("peercraft.modsync.restart.title");
    private final List<String> installed;

    public ModSyncRestartRequiredScreen(List<String> installed) {
        this.installed = Collections.unmodifiableList(new ArrayList<>(installed));
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int cx = this.width / 2;
        int y = this.height - 52;
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.modsync.restart.quit"), () -> this.mc.shutdown())
                .bounds(cx - 154, y, 150, 20).build());
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.modsync.restart.back"), this::toTitle)
                .bounds(cx + 4, y, 150, 20).build());
    }

    private void toTitle() {
        this.mc.displayGuiScreen(new GuiMainMenu());
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
            return; // shouldCloseOnEsc() == false on the modern screen
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        int cx = this.width / 2;
        int y = 40;
        this.drawCenteredString(this.fontRenderer, this.titleText, cx, y, 0xFFFFFFFF);
        y += 18;
        this.drawCenteredString(this.fontRenderer,
                PeerCraftLang.tr("peercraft.modsync.restart.body", installed.size()), cx, y, 0xFF55FF55);
        y += 20;
        int shown = Math.min(MAX_LISTED, installed.size());
        for (int i = 0; i < shown; i++) {
            this.drawCenteredString(this.fontRenderer, installed.get(i), cx, y, 0xFFFFFFFF);
            y += 12;
        }
        if (installed.size() > shown) {
            this.drawCenteredString(this.fontRenderer, "… +" + (installed.size() - shown), cx, y, 0xFFAAAAAA);
        }
    }
}
