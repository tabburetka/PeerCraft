package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

final class PeerCraftConfirmScreen extends PeerCraftDialogScreen {
    private final Consumer<Boolean> callback;
    private final Component message, yes, no;
    private boolean completed;
    PeerCraftConfirmScreen(Consumer<Boolean> callback, Component title, Component message) {
        this(callback, title, message, Component.translatable("gui.yes"), Component.translatable("gui.no"));
    }
    PeerCraftConfirmScreen(Consumer<Boolean> callback, Component title, Component message, Component yes, Component no) {
        super(title); this.callback = callback; this.message = message; this.yes = yes; this.no = no;
    }
    @Override protected void init() {
        super.init();
        this.addRenderableWidget(dialogAction(this.yes, b -> finish(true), true, 0, 2));
        this.addRenderableWidget(dialogAction(this.no, b -> finish(false), false, 1, 2));
    }
    private void finish(boolean accepted) { if (!completed) { completed = true; callback.accept(accepted); } }
    @Override public void onClose() { finish(false); }
    //? if <26.1 {
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.dialog.status(graphics, this.font, this.message, this.dialog.contentTop(),
                this.dialog.height - this.dialog.headerHeight - 2 * this.dialog.buttonPitch() - 22,
                SteampunkSettingsTheme.TEXT, mouseX, mouseY);
    }
    //?} else {
    /*@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        this.dialog.status(graphics, this.font, this.message, this.dialog.contentTop(),
                this.dialog.height - this.dialog.headerHeight - 2 * this.dialog.buttonPitch() - 22,
                SteampunkSettingsTheme.TEXT, mouseX, mouseY);
    }*/
    //?}
}
