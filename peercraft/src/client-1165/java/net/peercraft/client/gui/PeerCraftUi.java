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
final class PeerCraftUi {

    static final int TEXT_TITLE = 0xFFFFFFFF;
    static final int TEXT_MUTED = 0xFFAAAAAA;
    static final int TEXT_ERROR = 0xFFFF5555;
    static final int TEXT_SUCCESS = 0xFF55FF55;
    static final int TEXT_ACCENT = 0xFFFFD966;

    private PeerCraftUi() {
    }

    /** 1.16.5 keeps {@code Minecraft.setScreen} — no {@code Minecraft.gui} indirection yet. */
    static void setScreen(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }

    static boolean isCurrentScreen(Screen screen) {
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
