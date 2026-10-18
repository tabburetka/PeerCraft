package net.peercraft.client.gui;

// Forge 1.7.10 backport of src/main/.../client/gui/ModSyncIssuesScreen.java (twin of
// the src/client-1122 backport). Screen -> GuiScreen; Component -> String; minecraft.stop() ->
// mc.shutdown() (VERIFY: MCP stable_12 maps func_71400_g -> shutdown); minecraft.setScreen(new
// TitleScreen()) -> mc.displayGuiScreen(new GuiMainMenu()); shouldCloseOnEsc()==false -> ESC
// swallowed in keyTyped; List.copyOf -> unmodifiable ArrayList. 1.7.10 deltas vs the 1.12.2
// twin: this.fontRenderer -> this.fontRendererObj; addButton() -> this.buttonList.add(...);
// no `throws IOException`. Keep in sync with the original.

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shown after mod sync has installed the missing jars. Minecraft can't load them without a
 * relaunch, so the join is deliberately NOT continued.
 */
public class ModSyncIssuesScreen extends PeerCraftDialogScreen {


    private final String titleText = PeerCraftLang.tr("peercraft.modsync.review.title");
    private final Runnable onAccept, onCancel;
    private final List<String> installed;

    public ModSyncIssuesScreen(List<String> installed, Runnable onAccept, Runnable onCancel) {
        super(PeerCraftLang.tr("peercraft.modsync.review.title"), 340, 400);
        this.onAccept = onAccept; this.onCancel = onCancel;
        this.installed = Collections.unmodifiableList(new ArrayList<>(installed));
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();
        dialogAction(PeerCraftLang.tr("peercraft.modsync.review.continue"), () -> onAccept.run(), true, 0, 2);
        dialogAction(PeerCraftLang.tr("peercraft.modsync.confirm.cancel"), onCancel, false, 1, 2);
    }

    private void toTitle() {
        this.mc.displayGuiScreen(new GuiMainMenu());
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
            return; // shouldCloseOnEsc() == false on the modern screen
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        java.util.List<String> paragraphs = new java.util.ArrayList<>();
        paragraphs.add(PeerCraftLang.tr("peercraft.modsync.review.body", installed.size()));
        paragraphs.add(""); paragraphs.addAll(installed);
        drawBody(paragraphs, PeerCraftUi.TEXT_TITLE);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
