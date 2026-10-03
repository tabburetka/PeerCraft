package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.handoff.PeercraftWorldMeta;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Vanilla-style "are you sure" gate shown when the player opens a world in singleplayer that
 * was handed off to someone else and hasn't been reclaimed since — opening it means starting
 * from the older, pre-handoff state. Two buttons: proceed anyway / back.
 */
public class HandoffStaleWorldWarningScreen extends PeerCraftDialogScreen {

    private final PeercraftWorldMeta meta;
    private final Runnable onProceed;
    private final Runnable onBack;
    private boolean chosen;

    public HandoffStaleWorldWarningScreen(PeercraftWorldMeta meta, Runnable onProceed, Runnable onBack) {
        super(Component.translatable("peercraft.handoff.stale.title"));
        this.meta = meta;
        this.onProceed = onProceed;
        this.onBack = onBack;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        this.addRenderableWidget(dialogAction(Component.translatable("peercraft.handoff.stale.proceed"), b -> choose(onProceed), true, 0, 2));
        this.addRenderableWidget(dialogAction(Component.translatable("peercraft.handoff.stale.back"), b -> choose(onBack), false, 1, 2));
    }

    private void choose(Runnable action) {
        if (chosen) {
            return;
        }
        chosen = true;
        action.run();
    }

    @Override
    public void onClose() {
        choose(onBack);
    }

    private List<String> bodyLines() {
        String to = meta.handedOffTo() == null || meta.handedOffTo().isEmpty()
                ? Component.translatable("peercraft.handoff.stale.someone").getString()
                : meta.handedOffTo();
        String when = meta.handedOffAt() > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(meta.handedOffAt() * 1000L))
                : "?";
        String body = Component.translatable("peercraft.handoff.stale.body", to, when).getString();
        return PeerCraftUi.wrap(this.font, body, this.dialog.contentWidth());
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.dialog.contentTop();
        drawBody(graphics, bodyLines(), 0xFFCCCCCC);
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.dialog.contentTop();
        drawBody(graphics, bodyLines(), 0xFFCCCCCC);
    }*/
    //?}
}
