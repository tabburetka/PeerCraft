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
public class HandoffStaleWorldWarningScreen extends Screen {

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
        int cx = this.width / 2;
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.handoff.stale.proceed"), b -> choose(onProceed))
                .bounds(cx - 155, this.height - 44, 150, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.handoff.stale.back"), b -> choose(onBack))
                .bounds(cx + 5, this.height - 44, 150, 20).build());
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
        return PeerCraftUi.wrap(this.font, body, Math.min(this.width - 60, 380));
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 40;
        graphics.drawCenteredString(this.font, this.title, cx, y, 0xFFFF5555);
        y += 22;
        for (String line : bodyLines()) {
            graphics.drawCenteredString(this.font, line, cx, y, 0xFFCCCCCC);
            y += 12;
        }
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 40;
        graphics.centeredText(this.font, this.title, cx, y, 0xFFFF5555);
        y += 22;
        for (String line : bodyLines()) {
            graphics.centeredText(this.font, line, cx, y, 0xFFCCCCCC);
            y += 12;
        }
    }*/
    //?}
}
