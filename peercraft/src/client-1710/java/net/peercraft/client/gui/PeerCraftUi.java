package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;

/**
 * Forge 1.7.10 backport of {@code src/main/.../PeerCraftUi.java} (twin of {@code src/client-1122}
 * / {@code src/client-1165}). Same colour scheme and helpers on the 1.7.10 API — which for the
 * pieces this class touches is identical to 1.12.2:
 * <ul>
 *   <li>{@code Minecraft.getInstance()} → {@code Minecraft.getMinecraft()};
 *       {@code mc.setScreen} / {@code mc.screen} → {@code displayGuiScreen} / {@code currentScreen}.</li>
 *   <li>{@code GuiGraphics}/{@code Font} → {@code FontRenderer}; {@code font.width} →
 *       {@code getStringWidth}; drawing is done by the screen via {@code fontRenderer.drawString}
 *       (note: the field is {@code fontRendererObj} on 1.7.10, {@code fontRenderer} on 1.12.2).</li>
 *   <li>{@code Component.translatable("k").getString()} → {@code PeerCraftLang.tr("k")}.</li>
 *   <li>{@code squareGlyphButton} returns an {@link IdButton}; 1.7.10 has no tooltip object so
 *       {@code tooltipText} is unused (same as the 1.12.2 / 1.16.5 twins).</li>
 * </ul>
 */
public final class PeerCraftUi {

    static final int TEXT_TITLE = net.peercraft.client.theme.SteampunkPalette.TEXT;
    static final int TEXT_MUTED = net.peercraft.client.theme.SteampunkPalette.MUTED;
    static final int TEXT_ERROR = 0xFFFF5555;
    static final int TEXT_SUCCESS = 0xFF55FF55;
    static final int TEXT_ACCENT = net.peercraft.client.theme.SteampunkPalette.ACCENT;

    private PeerCraftUi() {
    }

    /** Greedy word-wrap of {@code text} to lines no wider than {@code maxWidth} px. Matches src/main's {@code PeerCraftUi.wrap}. */
    public static java.util.List<String> wrap(FontRenderer font, String text, int maxWidth) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                // Filenames and IDs may contain no spaces: they still have to stay inside the panel.
                while (font.getStringWidth(word) > Math.max(1, maxWidth)) {
                    if (line.length() > 0) { lines.add(line.toString()); line.setLength(0); }
                    String part = font.trimStringToWidth(word, Math.max(1, maxWidth));
                    if (part.isEmpty()) part = word.substring(0, word.offsetByCodePoints(0, 1));
                    lines.add(part);
                    word = word.substring(part.length());
                }
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (font.getStringWidth(candidate) > maxWidth && line.length() > 0) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /** "12.3 MB" / "512 KB" / "900 B" — matches src/main's {@code PeerCraftUi.humanSize}. */
    public static String humanSize(long b) {
        if (b < 1024) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024) return String.format("%.0f KB", kb);
        return String.format("%.1f MB", kb / 1024.0);
    }

    public static void setScreen(Minecraft mc, GuiScreen screen) {
        mc.displayGuiScreen(screen);
    }

    public static boolean isCurrentScreen(GuiScreen screen) {
        return Minecraft.getMinecraft().currentScreen == screen;
    }

    static boolean isValidUsername(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            boolean digit = c >= '0' && c <= '9';
            if (!letter && !digit && c != '_') {
                return false;
            }
        }
        return true;
    }

    static String badgeText(boolean licensed) {
        return licensed ? " ✓" : " " + PeerCraftLang.tr("peercraft.gui.common.unlicensed_badge");
    }

    static int badgeColor(boolean licensed) {
        return licensed ? TEXT_SUCCESS : TEXT_MUTED;
    }

    /** Draws {@code name} left-aligned at {@code x}, followed by its badge. Returns the x just past the badge. */
    static int drawNameWithBadge(FontRenderer font, String name, boolean licensed, int x, int y, int nameColor) {
        font.drawString(name, x, y, nameColor);
        int badgeX = x + font.getStringWidth(name);
        String badge = badgeText(licensed);
        font.drawString(badge, badgeX, y, badgeColor(licensed));
        return badgeX + font.getStringWidth(badge);
    }

    /** Draws {@code name} + badge centered as one unit around {@code centerX}. */
    static void drawNameWithBadgeCentered(FontRenderer font, String name, boolean licensed, int centerX, int y, int nameColor) {
        String badge = badgeText(licensed);
        int totalWidth = font.getStringWidth(name) + font.getStringWidth(badge);
        drawNameWithBadge(font, name, licensed, centerX - totalWidth / 2, y, nameColor);
    }

    /** A small square glyph button. 1.7.10 has no tooltip object, so {@code tooltipText} is unused. */
    static IdButton squareGlyphButton(int x, int y, int size, String glyph, String tooltipText, Runnable onPress) {
        return IdButton.builder(glyph, onPress).bounds(x, y, size, size).build();
    }
}
