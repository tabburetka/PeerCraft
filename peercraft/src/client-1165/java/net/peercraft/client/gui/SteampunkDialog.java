package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.network.chat.Component;
import net.peercraft.client.theme.SteampunkPalette;

/** 1.16.5 renderer for the shared charcoal and brass dialog design. */
final class SteampunkDialog {
    final int left, top, width, height, headerHeight;
    final boolean compact;
    private final Component subtitle;

    SteampunkDialog(int screenWidth, int screenHeight, int desiredHeight, Component subtitle) {
        this(screenWidth, screenHeight, desiredHeight, subtitle, 320);
    }
    SteampunkDialog(int screenWidth, int screenHeight, int desiredHeight, Component subtitle, int desiredWidth) {
        compact = screenHeight < 300;
        width = Math.max(1, Math.min(desiredWidth, screenWidth - 24));
        height = Math.max(1, Math.min(desiredHeight, screenHeight - 16));
        left = (screenWidth - width) / 2;
        top = (screenHeight - height) / 2;
        headerHeight = compact ? 36 : 46;
        this.subtitle = new net.minecraft.network.chat.TextComponent(subtitle.getString().replace("PeerCraft — ", ""));
    }
    int contentX() { return left + 12; }
    int contentWidth() { return Math.max(1, width - 24); }
    int contentTop() { return top + headerHeight + 6; }
    int buttonHeight() { return compact ? 18 : 24; }
    int buttonPitch() { return compact ? 22 : 30; }

    static void frame(PoseStack pose, int x, int y, int width, int height, int fill, int border) {
        GuiComponent.fill(pose, x, y, x + width, y + height, border);
        GuiComponent.fill(pose, x + 1, y + 1, x + width - 1, y + height - 1, fill);
    }
    void background(PoseStack pose, Font font, int screenWidth, int screenHeight, long elapsed, boolean inWorld) {
        if (!inWorld) {
            GuiComponent.fill(pose, 0, 0, screenWidth, screenHeight, SteampunkPalette.BACKGROUND);
            for (int i = 0; i < 28; i++) {
                double phase = (i * 0.61803398875 + elapsed / (24000.0 + (i % 5) * 3000.0)) % 1.0;
                int sideWidth = Math.max(0, left - 12);
                if (sideWidth < 8) continue;
                int x = 4 + (int) (((i * 0.754877666 + Math.sin(elapsed / 4200.0 + i) * 0.025) % 1.0 + 1.0) % 1.0 * (sideWidth - 4));
                if ((i & 1) != 0) x = screenWidth - x;
                int y = (int) ((1.0 - phase) * screenHeight);
                GuiComponent.fill(pose, x - 1, y - 1, x + 2, y + 2, 0x183C2B10);
                GuiComponent.fill(pose, x, y, x + 1, y + 1, 0x997C592A);
            }
        }
        frame(pose, left, top, width, height, SteampunkPalette.PANEL, SteampunkPalette.BORDER);
        float scale = compact ? 1.25F : 1.5F;
        pose.pushPose();
        pose.scale(scale, scale, 1.0F);
        GuiComponent.drawCenteredString(pose, font, "PeerCraft", (int) (screenWidth / 2.0F / scale),
                (int) ((top + 8) / scale), SteampunkPalette.ACCENT);
        pose.popPose();
        GuiComponent.drawCenteredString(pose, font, font.plainSubstrByWidth(subtitle.getString(), contentWidth()), screenWidth / 2, top + (compact ? 23 : 28), SteampunkPalette.TEXT);
        GuiComponent.fill(pose, contentX(), top + headerHeight, contentX() + contentWidth(), top + headerHeight + 1, 0xFF49331F);
    }
    void status(PoseStack pose, Font font, Component message, int y, int availableHeight, int color) {
        java.util.List<String> lines = PeerCraftUi.wrap(font, message.getString(), contentWidth());
        int count = Math.min(lines.size(), Math.max(0, availableHeight / font.lineHeight));
        for (int i = 0; i < count; i++) GuiComponent.drawCenteredString(pose, font, lines.get(i), left + width / 2, y + i * font.lineHeight, color);
    }
}
