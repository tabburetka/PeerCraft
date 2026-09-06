package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shown the instant the mod-sync handshake starts, so the gap between clicking Connect and
 * the confirm screen (receiving the host's mod list, then resolving download sources for it)
 * isn't a blank, seemingly-frozen screen. Carries a status line the agent updates through the
 * phases and an indeterminate "working" bar.
 */
public class ModSyncPreparingScreen extends Screen {

    private static final int TRACK = 28;

    private volatile Component status = Component.translatable("peercraft.modsync.prepare.connecting");
    private final Runnable onCancel;

    public ModSyncPreparingScreen(Runnable onCancel) {
        super(Component.translatable("peercraft.modsync.prepare.title"));
        this.onCancel = onCancel;
    }

    public void setStatus(Component status) {
        this.status = status;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.modsync.confirm.cancel"), b -> onCancel.run())
                .bounds(this.width / 2 - 100, this.height - 44, 200, 20).build());
    }

    @Override
    public void onClose() {
        onCancel.run();
    }

    /** A single block bouncing back and forth inside a fixed-width track — visibly alive, no percentage. */
    private String indeterminateBar() {
        int span = TRACK - 3;
        int t = (int) ((System.currentTimeMillis() / 90) % (2L * span));
        int pos = t < span ? t : (2 * span - t);
        StringBuilder sb = new StringBuilder(TRACK);
        for (int i = 0; i < TRACK; i++) {
            sb.append(i >= pos && i < pos + 3 ? '█' : '░');
        }
        return sb.toString();
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, cx, this.height / 2 - 30, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, this.status, cx, this.height / 2 - 6, 0xFFAAAAAA);
        graphics.drawCenteredString(this.font, indeterminateBar(), cx, this.height / 2 + 14, 0xFFFFD966);
        graphics.drawCenteredString(this.font, Component.translatable("peercraft.modsync.confirm.trust_reminder"),
                cx, this.height / 2 + 40, 0xFFFF5555);
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        graphics.centeredText(this.font, this.title, cx, this.height / 2 - 30, 0xFFFFFFFF);
        graphics.centeredText(this.font, this.status, cx, this.height / 2 - 6, 0xFFAAAAAA);
        graphics.centeredText(this.font, indeterminateBar(), cx, this.height / 2 + 14, 0xFFFFD966);
        graphics.centeredText(this.font, Component.translatable("peercraft.modsync.confirm.trust_reminder"),
                cx, this.height / 2 + 40, 0xFFFF5555);
    }*/
    //?}
}
