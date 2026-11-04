package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.peercraft.client.theme.SteampunkPalette;
import net.peercraft.client.update.SelfUpdateInstaller;
import net.peercraft.client.update.UpdateChecker;
//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/

import java.util.List;

/**
 * Small "new PeerCraft version" plate in the title screen's bottom-right corner. Added on every
 * title-screen init but inert (inactive, draws nothing) until {@link UpdateChecker#available()}
 * reports a newer release, so a result that arrives while the menu is open still shows up.
 * It lays itself out each frame to stay clear of the menu buttons and hides when there is no room.
 */
public final class UpdateNoticeWidget extends Button {
    private static final int MAX_WIDTH = 180;
    private static final int MIN_WIDTH = 96;
    private static final int MARGIN = 4;
    private static final int BOTTOM_GAP = 12; // keeps vanilla's copyright line readable
    private static final int CLOSE_SIZE = 9;
    private static final long FADE_MILLIS = 400L;

    private final Screen parent;
    private boolean closeHit;
    private List<FormattedCharSequence> lines = java.util.Collections.emptyList();
    private int titleLines;
    private UpdateChecker.UpdateInfo shown;
    private boolean closable;

    public UpdateNoticeWidget(Screen parent) {
        super(0, 0, 0, 0, Component.empty(), button -> ((UpdateNoticeWidget) button).press(), DEFAULT_NARRATION);
        this.parent = parent;
        this.active = false;
    }

    private void press() {
        UpdateChecker.UpdateInfo info = this.shown;
        if (info == null) return;
        if (this.closeHit && this.closable) {
            UpdateChecker.dismiss();
            this.active = false;
            return;
        }
        PeerCraftUi.setScreen(Minecraft.getInstance(), new UpdateConfirmScreen(this.parent, info));
    }

    /** Recomputes bounds and text; false when the plate should stay hidden this frame. */
    private boolean layout(Font font) {
        // A prepared install stays on screen (without the close cross) until the game restarts.
        boolean ready = SelfUpdateInstaller.state() == SelfUpdateInstaller.State.READY_RESTART;
        UpdateChecker.UpdateInfo info = ready ? UpdateChecker.latestResult() : UpdateChecker.available();
        this.shown = info;
        this.closable = !ready;
        if (info == null) return false;
        Component title = ready
                ? Component.translatable("peercraft.update.ready_title", SelfUpdateInstaller.readyVersion())
                : Component.translatable("peercraft.update.title");
        Component versions = ready
                ? Component.translatable("peercraft.update.ready_hint")
                : Component.translatable("peercraft.update.versions", info.current, info.latest);
        setMessage(Component.empty().append(title).append(". ").append(versions));

        int screenW = this.parent.width;
        int screenH = this.parent.height;
        int w = Math.min(MAX_WIDTH, screenW - 2 * MARGIN);
        for (int attempt = 0; attempt < 2 && w >= MIN_WIDTH; attempt++) {
            int textWidth = w - 16 - CLOSE_SIZE;
            List<FormattedCharSequence> titleWrapped = font.split(title, textWidth);
            this.titleLines = Math.min(2, titleWrapped.size());
            this.lines = new java.util.ArrayList<>(titleWrapped.subList(0, this.titleLines));
            this.lines.addAll(font.split(versions, w - 16));
            if (this.lines.size() > 4) this.lines = this.lines.subList(0, 4);
            int h = 8 + this.lines.size() * (font.lineHeight + 1);
            int x = screenW - MARGIN - w;
            int y = screenH - BOTTOM_GAP - h;
            int blockerRight = overlappingRight(x, y, w, h);
            if (blockerRight == Integer.MIN_VALUE) {
                setX(x);
                setY(y);
                setWidth(w);
                this.height = h;
                return true;
            }
            w = screenW - MARGIN - (blockerRight + MARGIN);
        }
        return false;
    }

    /** Rightmost edge of any other widget the rectangle would cover, or MIN_VALUE when it is clear. */
    private int overlappingRight(int x, int y, int w, int h) {
        int right = Integer.MIN_VALUE;
        for (GuiEventListener child : this.parent.children()) {
            if (child == this || !(child instanceof AbstractWidget widget) || !widget.visible) continue;
            if (widget.getX() < x + w && widget.getX() + widget.getWidth() > x
                    && widget.getY() < y + h && widget.getY() + widget.getHeight() > y) {
                right = Math.max(right, widget.getX() + widget.getWidth());
            }
        }
        return right;
    }

    private boolean overClose(double mouseX, double mouseY) {
        if (!this.closable) return false;
        int cx = getX() + getWidth() - CLOSE_SIZE - 3;
        int cy = getY() + 3;
        return mouseX >= cx && mouseX < cx + CLOSE_SIZE && mouseY >= cy && mouseY < cy + CLOSE_SIZE;
    }

    private static int withAlpha(int color, float alpha) {
        return ((int) (((color >>> 24) & 0xFF) * alpha) << 24) | (color & 0xFFFFFF);
    }

    //? if <1.21.9 {
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        this.closeHit = overClose(mouseX, mouseY);
        return super.mouseClicked(mouseX, mouseY, button);
    }
    //?} else {
    /*@Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        this.closeHit = overClose(event.x(), event.y());
        return super.mouseClicked(event, doubleClick);
    }*/
    //?}

    @Override
    //? if <1.21.11
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //? if >=1.21.11 && <26.1
    /*protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {*/
    //? if >=26.1
    /*protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {*/
        Font font = Minecraft.getInstance().font;
        boolean visibleNow = layout(font);
        this.active = visibleNow;
        if (!visibleNow) return;
        float fade = Math.min(1f, (System.currentTimeMillis() - this.shown.foundAtMillis) / (float) FADE_MILLIS);
        boolean hover = isHovered();
        boolean closeHover = hover && overClose(mouseX, mouseY);
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();

        graphics.fill(x, y, x + w, y + h, withAlpha(hover && !closeHover ? SteampunkPalette.BORDER_HOVER : SteampunkPalette.BORDER, fade));
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, withAlpha(SteampunkPalette.PANEL, fade * 0.94f));
        graphics.fill(x + 1, y + 1, x + 3, y + h - 1, withAlpha(SteampunkPalette.ACCENT, fade));
        // Text with near-zero alpha is drawn opaque (or skipped) by vanilla; wait for the frame to settle.
        if (fade < 0.25f) return;

        int textY = y + 5;
        for (int i = 0; i < this.lines.size(); i++) {
            int color = i < this.titleLines ? SteampunkPalette.ACCENT : SteampunkPalette.MUTED;
            //? if <26.1
            graphics.drawString(font, this.lines.get(i), x + 8, textY, withAlpha(color, fade), false);
            //? if >=26.1
            /*graphics.text(font, this.lines.get(i), x + 8, textY, withAlpha(color, fade), false);*/
            textY += font.lineHeight + 1;
        }
        if (!this.closable) return;
        int cx = x + w - CLOSE_SIZE - 3;
        int closeColor = withAlpha(closeHover ? SteampunkPalette.TEXT : SteampunkPalette.MUTED, fade);
        //? if <26.1
        graphics.drawString(font, "×", cx + (CLOSE_SIZE - font.width("×")) / 2 + 1, y + 3, closeColor, false);
        //? if >=26.1
        /*graphics.text(font, "×", cx + (CLOSE_SIZE - font.width("×")) / 2 + 1, y + 3, closeColor, false);*/
    }
}
