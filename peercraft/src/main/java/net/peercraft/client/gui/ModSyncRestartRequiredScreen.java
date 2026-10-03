package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Shown after mod sync has installed the missing jars. Minecraft can't load them without a
 * relaunch, so the join is deliberately NOT continued — the player quits (or goes to the
 * title screen) and reconnects next launch, when the mods are present and the join goes
 * straight through.
 */
public class ModSyncRestartRequiredScreen extends PeerCraftDialogScreen {

    private int scroll;

    private final List<String> installed;

    public ModSyncRestartRequiredScreen(List<String> installed) {
        super(Component.translatable("peercraft.modsync.restart.title"));
        this.installed = List.copyOf(installed);
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int y = this.height - 52;
        this.addRenderableWidget(dialogAction(Component.translatable("peercraft.modsync.restart.quit"), b -> this.minecraft.stop(), true, 0, 2));
        this.addRenderableWidget(dialogAction(Component.translatable("peercraft.modsync.restart.back"), b -> toTitle(), false, 1, 2));
    }

    private List<net.minecraft.util.FormattedCharSequence> bodyLines() {
        List<net.minecraft.util.FormattedCharSequence> lines = new java.util.ArrayList<>();
        lines.addAll(font.split(Component.translatable("peercraft.modsync.restart.body", installed.size()), dialog.contentWidth() - 8));
        for (String file : installed) lines.addAll(font.split(Component.literal(file), dialog.contentWidth() - 8));
        return lines;
    }
    private int bodyRows() {
        int bottom = dialog.top + dialog.height - 18 - 2 * dialog.buttonPitch();
        return Math.max(0, (bottom - dialog.contentTop()) / 12);
    }
    private int maxScroll() { return Math.max(0, bodyLines().size() - bodyRows()); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0 && x >= dialog.contentX() && x < dialog.contentX() + dialog.contentWidth()
                && y >= dialog.contentTop() && y < dialog.contentTop() + bodyRows() * 12) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(vertical) * 3));
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    private boolean page(int key) {
        if (key != 266 && key != 267) return false;
        scroll = Math.max(0, Math.min(maxScroll(), scroll + (key == 266 ? -1 : 1) * Math.max(1, bodyRows())));
        return true;
    }
    //? if <1.21.9 {
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        return page(key) || super.keyPressed(key, scan, modifiers);
    }
    //?} else {
    /*@Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        return page(event.key()) || super.keyPressed(event);
    }*/
    //?}

    private void toTitle() {
        //? if <26.2
        this.minecraft.setScreen(new TitleScreen());
        //? if >=26.2
        /*this.minecraft.gui.setScreen(new TitleScreen());*/
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        List<net.minecraft.util.FormattedCharSequence> lines = bodyLines();
        int rows = bodyRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - rows)));
        int shown = Math.min(rows, lines.size() - scroll);
        for (int i = 0; i < shown; i++) {
            net.minecraft.util.FormattedCharSequence line = lines.get(scroll + i);
            graphics.drawString(font, line, dialog.contentX(), dialog.contentTop() + i * 12, 0xFFE9DFCB, false);
        }
        if (rows > 0 && lines.size() > rows) {
            int height = rows * 12;
            int thumb = Math.max(8, height * rows / lines.size());
            int y = dialog.contentTop() + (height - thumb) * scroll / (lines.size() - rows);
            int x = dialog.contentX() + dialog.contentWidth() - 3;
            graphics.fill(x, dialog.contentTop(), x + 3, dialog.contentTop() + height, 0xFF2C241B);
            graphics.fill(x, y, x + 3, y + thumb, 0xFFBB8B4B);
        }
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        List<net.minecraft.util.FormattedCharSequence> lines = bodyLines();
        int rows = bodyRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - rows)));
        int shown = Math.min(rows, lines.size() - scroll);
        for (int i = 0; i < shown; i++) {
            net.minecraft.util.FormattedCharSequence line = lines.get(scroll + i);
            graphics.text(font, line, dialog.contentX(), dialog.contentTop() + i * 12, 0xFFE9DFCB, false);
        }
        if (rows > 0 && lines.size() > rows) {
            int height = rows * 12;
            int thumb = Math.max(8, height * rows / lines.size());
            int y = dialog.contentTop() + (height - thumb) * scroll / (lines.size() - rows);
            int x = dialog.contentX() + dialog.contentWidth() - 3;
            graphics.fill(x, dialog.contentTop(), x + 3, dialog.contentTop() + height, 0xFF2C241B);
            graphics.fill(x, y, x + 3, y + thumb, 0xFFBB8B4B);
        }
    }*/
    //?}
}
