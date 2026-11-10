package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** A scrollable list of named rows (one action each) inside the compact in-game dialog. */
abstract class PeerCraftRowsScreen extends PeerCraftDialogScreen {
    private static final int ROW_HEIGHT = 24;

    protected final Screen parent;
    protected Component statusMessage = Component.empty();
    protected int statusColor = PeerCraftUi.TEXT_MUTED;
    private int scroll;

    /** One list entry; {@code action} may be null for a row without a button. */
    protected record Row(String name, boolean licensed, boolean badge, Component detail, int detailColor, RowAction action) {
    }

    protected record RowAction(Component label, boolean primary, boolean active, Component tooltip, Runnable onPress) {
    }

    protected record Footer(Component label, boolean primary, boolean active, Runnable onPress) {
    }

    protected PeerCraftRowsScreen(Screen parent, Component title) {
        super(title);
        this.parent = parent;
    }

    protected abstract List<Row> rows();

    protected abstract List<Footer> footer();

    @Override
    protected void init() {
        clearWidgets();
        super.init();
        this.dialog = new SteampunkDialog(this.width, this.height, 360, this.title);
        List<Row> rows = rows();
        scroll = Math.max(0, Math.min(scroll, maximumScroll(rows)));
        int shown = Math.min(rows.size() - scroll, visibleRows());
        for (int i = 0; i < shown; i++) {
            RowAction action = rows.get(scroll + i).action();
            if (action == null) continue;
            Button button = SteampunkSettingsTheme.action(actionX(), rowsTop() + i * ROW_HEIGHT + 1, actionWidth(), 20,
                    action.label(), b -> action.onPress().run(), action.primary());
            button.active = action.active();
            if (action.tooltip() != null) button.setTooltip(Tooltip.create(action.tooltip()));
            addRenderableWidget(button);
        }
        List<Footer> footer = footer();
        int gap = 4;
        int each = Math.max(1, (dialog.contentWidth() - gap * (footer.size() - 1)) / Math.max(1, footer.size()));
        for (int i = 0; i < footer.size(); i++) {
            Footer f = footer.get(i);
            Button button = SteampunkSettingsTheme.action(dialog.contentX() + i * (each + gap), footerTop(), each,
                    dialog.buttonHeight(), f.label(), b -> f.onPress().run(), f.primary());
            button.active = f.active();
            addRenderableWidget(button);
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
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0 && dialog != null && y >= rowsTop() && y < statusY() && maximumScroll(rows()) > 0) {
            scroll = Math.max(0, Math.min(maximumScroll(rows()), scroll - (int) Math.signum(vertical)));
            init();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    private String fitted(String value, int width) {
        if (this.font.width(value) <= width) return value;
        return this.font.plainSubstrByWidth(value, Math.max(1, width - this.font.width("…"))) + "…";
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        List<Row> rows = rows();
        int shown = Math.min(rows.size() - scroll, visibleRows());
        int textWidth = actionX() - dialog.contentX() - 6;
        for (int i = 0; i < shown; i++) {
            Row row = rows.get(scroll + i);
            int y = rowsTop() + i * ROW_HEIGHT;
            String name = fitted(row.name(), textWidth - (row.badge() ? this.font.width(PeerCraftUi.badgeText(row.licensed())) : 0));
            if (row.badge()) PeerCraftUi.drawNameWithBadge(graphics, this.font, name, row.licensed(), dialog.contentX(), y + 2, PeerCraftUi.TEXT_TITLE);
            else graphics.drawString(this.font, name, dialog.contentX(), y + 2, PeerCraftUi.TEXT_TITLE, false);
            graphics.drawString(this.font, fitted(row.detail().getString(), textWidth), dialog.contentX(), y + 12, row.detailColor(), false);
        }
        if (maximumScroll(rows) > 0 && visibleRows() > 0) {
            int track = visibleRows() * ROW_HEIGHT, thumb = Math.max(10, track * visibleRows() / rows.size());
            int thumbY = rowsTop() + (track - thumb) * scroll / maximumScroll(rows);
            int x = dialog.contentX() + dialog.contentWidth() - 3;
            graphics.fill(x, rowsTop(), x + 3, rowsTop() + track, 0xFF2C241B);
            graphics.fill(x, thumbY, x + 3, thumbY + thumb, 0xFFBB8B4B);
        }
        graphics.drawCenteredString(this.font, this.statusMessage, this.width / 2, statusY(), this.statusColor);
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        List<Row> rows = rows();
        int shown = Math.min(rows.size() - scroll, visibleRows());
        int textWidth = actionX() - dialog.contentX() - 6;
        for (int i = 0; i < shown; i++) {
            Row row = rows.get(scroll + i);
            int y = rowsTop() + i * ROW_HEIGHT;
            String name = fitted(row.name(), textWidth - (row.badge() ? this.font.width(PeerCraftUi.badgeText(row.licensed())) : 0));
            if (row.badge()) PeerCraftUi.drawNameWithBadge(graphics, this.font, name, row.licensed(), dialog.contentX(), y + 2, PeerCraftUi.TEXT_TITLE);
            else graphics.text(this.font, name, dialog.contentX(), y + 2, PeerCraftUi.TEXT_TITLE, false);
            graphics.text(this.font, fitted(row.detail().getString(), textWidth), dialog.contentX(), y + 12, row.detailColor(), false);
        }
        if (maximumScroll(rows) > 0 && visibleRows() > 0) {
            int track = visibleRows() * ROW_HEIGHT, thumb = Math.max(10, track * visibleRows() / rows.size());
            int thumbY = rowsTop() + (track - thumb) * scroll / maximumScroll(rows);
            int x = dialog.contentX() + dialog.contentWidth() - 3;
            graphics.fill(x, rowsTop(), x + 3, rowsTop() + track, 0xFF2C241B);
            graphics.fill(x, thumbY, x + 3, thumbY + thumb, 0xFFBB8B4B);
        }
        graphics.centeredText(this.font, this.statusMessage, this.width / 2, statusY(), this.statusColor);
    }*/
    //?}
}
