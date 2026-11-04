package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.peercraft.client.theme.SteampunkPalette;
import net.peercraft.client.update.SelfUpdateInstaller;
import net.peercraft.client.update.UpdateChecker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../UpdateNoticeWidget.java}: the bottom-right
 * "new PeerCraft version" plate on the title screen, inert until a newer release is known.
 */
public final class UpdateNoticeWidget extends Button {
    private static final int MAX_WIDTH = 180;
    private static final int MIN_WIDTH = 96;
    private static final int MARGIN = 4;
    private static final int BOTTOM_GAP = 12;
    private static final int CLOSE_SIZE = 9;
    private static final long FADE_MILLIS = 400L;

    private final Screen parent;
    private boolean closeHit;
    private List<FormattedCharSequence> lines = Collections.emptyList();
    private int titleLines;
    private UpdateChecker.UpdateInfo shown;
    private boolean closable;

    public UpdateNoticeWidget(Screen parent) {
        super(0, 0, 0, 0, TextComponent.EMPTY, button -> ((UpdateNoticeWidget) button).press());
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
        Minecraft.getInstance().setScreen(new UpdateConfirmScreen(this.parent, info));
    }

    private boolean layout(Font font) {
        // A prepared install stays on screen (without the close cross) until the game restarts.
        boolean ready = SelfUpdateInstaller.state() == SelfUpdateInstaller.State.READY_RESTART;
        UpdateChecker.UpdateInfo info = ready ? UpdateChecker.latestResult() : UpdateChecker.available();
        this.shown = info;
        this.closable = !ready;
        if (info == null) return false;
        TranslatableComponent title = ready
                ? new TranslatableComponent("peercraft.update.ready_title", SelfUpdateInstaller.readyVersion())
                : new TranslatableComponent("peercraft.update.title");
        TranslatableComponent versions = ready
                ? new TranslatableComponent("peercraft.update.ready_hint")
                : new TranslatableComponent("peercraft.update.versions", info.current, info.latest);
        setMessage(new TextComponent("").append(title).append(". ").append(versions));

        int w = Math.min(MAX_WIDTH, this.parent.width - 2 * MARGIN);
        for (int attempt = 0; attempt < 2 && w >= MIN_WIDTH; attempt++) {
            List<FormattedCharSequence> titleWrapped = font.split(title, w - 16 - CLOSE_SIZE);
            this.titleLines = Math.min(2, titleWrapped.size());
            List<FormattedCharSequence> all = new ArrayList<>(titleWrapped.subList(0, this.titleLines));
            all.addAll(font.split(versions, w - 16));
            this.lines = all.size() > 4 ? all.subList(0, 4) : all;
            int h = 8 + this.lines.size() * (font.lineHeight + 1);
            int x = this.parent.width - MARGIN - w;
            int y = this.parent.height - BOTTOM_GAP - h;
            int blockerRight = overlappingRight(x, y, w, h);
            if (blockerRight == Integer.MIN_VALUE) {
                this.x = x;
                this.y = y;
                this.width = w;
                this.height = h;
                return true;
            }
            w = this.parent.width - MARGIN - (blockerRight + MARGIN);
        }
        return false;
    }

    private int overlappingRight(int x, int y, int w, int h) {
        int right = Integer.MIN_VALUE;
        for (Object child : this.parent.children()) {
            if (child == this || !(child instanceof AbstractWidget)) continue;
            AbstractWidget widget = (AbstractWidget) child;
            if (!widget.visible) continue;
            if (widget.x < x + w && widget.x + widget.getWidth() > x
                    && widget.y < y + h && widget.y + widget.getHeight() > y) {
                right = Math.max(right, widget.x + widget.getWidth());
            }
        }
        return right;
    }

    private boolean overClose(double mouseX, double mouseY) {
        if (!this.closable) return false;
        int cx = this.x + this.width - CLOSE_SIZE - 3;
        int cy = this.y + 3;
        return mouseX >= cx && mouseX < cx + CLOSE_SIZE && mouseY >= cy && mouseY < cy + CLOSE_SIZE;
    }

    private static int withAlpha(int color, float alpha) {
        return ((int) (((color >>> 24) & 0xFF) * alpha) << 24) | (color & 0xFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        this.closeHit = overClose(mouseX, mouseY);
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void renderButton(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        boolean visibleNow = layout(font);
        this.active = visibleNow;
        if (!visibleNow) return;
        float fade = Math.min(1f, (System.currentTimeMillis() - this.shown.foundAtMillis) / (float) FADE_MILLIS);
        boolean hover = isHovered();
        boolean closeHover = hover && overClose(mouseX, mouseY);
        int x = this.x, y = this.y, w = this.width, h = this.height;

        GuiComponent.fill(pose, x, y, x + w, y + h, withAlpha(hover && !closeHover ? SteampunkPalette.BORDER_HOVER : SteampunkPalette.BORDER, fade));
        GuiComponent.fill(pose, x + 1, y + 1, x + w - 1, y + h - 1, withAlpha(SteampunkPalette.PANEL, fade * 0.94f));
        GuiComponent.fill(pose, x + 1, y + 1, x + 3, y + h - 1, withAlpha(SteampunkPalette.ACCENT, fade));
        if (fade < 0.25f) return;

        int textY = y + 5;
        for (int i = 0; i < this.lines.size(); i++) {
            int color = i < this.titleLines ? SteampunkPalette.ACCENT : SteampunkPalette.MUTED;
            font.draw(pose, this.lines.get(i), x + 8, textY, withAlpha(color, fade));
            textY += font.lineHeight + 1;
        }
        if (!this.closable) return;
        int cx = x + w - CLOSE_SIZE - 3;
        font.draw(pose, "×", cx + (CLOSE_SIZE - font.width("×")) / 2 + 1, y + 3,
                withAlpha(closeHover ? SteampunkPalette.TEXT : SteampunkPalette.MUTED, fade));
    }
}
