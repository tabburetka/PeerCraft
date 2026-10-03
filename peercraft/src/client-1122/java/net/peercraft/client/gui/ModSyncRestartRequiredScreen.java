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
public class ModSyncRestartRequiredScreen extends PeerCraftDialogScreen {


    private final String titleText = PeerCraftLang.tr("peercraft.modsync.restart.title");
    private final List<String> installed;

    public ModSyncRestartRequiredScreen(List<String> installed) {
        super(PeerCraftLang.tr("peercraft.modsync.restart.title"), 340, 400);
        this.installed = Collections.unmodifiableList(new ArrayList<>(installed));
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();
        dialogAction(PeerCraftLang.tr("peercraft.modsync.restart.quit"), () -> this.mc.shutdown(), true, 0, 2);
        dialogAction(PeerCraftLang.tr("peercraft.modsync.restart.back"), this::toTitle, false, 1, 2);
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
        java.util.List<String> paragraphs = new java.util.ArrayList<>();
        paragraphs.add(PeerCraftLang.tr("peercraft.modsync.restart.body", installed.size()));
        paragraphs.add(""); paragraphs.addAll(installed);
        drawBody(paragraphs, PeerCraftUi.TEXT_TITLE);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
