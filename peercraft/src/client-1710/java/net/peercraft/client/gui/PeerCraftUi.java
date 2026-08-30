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
final class PeerCraftUi {

    static final int TEXT_TITLE = 0xFFFFFFFF;
    static final int TEXT_MUTED = 0xFFAAAAAA;
    static final int TEXT_ERROR = 0xFFFF5555;
    static final int TEXT_SUCCESS = 0xFF55FF55;
    static final int TEXT_ACCENT = 0xFFFFD966;

    private PeerCraftUi() {
    }

    static void setScreen(Minecraft mc, GuiScreen screen) {
        mc.displayGuiScreen(screen);
    }

    static boolean isCurrentScreen(GuiScreen screen) {
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
