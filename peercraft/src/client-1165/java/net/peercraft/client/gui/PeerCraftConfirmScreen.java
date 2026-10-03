package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import java.util.function.Consumer;

final class PeerCraftConfirmScreen extends PeerCraftDialogScreen {
    private final Consumer<Boolean> callback;
    private final Component message, yes, no;
    private boolean answered;
    PeerCraftConfirmScreen(Consumer<Boolean> callback, Component title, Component message) {
        this(callback, title, message, new TranslatableComponent("gui.yes"), new TranslatableComponent("gui.no"));
    }
    PeerCraftConfirmScreen(Consumer<Boolean> callback, Component title, Component message, Component yes, Component no) {
        super(title, 240);
        this.callback = callback; this.message = message; this.yes = yes; this.no = no;
    }
    @Override protected void init() {
        super.init();
        dialogAction(yes, b -> answer(true), true, 0, 2);
        dialogAction(no, b -> answer(false), false, 1, 2);
    }
    private void answer(boolean value) { if (!answered) { answered = true; callback.accept(value); } }
    @Override public void onClose() { answer(false); }
    @Override public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        drawBody(pose, java.util.Collections.singletonList(message.getString()), PeerCraftUi.TEXT_TITLE);
        super.render(pose, mouseX, mouseY, partialTick);
    }
}
