package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.util.FormattedCharSequence;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../PeerCraftUi.java}. Same colour scheme and
 * helpers, on the 1.16.5 API: {@code GuiGraphics} → static {@code GuiComponent} draw calls
 * taking a {@link PoseStack}, {@code Component.translatable} → {@code TranslatableComponent},
 * {@code String.repeat} → {@link #repeat}, and {@code squareGlyphButton} drops its tooltip
 * (1.16.5 has no {@code Tooltip} object and the button row it sits in never carried one).
 */
public final class PeerCraftUi {

    static final int TEXT_TITLE = 0xFFFFFFFF;
    static final int TEXT_MUTED = 0xFFAAAAAA;
    static final int TEXT_ERROR = 0xFFFF5555;
    static final int TEXT_SUCCESS = 0xFF55FF55;
    static final int TEXT_ACCENT = 0xFFFFD966;

    private PeerCraftUi() {
    }

    /** Greedy word-wrap of {@code text} to lines no wider than {@code maxWidth} px. Matches src/main's {@code PeerCraftUi.wrap}. */
    public static java.util.List<String> wrap(Font font, String text, int maxWidth) {
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

    /** "12.3 MB" / "512 KB" / "900 B" — matches src/main's {@code PeerCraftUi.humanSize}. */
    public static String humanSize(long b) {
        if (b < 1024) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024) return String.format("%.0f KB", kb);
        return String.format("%.1f MB", kb / 1024.0);
    }

    /** 1.16.5 keeps {@code Minecraft.setScreen} — no {@code Minecraft.gui} indirection yet. */
    public static void setScreen(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }

    public static boolean isCurrentScreen(Screen screen) {
        return Minecraft.getInstance().screen == screen;
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

    /**
     * 1.16.5's {@link EditBox#setSuggestion} paints the hint unconditionally, right after the
     * typed text — unlike modern {@code EditBox.setHint}, which only shows while the box is
     * empty. This wires it up to behave like a real placeholder: the hint shows while the box
     * is empty and is cleared the instant anything is typed (and restored if it's emptied
     * again). Use instead of a bare {@code setSuggestion} for boxes that don't set a responder
     * of their own.
     */
    static void placeholder(EditBox box, String hint) {
        box.setSuggestion(box.getValue().isEmpty() ? hint : "");
        box.setResponder(value -> box.setSuggestion(value.isEmpty() ? hint : ""));
    }

    /** Renders as dots instead of the real characters, without changing what {@code getValue()} returns. */
    static void maskAsPassword(EditBox box) {
        box.setFormatter((text, cursor) -> FormattedCharSequence.forward(repeat("•", text.length()), Style.EMPTY));
    }

    /** Java 8 stand-in for {@code String.repeat(int)}. */
    static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder(s.length() * Math.max(0, count));
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    static String badgeText(boolean licensed) {
        return licensed ? " ✓" : " " + new TranslatableComponent("peercraft.gui.common.unlicensed_badge").getString();
    }

    static int badgeColor(boolean licensed) {
        return licensed ? TEXT_SUCCESS : TEXT_MUTED;
    }

    /** Draws {@code name} left-aligned at {@code x}, followed by its badge. Returns the x position right after the badge. */
    static int drawNameWithBadge(PoseStack poseStack, Font font, String name, boolean licensed, int x, int y, int nameColor) {
        GuiComponent.drawString(poseStack, font, name, x, y, nameColor);
        int badgeX = x + font.width(name);
        String badge = badgeText(licensed);
        GuiComponent.drawString(poseStack, font, badge, badgeX, y, badgeColor(licensed));
        return badgeX + font.width(badge);
    }

    /** Draws {@code name} + badge centered as one unit around {@code centerX}. */
    static void drawNameWithBadgeCentered(PoseStack poseStack, Font font, String name, boolean licensed, int centerX, int y, int nameColor) {
        String badge = badgeText(licensed);
        int totalWidth = font.width(name) + font.width(badge);
        drawNameWithBadge(poseStack, font, name, licensed, centerX - totalWidth / 2, y, nameColor);
    }

    /** A small square glyph button. 1.16.5 has no {@code Tooltip} object, so {@code tooltipText} is unused here. */
    static Button squareGlyphButton(int x, int y, int size, String glyph, String tooltipText, Button.OnPress onPress) {
        return new Button(x, y, size, size, new TextComponent(glyph), onPress);
    }
}
