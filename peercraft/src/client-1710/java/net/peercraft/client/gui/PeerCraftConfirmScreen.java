package net.peercraft.client.gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import java.util.function.Consumer;
import org.lwjgl.input.Keyboard;
class PeerCraftConfirmScreen extends PeerCraftDialogScreen {
    private final Consumer<Boolean> callback;
    private final String message, yesLabel, noLabel;
    private boolean answered;
    PeerCraftConfirmScreen(Consumer<Boolean> callback, String title, String message) {
        this(callback, title, message, net.minecraft.client.resources.I18n.format("gui.yes"), net.minecraft.client.resources.I18n.format("gui.no"));
    }
    PeerCraftConfirmScreen(Consumer<Boolean> callback, String title, String message, String yesLabel, String noLabel) {
        super(title, 300); this.callback = callback; this.message = message; this.yesLabel = yesLabel; this.noLabel = noLabel;
    }
    @Override public void initGui() {
        super.initGui(); this.buttonList.clear();
        dialogAction(yesLabel, () -> answer(true), true, 0, 2);
        dialogAction(noLabel, () -> answer(false), false, 1, 2);
    }
    private void answer(boolean result) { if (!answered) { answered = true; callback.accept(result); } }
    @Override protected void actionPerformed(GuiButton button) {
        if (button instanceof IdButton) ((IdButton) button).onPress.run();
    }
    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawBody(java.util.Collections.singletonList(message), PeerCraftUi.TEXT_TITLE);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
    @Override protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) { answer(false); return; }
        super.keyTyped(typedChar, keyCode);
    }
}
