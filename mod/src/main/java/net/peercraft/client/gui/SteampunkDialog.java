//? if >=1.21.1 && <26.1 {
package net.peercraft.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** A compact dialog using the same palette and particles as the settings panel. */
final class SteampunkDialog {
    final int left;
    final int top;
    final int width;
    final int height;
    final int headerHeight;
    final boolean compact;
    private final Component subtitle;

    SteampunkDialog(int screenWidth, int screenHeight, int desiredHeight, Component subtitle) {
        this.compact = screenHeight < 300;
        this.width = Math.max(1, Math.min(320, screenWidth - 24));
        this.height = Math.max(1, Math.min(desiredHeight, screenHeight - 16));
        this.left = (screenWidth - this.width) / 2;
        this.top = (screenHeight - this.height) / 2;
        this.headerHeight = this.compact ? 36 : 46;
        this.subtitle = Component.literal(subtitle.getString().replace("PeerCraft — ", ""));
    }

    int contentX() {
        return this.left + 12;
    }

    int contentWidth() {
        return Math.max(1, this.width - 24);
    }

    int contentTop() {
        return this.top + this.headerHeight + 6;
    }

    int buttonHeight() {
        return this.compact ? 18 : 24;
    }

    int buttonPitch() {
        return this.compact ? 22 : 30;
    }

    void background(GuiGraphics graphics, Font font, int screenWidth, int screenHeight, long elapsedMillis) {
        graphics.fill(0, 0, screenWidth, screenHeight, SteampunkSettingsTheme.BACKGROUND);
        SteampunkSettingsTheme.particles(graphics, screenWidth, screenHeight, this.left,
                this.left + this.width, elapsedMillis);
        SteampunkSettingsTheme.frame(graphics, this.left, this.top, this.width, this.height,
                SteampunkSettingsTheme.PANEL, SteampunkSettingsTheme.BORDER);
        float scale = this.compact ? 1.25F : 1.5F;
        SteampunkSettingsTheme.pushScale(graphics, scale);
        graphics.drawCenteredString(font, "PeerCraft", (int) (screenWidth / 2.0F / scale),
                (int) ((this.top + 8) / scale), SteampunkSettingsTheme.ACCENT);
        SteampunkSettingsTheme.popScale(graphics);
        graphics.drawCenteredString(font, this.subtitle, screenWidth / 2, this.top + (this.compact ? 23 : 28),
                SteampunkSettingsTheme.TEXT);
        divider(graphics, this.top + this.headerHeight);
    }

    void divider(GuiGraphics graphics, int y) {
        graphics.fill(contentX(), y, contentX() + contentWidth(), y + 1, 0xFF49331F);
    }

    void status(GuiGraphics graphics, Font font, Component message, int y, int availableHeight,
                int color, int mouseX, int mouseY) {
        if (message.getString().isEmpty()) {
            return;
        }
        List<FormattedCharSequence> lines = font.split(message, contentWidth());
        int count = Math.min(lines.size(), Math.max(1, availableHeight / font.lineHeight));
        for (int i = 0; i < count; i++) {
            FormattedCharSequence line = lines.get(i);
            graphics.drawString(font, line, this.left + (this.width - font.width(line)) / 2,
                    y + i * font.lineHeight, color, false);
        }
        if (lines.size() > count && mouseX >= contentX() && mouseX < contentX() + contentWidth()
                && mouseY >= y && mouseY < y + availableHeight) {
            //? if <1.21.6 {
            graphics.renderTooltip(font, message, mouseX, mouseY);
            //?} else {
            /*graphics.setTooltipForNextFrame(font, message, mouseX, mouseY);*/
            //?}
        }
    }
}
//?} else {
/*package net.peercraft.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

final class SteampunkDialog {
    final int left;
    final int top;
    final int width;
    final int height;
    final int headerHeight;
    final boolean compact;
    private final Component subtitle;

    SteampunkDialog(int screenWidth, int screenHeight, int desiredHeight, Component subtitle) {
        this.compact = screenHeight < 300;
        this.width = Math.max(1, Math.min(320, screenWidth - 24));
        this.height = Math.max(1, Math.min(desiredHeight, screenHeight - 16));
        this.left = (screenWidth - this.width) / 2;
        this.top = (screenHeight - this.height) / 2;
        this.headerHeight = this.compact ? 36 : 46;
        this.subtitle = Component.literal(subtitle.getString().replace("PeerCraft — ", ""));
    }

    int contentX() {
        return this.left + 12;
    }

    int contentWidth() {
        return Math.max(1, this.width - 24);
    }

    int contentTop() {
        return this.top + this.headerHeight + 6;
    }

    int buttonHeight() {
        return this.compact ? 18 : 24;
    }

    int buttonPitch() {
        return this.compact ? 22 : 30;
    }

    void background(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight, long elapsedMillis) {
        graphics.fill(0, 0, screenWidth, screenHeight, SteampunkSettingsTheme.BACKGROUND);
        SteampunkSettingsTheme.particles(graphics, screenWidth, screenHeight, this.left,
                this.left + this.width, elapsedMillis);
        SteampunkSettingsTheme.frame(graphics, this.left, this.top, this.width, this.height,
                SteampunkSettingsTheme.PANEL, SteampunkSettingsTheme.BORDER);
        float scale = this.compact ? 1.25F : 1.5F;
        SteampunkSettingsTheme.pushScale(graphics, scale);
        graphics.centeredText(font, "PeerCraft", (int) (screenWidth / 2.0F / scale),
                (int) ((this.top + 8) / scale), SteampunkSettingsTheme.ACCENT);
        SteampunkSettingsTheme.popScale(graphics);
        graphics.centeredText(font, this.subtitle, screenWidth / 2, this.top + (this.compact ? 23 : 28),
                SteampunkSettingsTheme.TEXT);
        divider(graphics, this.top + this.headerHeight);
    }

    void divider(GuiGraphicsExtractor graphics, int y) {
        graphics.fill(contentX(), y, contentX() + contentWidth(), y + 1, 0xFF49331F);
    }

    void status(GuiGraphicsExtractor graphics, Font font, Component message, int y, int availableHeight,
                int color, int mouseX, int mouseY) {
        if (message.getString().isEmpty()) {
            return;
        }
        List<FormattedCharSequence> lines = font.split(message, contentWidth());
        int count = Math.min(lines.size(), Math.max(1, availableHeight / font.lineHeight));
        for (int i = 0; i < count; i++) {
            FormattedCharSequence line = lines.get(i);
            graphics.text(font, line, this.left + (this.width - font.width(line)) / 2,
                    y + i * font.lineHeight, color, false);
        }
        if (lines.size() > count && mouseX >= contentX() && mouseX < contentX() + contentWidth()
                && mouseY >= y && mouseY < y + availableHeight) {

            graphics.setTooltipForNextFrame(font, message, mouseX, mouseY);

        }
    }
}*/
//?}
