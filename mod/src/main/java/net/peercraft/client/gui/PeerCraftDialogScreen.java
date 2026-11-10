package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A compact modal that leaves the current world visible when opened in-game. */
abstract class PeerCraftDialogScreen extends Screen {
    protected SteampunkDialog dialog;
    private int footerActions, bodyOffset, bodyRows, bodyLines;
    private final long animationStart = System.nanoTime();

    protected PeerCraftDialogScreen(Component title) { super(title); }

    @Override
    protected void init() {
        footerActions = 0;
        this.dialog = new SteampunkDialog(this.width, this.height, 300, this.title);
    }

    protected Button dialogAction(Component label, Button.OnPress callback, boolean primary, int index, int count) {
        footerActions = Math.max(footerActions, count);
        int pitch = this.dialog.buttonPitch();
        return SteampunkSettingsTheme.action(this.dialog.contentX(),
                this.dialog.top + this.dialog.height - 10 - count * pitch + index * pitch,
                this.dialog.contentWidth(), this.dialog.buttonHeight(), label, callback, primary);
    }

    //? if <26.1 {
    protected void drawBody(GuiGraphics graphics, java.util.List<String> paragraphs, int color) {
        java.util.List<net.minecraft.util.FormattedCharSequence> lines = new java.util.ArrayList<>();
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) lines.add(Component.empty().getVisualOrderText());
            else lines.addAll(font.split(Component.literal(paragraph), Math.max(1, dialog.contentWidth() - 8)));
        }
        int bottom = dialog.top + dialog.height - 18 - footerActions * dialog.buttonPitch();
        bodyRows = Math.max(0, (bottom - dialog.contentTop()) / 12);
        bodyLines = lines.size();
        bodyOffset = Math.max(0, Math.min(bodyOffset, Math.max(0, bodyLines - bodyRows)));
        int shown = Math.min(bodyRows, bodyLines - bodyOffset);
        for (int i = 0; i < shown; i++) {
            net.minecraft.util.FormattedCharSequence line = lines.get(i + bodyOffset);
            graphics.drawString(font, line, width / 2 - font.width(line) / 2, dialog.contentTop() + i * 12, color, false);
        }
        if (bodyRows > 0 && bodyLines > bodyRows) {
            int height = bodyRows * 12, thumb = Math.max(8, height * bodyRows / bodyLines);
            int y = dialog.contentTop() + (height - thumb) * bodyOffset / (bodyLines - bodyRows);
            int x = dialog.contentX() + dialog.contentWidth() - 3;
            graphics.fill(x, dialog.contentTop(), x + 3, dialog.contentTop() + height, 0xFF2C241B);
            graphics.fill(x, y, x + 3, y + thumb, 0xFFBB8B4B);
        }
    }
    //?} else {
    /*    protected void drawBody(GuiGraphicsExtractor graphics, java.util.List<String> paragraphs, int color) {
        java.util.List<net.minecraft.util.FormattedCharSequence> lines = new java.util.ArrayList<>();
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) lines.add(Component.empty().getVisualOrderText());
            else lines.addAll(font.split(Component.literal(paragraph), Math.max(1, dialog.contentWidth() - 8)));
        }
        int bottom = dialog.top + dialog.height - 18 - footerActions * dialog.buttonPitch();
        bodyRows = Math.max(0, (bottom - dialog.contentTop()) / 12);
        bodyLines = lines.size();
        bodyOffset = Math.max(0, Math.min(bodyOffset, Math.max(0, bodyLines - bodyRows)));
        int shown = Math.min(bodyRows, bodyLines - bodyOffset);
        for (int i = 0; i < shown; i++) {
            net.minecraft.util.FormattedCharSequence line = lines.get(i + bodyOffset);
            graphics.text(font, line, width / 2 - font.width(line) / 2, dialog.contentTop() + i * 12, color, false);
        }
        if (bodyRows > 0 && bodyLines > bodyRows) {
            int height = bodyRows * 12, thumb = Math.max(8, height * bodyRows / bodyLines);
            int y = dialog.contentTop() + (height - thumb) * bodyOffset / (bodyLines - bodyRows);
            int x = dialog.contentX() + dialog.contentWidth() - 3;
            graphics.fill(x, dialog.contentTop(), x + 3, dialog.contentTop() + height, 0xFF2C241B);
            graphics.fill(x, y, x + 3, y + thumb, 0xFFBB8B4B);
        }
    }
    */
    //?}
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0 && x >= dialog.contentX() && x < dialog.contentX() + dialog.contentWidth()
                && y >= dialog.contentTop() && y < dialog.contentTop() + bodyRows * 12 && bodyLines > bodyRows) {
            bodyOffset = Math.max(0, Math.min(bodyLines - bodyRows, bodyOffset - (int) Math.signum(vertical) * 3));
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    private boolean pageBody(int key) {
        if (key != 266 && key != 267 || bodyLines <= bodyRows) return false;
        bodyOffset = Math.max(0, Math.min(bodyLines - bodyRows, bodyOffset + (key == 266 ? -1 : 1) * Math.max(1, bodyRows)));
        return true;
    }
    //? if <1.21.9 {
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        return pageBody(key) || super.keyPressed(key, scan, modifiers);
    }
    //?} else {
    /*@Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        return pageBody(event.key()) || super.keyPressed(event);
    }*/
    //?}

    //? if <26.1 {
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.minecraft.level != null) {
            super.renderBackground(graphics, mouseX, mouseY, partialTick);
            drawWorldPanel(graphics);
        } else {
            this.dialog.background(graphics, this.font, this.width, this.height,
                    (System.nanoTime() - this.animationStart) / 1_000_000L);
        }
    }

    private void drawWorldPanel(GuiGraphics graphics) {
        SteampunkSettingsTheme.frame(graphics, this.dialog.left, this.dialog.top, this.dialog.width,
                this.dialog.height, SteampunkSettingsTheme.PANEL, SteampunkSettingsTheme.BORDER);
        graphics.drawCenteredString(this.font, "PeerCraft", this.width / 2, this.dialog.top + 10, SteampunkSettingsTheme.ACCENT);
        this.dialog.status(graphics, this.font, this.title, this.dialog.top + 26,
                this.dialog.headerHeight - 24, SteampunkSettingsTheme.TEXT, -1, -1);
        this.dialog.divider(graphics, this.dialog.top + this.dialog.headerHeight);
    }
    //?} else {
    /*@Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (this.minecraft.level != null) {
            super.extractBackground(graphics, mouseX, mouseY, partialTick);
            SteampunkSettingsTheme.frame(graphics, this.dialog.left, this.dialog.top, this.dialog.width,
                    this.dialog.height, SteampunkSettingsTheme.PANEL, SteampunkSettingsTheme.BORDER);
            graphics.centeredText(this.font, "PeerCraft", this.width / 2, this.dialog.top + 10, SteampunkSettingsTheme.ACCENT);
            this.dialog.status(graphics, this.font, this.title, this.dialog.top + 26,
                    this.dialog.headerHeight - 24, SteampunkSettingsTheme.TEXT, -1, -1);
            this.dialog.divider(graphics, this.dialog.top + this.dialog.headerHeight);
        } else {
            this.dialog.background(graphics, this.font, this.width, this.height,
                    (System.nanoTime() - this.animationStart) / 1_000_000L);
        }
    }*/
    //?}
}
