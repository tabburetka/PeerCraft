package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shown once, the first time this player would run mod sync, before anything is fetched:
 * mod sync installs jars chosen by the host, and a jar is arbitrary code. The player either
 * acknowledges the risk (a flag in {@code settings.json} is set and this screen never returns)
 * or cancels the join. A short red reminder stays on the preparing/confirm screens after that.
 */
public class ModSyncSecurityNoticeScreen extends Screen {

    private final Runnable onAccept;
    private final Runnable onCancel;

    public ModSyncSecurityNoticeScreen(Runnable onAccept, Runnable onCancel) {
        super(Component.translatable("peercraft.modsync.notice.title"));
        this.onAccept = onAccept;
        this.onCancel = onCancel;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height - 52;
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.modsync.notice.accept"), b -> onAccept.run())
                .bounds(cx - 204, y, 200, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.modsync.notice.decline"), b -> onCancel.run())
                .bounds(cx + 4, y, 200, 20).build());
    }

    @Override
    public void onClose() {
        onCancel.run();
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = 48;
        graphics.drawCenteredString(this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 24;
        graphics.drawCenteredString(this.font, Component.translatable("peercraft.modsync.notice.body1"), cx, y, 0xFFCCCCCC);
        y += 16;
        graphics.drawCenteredString(this.font, Component.translatable("peercraft.modsync.notice.body2"), cx, y, 0xFFFF5555);
        y += 16;
        graphics.drawCenteredString(this.font, Component.translatable("peercraft.modsync.notice.body3"), cx, y, 0xFFCCCCCC);
        y += 16;
        graphics.drawCenteredString(this.font, Component.translatable("peercraft.modsync.notice.body4"), cx, y, 0xFFAAAAAA);
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = 48;
        graphics.centeredText(this.font, this.title, cx, y, 0xFFFFFFFF);
        y += 24;
        graphics.centeredText(this.font, Component.translatable("peercraft.modsync.notice.body1"), cx, y, 0xFFCCCCCC);
        y += 16;
        graphics.centeredText(this.font, Component.translatable("peercraft.modsync.notice.body2"), cx, y, 0xFFFF5555);
        y += 16;
        graphics.centeredText(this.font, Component.translatable("peercraft.modsync.notice.body3"), cx, y, 0xFFCCCCCC);
        y += 16;
        graphics.centeredText(this.font, Component.translatable("peercraft.modsync.notice.body4"), cx, y, 0xFFAAAAAA);
    }*/
    //?}
}
