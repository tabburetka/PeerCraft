package net.peercraft.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Shared look-and-feel for the PeerCraft screens: a status-message color scheme that actually
 * distinguishes loading/error/success (previously every message — including errors — was the
 * same yellow), plus a couple of small widget helpers reused across the account/friends screens.
 */
final class PeerCraftUi {

    static final int TEXT_TITLE = 0xFFFFFF;
    static final int TEXT_MUTED = 0xAAAAAA;
    static final int TEXT_ERROR = 0xFF5555;
    static final int TEXT_SUCCESS = 0x55FF55;
    static final int TEXT_ACCENT = 0xFFD966;

    private PeerCraftUi() {
    }

    /** Renders as dots instead of the real characters, without changing what {@code getValue()} returns. */
    static void maskAsPassword(EditBox box) {
        box.setFormatter((text, cursor) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
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

    /** Draws {@code name} left-aligned at {@code x}, followed by its badge. Returns the x position right after the badge, for drawing more text on the same line. */
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
