package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;

import java.util.List;

/** 1.16.5 twin: a scrollable list of named rows (one action each) inside the compact in-game dialog. */
abstract class PeerCraftRowsScreen extends PeerCraftDialogScreen {
    private static final int ROW_HEIGHT = 24;

    protected final Screen parent;
    protected Component statusMessage = TextComponent.EMPTY;
    protected int statusColor = PeerCraftUi.TEXT_MUTED;
    private int scroll;

    /** One list entry; {@code action} may be null for a row without a button. */
    protected static final class Row {
        final String name; final boolean licensed, badge; final Component detail; final int detailColor; final RowAction action;
        Row(String name, boolean licensed, boolean badge, Component detail, int detailColor, RowAction action) {
            this.name = name; this.licensed = licensed; this.badge = badge; this.detail = detail; this.detailColor = detailColor; this.action = action;
        }
    }

    protected static final class RowAction {
        final Component label; final boolean primary, active; final Runnable onPress;
        RowAction(Component label, boolean primary, boolean active, Runnable onPress) {
            this.label = label; this.primary = primary; this.active = active; this.onPress = onPress;
        }
    }

    protected static final class Footer {
        final Component label; final boolean primary, active; final Runnable onPress;
        Footer(Component label, boolean primary, boolean active, Runnable onPress) {
            this.label = label; this.primary = primary; this.active = active; this.onPress = onPress;
        }
    }

    protected PeerCraftRowsScreen(Screen parent, Component title) {
        super(title, 360);
        this.parent = parent;
    }

    protected abstract List<Row> rows();

    protected abstract List<Footer> footer();

    @Override
    protected void init() {
        setFocused(null);
        buttons.clear(); children.clear();
        super.init();
        List<Row> rows = rows();
        scroll = Math.max(0, Math.min(scroll, maximumScroll(rows)));
        int shown = Math.min(rows.size() - scroll, visibleRows());
        for (int i = 0; i < shown; i++) {
            final RowAction action = rows.get(scroll + i).action;
            if (action == null) continue;
            Btn.Builder builder = Btn.builder(action.label, b -> action.onPress.run())
                    .bounds(actionX(), rowsTop() + i * ROW_HEIGHT + 1, actionWidth(), 20);
            Button button = addButton((action.primary ? builder.primary() : builder).build());
            button.active = action.active;
        }
        List<Footer> footer = footer();
        int gap = 4;
        int each = Math.max(1, (dialog.contentWidth() - gap * (footer.size() - 1)) / Math.max(1, footer.size()));
        for (int i = 0; i < footer.size(); i++) {
            final Footer f = footer.get(i);
            Btn.Builder builder = Btn.builder(f.label, b -> f.onPress.run())
                    .bounds(dialog.contentX() + i * (each + gap), footerTop(), each, dialog.buttonHeight());
            Button button = addButton((f.primary ? builder.primary() : builder).build());
            button.active = f.active;
        }
    }

    protected void refresh() {
        if (stillOnThisScreen()) init();
    }

    protected void setStatus(Component message, int color) {
        this.statusMessage = message;
        this.statusColor = color;
    }

    protected void runOnClientThread(Runnable action) {
        Minecraft.getInstance().execute(action);
    }

    protected boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    @Override
    public void onClose() {
        PeerCraftUi.setScreen(this.minecraft, this.parent);
    }

    private int footerTop() { return dialog.top + dialog.height - 10 - dialog.buttonHeight(); }
    private int rowsTop() { return dialog.contentTop(); }
    private int statusY() { return footerTop() - 14; }
    private int visibleRows() { return Math.max(0, (statusY() - 4 - rowsTop()) / ROW_HEIGHT); }
    private int actionWidth() { return Math.min(96, Math.max(40, dialog.contentWidth() / 3)); }
    private int actionX() { return dialog.contentX() + dialog.contentWidth() - actionWidth() - 6; }
    private int maximumScroll(List<Row> rows) { return Math.max(0, rows.size() - visibleRows()); }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (delta != 0 && dialog != null && y >= rowsTop() && y < statusY() && maximumScroll(rows()) > 0) {
            scroll = Math.max(0, Math.min(maximumScroll(rows()), scroll - (int) Math.signum(delta)));
            init();
            return true;
        }
        return super.mouseScrolled(x, y, delta);
    }

    private String fitted(String value, int width) {
        if (this.font.width(value) <= width) return value;
        return this.font.plainSubstrByWidth(value, Math.max(1, width - this.font.width("…"))) + "…";
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        List<Row> rows = rows();
        int shown = Math.min(rows.size() - scroll, visibleRows());
        int textWidth = actionX() - dialog.contentX() - 6;
        for (int i = 0; i < shown; i++) {
            Row row = rows.get(scroll + i);
            int y = rowsTop() + i * ROW_HEIGHT;
            String name = fitted(row.name, textWidth - (row.badge ? this.font.width(PeerCraftUi.badgeText(row.licensed)) : 0));
            if (row.badge) PeerCraftUi.drawNameWithBadge(pose, this.font, name, row.licensed, dialog.contentX(), y + 2, PeerCraftUi.TEXT_TITLE);
            else GuiComponent.drawString(pose, this.font, name, dialog.contentX(), y + 2, PeerCraftUi.TEXT_TITLE);
            GuiComponent.drawString(pose, this.font, fitted(row.detail.getString(), textWidth), dialog.contentX(), y + 12, row.detailColor);
        }
        if (maximumScroll(rows) > 0 && visibleRows() > 0) {
            int track = visibleRows() * ROW_HEIGHT, thumb = Math.max(10, track * visibleRows() / rows.size());
            int thumbY = rowsTop() + (track - thumb) * scroll / maximumScroll(rows);
            int x = dialog.contentX() + dialog.contentWidth() - 3;
            GuiComponent.fill(pose, x, rowsTop(), x + 3, rowsTop() + track, 0xFF2C241B);
            GuiComponent.fill(pose, x, thumbY, x + 3, thumbY + thumb, 0xFFBB8B4B);
        }
        GuiComponent.drawCenteredString(pose, this.font, this.statusMessage, this.width / 2, statusY(), this.statusColor);
        super.render(pose, mouseX, mouseY, partialTick);
    }
}
