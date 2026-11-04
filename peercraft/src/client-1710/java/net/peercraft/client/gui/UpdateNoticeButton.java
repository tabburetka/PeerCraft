package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.peercraft.client.theme.SteampunkPalette;
import net.peercraft.client.update.UpdateChecker;
import org.lwjgl.opengl.GL11;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 1.7.10 twin of {@code src/main/.../UpdateNoticeWidget.java}: the bottom-right "new PeerCraft
 * version" plate on {@code GuiMainMenu}. Added on every init but disabled and undrawn until a
 * newer release is known; clicks arrive through {@code PeerCraftScreenEvents.onActionPre}.
 */
class UpdateNoticeButton extends GuiButton {
    private static final int MAX_WIDTH = 180;
    private static final int MIN_WIDTH = 96;
    private static final int MARGIN = 4;
    private static final int BOTTOM_GAP = 12;
    private static final int CLOSE_SIZE = 9;
    private static final long FADE_MILLIS = 400L;

    private final GuiScreen parent;
    private final List<GuiButton> siblings;
    private boolean closeHit;
    private List<String> lines = Collections.emptyList();
    private int titleLines;
    private UpdateChecker.UpdateInfo shown;

    UpdateNoticeButton(GuiScreen parent, List<GuiButton> siblings) {
        super(9890, 0, 0, 0, 0, "");
        this.parent = parent;
        this.siblings = siblings;
        this.enabled = false;
    }

    void press() {
        UpdateChecker.UpdateInfo info = this.shown;
        if (info == null) return;
        if (this.closeHit) {
            UpdateChecker.dismiss();
            this.enabled = false;
            return;
        }
        // Same as the Feedback/Donate links in PeerCraftMultiplayerScreen: straight to the OS.
        try {
            org.lwjgl.Sys.openURL(info.url);
        } catch (Throwable t) {
            try {
                java.awt.Desktop.getDesktop().browse(new URI(info.url));
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean layout(FontRenderer font) {
        UpdateChecker.UpdateInfo info = UpdateChecker.available();
        this.shown = info;
        if (info == null) return false;
        String title = PeerCraftLang.tr("peercraft.update.title");
        String versions = PeerCraftLang.tr("peercraft.update.versions", info.current, info.latest);
        this.displayString = title + ". " + versions;

        int w = Math.min(MAX_WIDTH, this.parent.width - 2 * MARGIN);
        for (int attempt = 0; attempt < 2 && w >= MIN_WIDTH; attempt++) {
            @SuppressWarnings("unchecked") List<String> titleWrapped = font.listFormattedStringToWidth(title, w - 16 - CLOSE_SIZE);
            this.titleLines = Math.min(2, titleWrapped.size());
            List<String> all = new ArrayList<String>(titleWrapped.subList(0, this.titleLines));
            all.addAll(font.listFormattedStringToWidth(versions, w - 16));
            this.lines = all.size() > 4 ? all.subList(0, 4) : all;
            int h = 8 + this.lines.size() * (font.FONT_HEIGHT + 1);
            int left = this.parent.width - MARGIN - w;
            int top = this.parent.height - BOTTOM_GAP - h;
            int blockerRight = overlappingRight(left, top, w, h);
            if (blockerRight == Integer.MIN_VALUE) {
                this.xPosition = left;
                this.yPosition = top;
                this.width = w;
                this.height = h;
                return true;
            }
            w = this.parent.width - MARGIN - (blockerRight + MARGIN);
        }
        return false;
    }

    private int overlappingRight(int left, int top, int w, int h) {
        int right = Integer.MIN_VALUE;
        for (GuiButton b : this.siblings) {
            if (b == this || !b.visible) continue;
            if (b.xPosition < left + w && b.xPosition + b.width > left && b.yPosition < top + h && b.yPosition + b.height > top) {
                right = Math.max(right, b.xPosition + b.width);
            }
        }
        return right;
    }

    private boolean overClose(int mouseX, int mouseY) {
        int cx = this.xPosition + this.width - CLOSE_SIZE - 3;
        int cy = this.yPosition + 3;
        return mouseX >= cx && mouseX < cx + CLOSE_SIZE && mouseY >= cy && mouseY < cy + CLOSE_SIZE;
    }

    private static int withAlpha(int color, float alpha) {
        return ((int) (((color >>> 24) & 0xFF) * alpha) << 24) | (color & 0xFFFFFF);
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        boolean hit = super.mousePressed(mc, mouseX, mouseY);
        if (hit) this.closeHit = overClose(mouseX, mouseY);
        return hit;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) return;
        FontRenderer font = mc.fontRenderer;
        boolean visibleNow = layout(font);
        this.enabled = visibleNow;
        if (!visibleNow) return;
        float fade = Math.min(1f, (System.currentTimeMillis() - this.shown.foundAtMillis) / (float) FADE_MILLIS);
        boolean hover = mouseX >= this.xPosition && mouseX < this.xPosition + this.width && mouseY >= this.yPosition && mouseY < this.yPosition + this.height;
        boolean closeHover = hover && overClose(mouseX, mouseY);
        int x = this.xPosition, y = this.yPosition, w = this.width, h = this.height;

        Gui.drawRect(x, y, x + w, y + h, withAlpha(hover && !closeHover ? SteampunkPalette.BORDER_HOVER : SteampunkPalette.BORDER, fade));
        Gui.drawRect(x + 1, y + 1, x + w - 1, y + h - 1, withAlpha(SteampunkPalette.PANEL, fade * 0.94f));
        Gui.drawRect(x + 1, y + 1, x + 3, y + h - 1, withAlpha(SteampunkPalette.ACCENT, fade));
        if (fade < 0.25f) return;

        GL11.glEnable(GL11.GL_BLEND);
        int textY = y + 5;
        for (int i = 0; i < this.lines.size(); i++) {
            int color = i < this.titleLines ? SteampunkPalette.ACCENT : SteampunkPalette.MUTED;
            font.drawString(this.lines.get(i), x + 8, textY, withAlpha(color, fade));
            textY += font.FONT_HEIGHT + 1;
        }
        int cx = x + w - CLOSE_SIZE - 3;
        font.drawString("×", cx + (CLOSE_SIZE - font.getStringWidth("×")) / 2 + 1, y + 3,
                withAlpha(closeHover ? SteampunkPalette.TEXT : SteampunkPalette.MUTED, fade));
    }
}
