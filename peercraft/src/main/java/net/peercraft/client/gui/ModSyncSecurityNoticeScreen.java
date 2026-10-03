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
public class ModSyncSecurityNoticeScreen extends PeerCraftDialogScreen {

    private final Runnable onAccept;
    private final Runnable onCancel;

    public ModSyncSecurityNoticeScreen(Runnable onAccept, Runnable onCancel) {
        super(Component.translatable("peercraft.modsync.notice.title"));
        this.onAccept = onAccept;
        this.onCancel = onCancel;
    }

    @Override
    protected void init() {
        super.init();
        int lines = 0;
        for (String paragraph : noticeParagraphs()) {
            lines += paragraph.isEmpty() ? 1 : font.split(Component.literal(paragraph), Math.max(1, dialog.contentWidth() - 8)).size();
        }
        this.dialog = new SteampunkDialog(width, height,
                dialog.headerHeight + 6 + lines * 12 + 18 + 2 * dialog.buttonPitch() + 10, title);
        this.addRenderableWidget(dialogAction(Component.translatable("peercraft.modsync.notice.accept"), b -> onAccept.run(), true, 0, 2));
        this.addRenderableWidget(dialogAction(Component.translatable("peercraft.modsync.notice.decline"), b -> onCancel.run(), false, 1, 2));
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
        drawBody(graphics, noticeParagraphs(), SteampunkSettingsTheme.TEXT);
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        drawBody(graphics, noticeParagraphs(), SteampunkSettingsTheme.TEXT);
    }*/
    //?}
    private java.util.List<String> noticeParagraphs() {
        java.util.List<String> paragraphs = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            if (i > 1) paragraphs.add("");
            paragraphs.add(Component.translatable("peercraft.modsync.notice.body" + i).getString());
        }
        return paragraphs;
    }

}
