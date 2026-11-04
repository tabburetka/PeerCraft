package net.peercraft.client.gui;

import org.lwjgl.opengl.GL11;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

import net.peercraft.client.theme.SteampunkPalette;

/** Legacy Forge renderer for the shared charcoal and brass dialog design. */
final class SteampunkDialog {
    private static void center(FontRenderer font, String text, int x, int y, int color) { font.drawStringWithShadow(text, x - font.getStringWidth(text) / 2, y, color); }
    final int left, top, width, height, headerHeight;
    final boolean compact;
    private final String subtitle;

    SteampunkDialog(int screenWidth, int screenHeight, int desiredHeight, String subtitle) {
        this(screenWidth, screenHeight, desiredHeight, subtitle, 320);
    }
    SteampunkDialog(int screenWidth, int screenHeight, int desiredHeight, String subtitle, int desiredWidth) {
        compact = screenHeight < 300;
        width = Math.max(1, Math.min(desiredWidth, screenWidth - 24));
        height = Math.max(1, Math.min(desiredHeight, screenHeight - 16));
        left = (screenWidth - width) / 2;
        top = (screenHeight - height) / 2;
        headerHeight = compact ? 36 : 46;
        this.subtitle = subtitle.startsWith("PeerCraft — ") ? subtitle.substring("PeerCraft — ".length()) : subtitle;
    }
    int contentX() { return left + 12; }
    int contentWidth() { return Math.max(1, width - 24); }
    int contentTop() { return top + headerHeight + 6; }
    int buttonHeight() { return compact ? 18 : 24; }
    int buttonPitch() { return compact ? 22 : 30; }

    static void frame(int x, int y, int width, int height, int fill, int border) {
        Gui.drawRect(x, y, x + width, y + height, border);
        Gui.drawRect(x + 1, y + 1, x + width - 1, y + height - 1, fill);
    }
    void background(FontRenderer font, int screenWidth, int screenHeight, long elapsed, boolean inWorld) {
        if (!inWorld) {
            Gui.drawRect(0, 0, screenWidth, screenHeight, SteampunkPalette.BACKGROUND);
            for (int i = 0; i < 28; i++) {
                double phase = (i * 0.61803398875 + elapsed / (24000.0 + (i % 5) * 3000.0)) % 1.0;
                int sideWidth = Math.max(0, left - 12);
                if (sideWidth < 8) continue;
                int x = 4 + (int) (((i * 0.754877666 + Math.sin(elapsed / 4200.0 + i) * 0.025) % 1.0 + 1.0) % 1.0 * (sideWidth - 4));
                if ((i & 1) != 0) x = screenWidth - x;
                int y = (int) ((1.0 - phase) * screenHeight);
                Gui.drawRect(x - 1, y - 1, x + 2, y + 2, 0x183C2B10);
                Gui.drawRect(x, y, x + 1, y + 1, 0x997C592A);
            }
        }
        frame(left, top, width, height, SteampunkPalette.PANEL, SteampunkPalette.BORDER);
        float scale = compact ? 1.25F : 1.5F;
        GL11.glPushMatrix();
        GL11.glScalef(scale, scale, 1.0F);
        center(font, "PeerCraft", (int) (screenWidth / 2.0F / scale),
                (int) ((top + 8) / scale), SteampunkPalette.ACCENT);
        GL11.glPopMatrix();
        center(font, font.trimStringToWidth(subtitle, contentWidth()), screenWidth / 2, top + (compact ? 23 : 28), SteampunkPalette.TEXT);
        Gui.drawRect(contentX(), top + headerHeight, contentX() + contentWidth(), top + headerHeight + 1, 0xFF49331F);
    }
    void status(FontRenderer font, String message, int y, int availableHeight, int color) {
        java.util.List<String> lines = PeerCraftUi.wrap(font, message, contentWidth());
        int count = Math.min(lines.size(), Math.max(0, availableHeight / font.FONT_HEIGHT));
        for (int i = 0; i < count; i++) center(font, lines.get(i), left + width / 2, y + i * font.FONT_HEIGHT, color);
    }
}
