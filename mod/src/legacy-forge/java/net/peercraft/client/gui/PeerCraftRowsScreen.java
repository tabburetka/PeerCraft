package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.peercraft.client.theme.SteampunkPalette;

//? if >=1.12 {
import java.io.IOException;
//?}
import java.util.ArrayList;
import java.util.List;

//? if >=1.12 {
/** Forge 1.12.2 twin: a scrollable list of named rows (one action each) inside the compact in-game dialog. */
//?} else {
//~ /** Forge 1.7.10 twin: a scrollable list of named rows (one action each) inside the compact in-game dialog. */
//?}
abstract class PeerCraftRowsScreen extends PeerCraftDialogScreen {
    private static final int ROW_HEIGHT = 24;

    protected final GuiScreen parent;
    protected String statusMessage = "";
    protected int statusColor = PeerCraftUi.TEXT_MUTED;
    private int scroll;

    /** One list entry; {@code action} may be null for a row without a button. */
    protected static final class Row {
        final String name, detail; final boolean licensed, badge; final int detailColor; final RowAction action;
        Row(String name, boolean licensed, boolean badge, String detail, int detailColor, RowAction action) {
            this.name = name; this.licensed = licensed; this.badge = badge; this.detail = detail; this.detailColor = detailColor; this.action = action;
        }
    }

    protected static final class RowAction {
        final String label; final boolean primary, active; final Runnable onPress;
        RowAction(String label, boolean primary, boolean active, Runnable onPress) {
            this.label = label; this.primary = primary; this.active = active; this.onPress = onPress;
        }
    }

    protected static final class Footer {
        final String label; final boolean primary, active; final Runnable onPress;
        Footer(String label, boolean primary, boolean active, Runnable onPress) {
            this.label = label; this.primary = primary; this.active = active; this.onPress = onPress;
        }
    }

    protected PeerCraftRowsScreen(GuiScreen parent, String title) {
        super(title, 360);
        this.parent = parent;
    }

    protected abstract List<Row> rows();

    protected abstract List<Footer> footer();

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();
        List<Row> rows = rows();
        scroll = Math.max(0, Math.min(scroll, maximumScroll(rows)));
        int shown = Math.min(rows.size() - scroll, visibleRows());
        for (int i = 0; i < shown; i++) {
            RowAction action = rows.get(scroll + i).action;
            if (action == null) continue;
            IdButton.Builder builder = IdButton.builder(action.label, action.onPress)
                    .bounds(actionX(), rowsTop() + i * ROW_HEIGHT + 1, actionWidth(), 20);
            IdButton button = (action.primary ? builder.primary() : builder).build();
            button.enabled = action.active;
            this.buttonList.add(button);
        }
        List<Footer> footer = footer();
        int gap = 4;
        int each = Math.max(1, (dialog.contentWidth() - gap * (footer.size() - 1)) / Math.max(1, footer.size()));
        for (int i = 0; i < footer.size(); i++) {
            Footer f = footer.get(i);
            IdButton.Builder builder = IdButton.builder(f.label, f.onPress)
                    .bounds(dialog.contentX() + i * (each + gap), footerTop(), each, dialog.buttonHeight());
            IdButton button = (f.primary ? builder.primary() : builder).build();
            button.enabled = f.active;
            this.buttonList.add(button);
        }
    }

    protected void refresh() {
        if (stillOnThisScreen()) initGui();
    }

    protected void setStatus(String message, int color) {
        this.statusMessage = message;
        this.statusColor = color;
    }

    protected void runOnClientThread(Runnable action) {
        //? if >=1.12 {
        Minecraft.getMinecraft().addScheduledTask(action);
        //?} else {
//~         Minecraft.getMinecraft().func_152344_a(action);
        //?}
    }

    protected boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    protected void onClose() {
        PeerCraftUi.setScreen(this.mc, this.parent);
    }

    private int footerTop() { return dialog.top + dialog.height - 10 - dialog.buttonHeight(); }
    private int rowsTop() { return dialog.contentTop(); }
    private int statusY() { return footerTop() - 14; }
    private int visibleRows() { return Math.max(0, (statusY() - 4 - rowsTop()) / ROW_HEIGHT); }
    private int actionWidth() { return Math.min(96, Math.max(40, dialog.contentWidth() / 3)); }
    private int actionX() { return dialog.contentX() + dialog.contentWidth() - actionWidth() - 6; }
    private int maximumScroll(List<Row> rows) { return Math.max(0, rows.size() - visibleRows()); }

    /** Presses at most one button: a handler may rebuild the list under the cursor. */
    @Override
    //? if >=1.12 {
    protected void mouseClicked(int x, int y, int button) throws IOException {
        if (button == 0) for (GuiButton b : new ArrayList<>(this.buttonList)) {
    //?} else {
//~     protected void mouseClicked(int x, int y, int button) {
//~         if (button == 0) for (Object entry : new ArrayList<Object>(this.buttonList)) {
//~             GuiButton b = (GuiButton) entry;
    //?}
            if (b.mousePressed(this.mc, x, y)) {
                //? if >=1.12 {
                b.playPressSound(this.mc.getSoundHandler());
                //?} else {
//~                 b.func_146113_a(this.mc.getSoundHandler());
                //?}
                actionPerformed(b);
                return;
            }
        }
        super.mouseClicked(x, y, button);
    }

    @Override
    //? if >=1.12 {
    protected void actionPerformed(GuiButton button) throws IOException {
    //?} else {
//~     protected void actionPerformed(GuiButton button) {
    //?}
        if (button instanceof IdButton) ((IdButton) button).onPress.run();
    }

    @Override
    //? if >=1.12 {
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
    //?} else {
//~     protected void keyTyped(char typedChar, int keyCode) {
    //?}
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) { onClose(); return; }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    //? if >=1.12 {
    public void handleMouseInput() throws IOException {
    //?} else {
//~     public void handleMouseInput() {
    //?}
        super.handleMouseInput();
        int delta = org.lwjgl.input.Mouse.getEventDWheel();
        int y = height - org.lwjgl.input.Mouse.getEventY() * height / mc.displayHeight - 1;
        if (delta != 0 && y >= rowsTop() && y < statusY() && maximumScroll(rows()) > 0) {
            scroll = Math.max(0, Math.min(maximumScroll(rows()), scroll - (int) Math.signum(delta)));
            initGui();
        }
    }

    private String fitted(String value, int width) {
        //? if >=1.12 {
        if (this.fontRenderer.getStringWidth(value) <= width) return value;
        return this.fontRenderer.trimStringToWidth(value, Math.max(1, width - this.fontRenderer.getStringWidth("..."))) + "...";
        //?} else {
//~         if (this.fontRendererObj.getStringWidth(value) <= width) return value;
//~         return this.fontRendererObj.trimStringToWidth(value, Math.max(1, width - this.fontRendererObj.getStringWidth("..."))) + "...";
        //?}
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        List<Row> rows = rows();
        int shown = Math.min(rows.size() - scroll, visibleRows());
        int textWidth = actionX() - dialog.contentX() - 6;
        for (int i = 0; i < shown; i++) {
            Row row = rows.get(scroll + i);
            int y = rowsTop() + i * ROW_HEIGHT;
            //? if >=1.12 {
            String name = fitted(row.name, textWidth - (row.badge ? this.fontRenderer.getStringWidth(PeerCraftUi.badgeText(row.licensed)) : 0));
            if (row.badge) PeerCraftUi.drawNameWithBadge(this.fontRenderer, name, row.licensed, dialog.contentX(), y + 2, PeerCraftUi.TEXT_TITLE);
            else this.fontRenderer.drawStringWithShadow(name, dialog.contentX(), y + 2, PeerCraftUi.TEXT_TITLE);
            this.fontRenderer.drawStringWithShadow(fitted(row.detail, textWidth), dialog.contentX(), y + 12, row.detailColor);
            //?} else {
//~             String name = fitted(row.name, textWidth - (row.badge ? this.fontRendererObj.getStringWidth(PeerCraftUi.badgeText(row.licensed)) : 0));
//~             if (row.badge) PeerCraftUi.drawNameWithBadge(this.fontRendererObj, name, row.licensed, dialog.contentX(), y + 2, PeerCraftUi.TEXT_TITLE);
//~             else this.fontRendererObj.drawStringWithShadow(name, dialog.contentX(), y + 2, PeerCraftUi.TEXT_TITLE);
//~             this.fontRendererObj.drawStringWithShadow(fitted(row.detail, textWidth), dialog.contentX(), y + 12, row.detailColor);
            //?}
        }
        if (maximumScroll(rows) > 0 && visibleRows() > 0) {
            int track = visibleRows() * ROW_HEIGHT, thumb = Math.max(10, track * visibleRows() / rows.size());
            int thumbY = rowsTop() + (track - thumb) * scroll / maximumScroll(rows);
            int x = dialog.contentX() + dialog.contentWidth() - 3;
            drawRect(x, rowsTop(), x + 3, rowsTop() + track, SteampunkPalette.BORDER);
            drawRect(x, thumbY, x + 3, thumbY + thumb, SteampunkPalette.ACCENT);
        }
        //? if >=1.12 {
        drawCenteredString(this.fontRenderer, this.statusMessage, this.width / 2, statusY(), this.statusColor);
        //?} else {
//~         drawCenteredString(this.fontRendererObj, this.statusMessage, this.width / 2, statusY(), this.statusColor);
        //?}
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
