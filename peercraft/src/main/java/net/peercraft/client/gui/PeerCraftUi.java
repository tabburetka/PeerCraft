package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Shared look-and-feel for the PeerCraft screens: a status-message color scheme that actually
 * distinguishes loading/error/success (previously every message — including errors — was the
 * same yellow), plus a couple of small widget helpers reused across the account/friends screens.
 */
public final class PeerCraftUi {

    // Fully opaque (0xFF alpha) — GuiGraphics.drawString() since 1.21.6 silently skips rendering
    // entirely when a color's alpha byte is 0, which every one of these was before.
    static final int TEXT_TITLE = 0xFFFFFFFF;
    static final int TEXT_MUTED = 0xFFAAAAAA;
    static final int TEXT_ERROR = 0xFFFF5555;
    static final int TEXT_SUCCESS = 0xFF55FF55;
    static final int TEXT_ACCENT = 0xFFFFD966;

    private PeerCraftUi() {
    }

    /** Greedy word-wrap of {@code text} to lines no wider than {@code maxWidth} px. Version-stable (String metrics only). */
    static java.util.List<String> wrap(Font font, String text, int maxWidth) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (font.width(candidate) > maxWidth && line.length() > 0) {
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

    /** "12.3 MB" / "512 KB" / "900 B" — matches ModSyncProgressScreen's humanSize. */
    static String humanSize(long b) {
        if (b < 1024) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024) return String.format("%.0f KB", kb);
        return String.format("%.1f MB", kb / 1024.0);
    }

    /**
     * Navigate to {@code screen}. 26.2 moved {@code setScreen}/{@code screen} off {@code Minecraft}
     * onto {@code Minecraft.gui} — routed through here so the ~30 call sites stay version-agnostic.
     */
    public static void setScreen(Minecraft mc, Screen screen) {
        //? if <26.2
        mc.setScreen(screen);
        //? if >=26.2
        /*mc.gui.setScreen(screen);*/
    }

    /** Whether {@code screen} is the one currently shown (used by async callbacks to bail if the player navigated away). */
    public static boolean isCurrentScreen(Screen screen) {
        //? if <26.2
        return Minecraft.getInstance().screen == screen;
        //? if >=26.2
        /*return Minecraft.getInstance().gui.screen() == screen;*/
    }

    /**
     * ASCII letters, digits and underscore only — mirrors the server's authoritative check
     * (AccountService.isValidUsername) so the field rejects an invalid nickname immediately
     * instead of round-tripping to the server first. Keeping this in sync matters: it's also
     * what stops a player from typing "✓" or other lookalike glyphs into their own nickname to
     * spoof the licensed badge drawn by {@link #badgeText}.
     */
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

    /** Renders as dots instead of the real characters, without changing what {@code getValue()} returns. */
    static void maskAsPassword(EditBox box) {
        // EditBox.setFormatter() was renamed to addFormatter() in 1.21.9 (multiple formatters
        // can now be chained).
        //? if <1.21.9
        box.setFormatter((text, cursor) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
        //? if >=1.21.9
        /*box.addFormatter((text, cursor) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));*/
    }

    /**
     * Suffix appended after a player name, colored instead of relying on a single icon glyph to
     * carry the meaning (green check vs. a plain "offline" tag rather than the previous
     * skull-and-crossbones, which read as a ban/danger warning rather than "no Mojang license").
     */
    static String badgeText(boolean licensed) {
        return licensed ? " ✓" : " " + Component.translatable("peercraft.gui.common.unlicensed_badge").getString();
    }

    static int badgeColor(boolean licensed) {
        return licensed ? TEXT_SUCCESS : TEXT_MUTED;
    }

    // GuiGraphics was renamed to GuiGraphicsExtractor in 26.1 and the immediate-mode draw methods
    // (drawString/drawCenteredString) became text/centeredText as the screen render pipeline moved
    // to render-state extraction. The call shapes are otherwise identical.
    /** Draws {@code name} left-aligned at {@code x}, followed by its badge. Returns the x position right after the badge, for drawing more text on the same line. */
    //? if <26.1 {
    static int drawNameWithBadge(GuiGraphics graphics, Font font, String name, boolean licensed, int x, int y, int nameColor) {
        graphics.drawString(font, name, x, y, nameColor, false);
        int badgeX = x + font.width(name);
        String badge = badgeText(licensed);
        graphics.drawString(font, badge, badgeX, y, badgeColor(licensed), false);
        return badgeX + font.width(badge);
    }

    /** Draws {@code name} + badge centered as one unit around {@code centerX}. */
    static void drawNameWithBadgeCentered(GuiGraphics graphics, Font font, String name, boolean licensed, int centerX, int y, int nameColor) {
        String badge = badgeText(licensed);
        int totalWidth = font.width(name) + font.width(badge);
        drawNameWithBadge(graphics, font, name, licensed, centerX - totalWidth / 2, y, nameColor);
    }
    //?} else {
    /*static int drawNameWithBadge(GuiGraphicsExtractor graphics, Font font, String name, boolean licensed, int x, int y, int nameColor) {
        graphics.text(font, name, x, y, nameColor, false);
        int badgeX = x + font.width(name);
        String badge = badgeText(licensed);
        graphics.text(font, badge, badgeX, y, badgeColor(licensed), false);
        return badgeX + font.width(badge);
    }

    static void drawNameWithBadgeCentered(GuiGraphicsExtractor graphics, Font font, String name, boolean licensed, int centerX, int y, int nameColor) {
        String badge = badgeText(licensed);
        int totalWidth = font.width(name) + font.width(badge);
        drawNameWithBadge(graphics, font, name, licensed, centerX - totalWidth / 2, y, nameColor);
    }*/
    //?}

    /**
     * A small square button using a plain vanilla-styled glyph (same look as the "▶" join-by-code
     * button next to it) instead of a custom-rendered icon — a pasted-on player-face texture read
     * as visually inconsistent with the rest of the vanilla button row.
     */
    static Button squareGlyphButton(int x, int y, int size, String glyph, String tooltipText, Button.OnPress onPress) {
        return Button.builder(Component.literal(glyph), onPress)
                .bounds(x, y, size, size)
                .tooltip(Tooltip.create(Component.literal(tooltipText)))
                .build();
    }
}
