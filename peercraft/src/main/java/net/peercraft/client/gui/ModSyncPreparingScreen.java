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
public class ModSyncPreparingScreen extends PeerCraftDialogScreen {


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
        super.init();
        int reminderLines = font.split(Component.translatable("peercraft.modsync.confirm.trust_reminder"), dialog.contentWidth()).size();
        dialog = new SteampunkDialog(width, height, dialog.headerHeight + 6 + 38 + 12 + reminderLines * 12 + 18 + dialog.buttonPitch() + 10, title);
        this.addRenderableWidget(dialogAction(Component.translatable("peercraft.modsync.confirm.cancel"), b -> onCancel.run(), true, 0, 1));
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
        int y = dialog.contentTop();
        dialog.status(graphics, font, status, y, 26, SteampunkSettingsTheme.MUTED, mouseX, mouseY);
        int trackY = y + 30, trackWidth = dialog.contentWidth();
        int blockWidth = Math.max(12, trackWidth / 8);
        int span = Math.max(1, trackWidth - blockWidth - 2);
        int phase = (int) ((System.currentTimeMillis() / 12) % (2L * span));
        int blockX = dialog.contentX() + 1 + (phase < span ? phase : 2 * span - phase);
        SteampunkSettingsTheme.frame(graphics, dialog.contentX(), trackY, trackWidth, 6,
                net.peercraft.client.theme.SteampunkPalette.CONTROL, SteampunkSettingsTheme.BORDER);
        graphics.fill(blockX, trackY + 1, blockX + blockWidth, trackY + 5, SteampunkSettingsTheme.ACCENT);
        dialog.status(graphics, font, Component.translatable("peercraft.modsync.confirm.trust_reminder"),
                trackY + 16, Math.max(0, dialog.top + dialog.height - 28 - dialog.buttonPitch() - trackY - 16),
                0xFFFF5555, mouseX, mouseY);
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int y = dialog.contentTop();
        dialog.status(graphics, font, status, y, 26, SteampunkSettingsTheme.MUTED, mouseX, mouseY);
        int trackY = y + 30, trackWidth = dialog.contentWidth();
        int blockWidth = Math.max(12, trackWidth / 8);
        int span = Math.max(1, trackWidth - blockWidth - 2);
        int phase = (int) ((System.currentTimeMillis() / 12) % (2L * span));
        int blockX = dialog.contentX() + 1 + (phase < span ? phase : 2 * span - phase);
        SteampunkSettingsTheme.frame(graphics, dialog.contentX(), trackY, trackWidth, 6,
                net.peercraft.client.theme.SteampunkPalette.CONTROL, SteampunkSettingsTheme.BORDER);
        graphics.fill(blockX, trackY + 1, blockX + blockWidth, trackY + 5, SteampunkSettingsTheme.ACCENT);
        dialog.status(graphics, font, Component.translatable("peercraft.modsync.confirm.trust_reminder"),
                trackY + 16, Math.max(0, dialog.top + dialog.height - 28 - dialog.buttonPitch() - trackY - 16),
                0xFFFF5555, mouseX, mouseY);
    }*/
    //?}
}
