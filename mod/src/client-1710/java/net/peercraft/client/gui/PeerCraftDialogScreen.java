package net.peercraft.client.gui;
import net.minecraft.client.gui.GuiScreen;
import net.peercraft.client.theme.SteampunkPalette;
/** Shared panel geometry for the physical legacy Forge screen twins. */
abstract class PeerCraftDialogScreen extends GuiScreen {
    protected SteampunkDialog dialog;
    private final String subtitle;
    private final int desiredHeight;
    private final int desiredWidth;
    private int footerActions, bodyOffset, bodyLines, bodyRows;
    private final long openedAt = System.currentTimeMillis();
    protected PeerCraftDialogScreen(String subtitle, int desiredHeight) {
        this(subtitle, desiredHeight, 320);
    }
    protected PeerCraftDialogScreen(String subtitle, int desiredHeight, int desiredWidth) {
        this.subtitle = subtitle; this.desiredHeight = desiredHeight; this.desiredWidth = desiredWidth;
    }
    @Override public void initGui() {
        footerActions = 0;
        dialog = new SteampunkDialog(width, height, desiredHeight, subtitle, desiredWidth);
    }
    @Override public void drawDefaultBackground() {
        boolean inWorld = mc != null && mc.theWorld != null;
        if (inWorld) super.drawDefaultBackground();
        dialog.background(this.fontRendererObj, width, height, System.currentTimeMillis() - openedAt, inWorld);
    }
    protected int backY() { return dialog.top + dialog.height - dialog.buttonHeight() - 12; }
    protected void label(String key, int y) {
        this.fontRendererObj.drawStringWithShadow(this.fontRendererObj.trimStringToWidth(PeerCraftLang.tr(key), dialog.contentWidth()),
                dialog.contentX(), y, SteampunkPalette.MUTED);
    }
    protected void status(String message, int y, int color) {
        dialog.status(this.fontRendererObj, message, y, Math.max(0, backY() - 8 - y), color);
    }
    protected IdButton dialogAction(String label, Runnable action, boolean primary, int index, int count) {
        footerActions = Math.max(footerActions, count);
        IdButton.Builder builder = IdButton.builder(label, action).bounds(dialog.contentX(),
                backY() - (count - 1 - index) * dialog.buttonPitch(), dialog.contentWidth(), dialog.buttonHeight());
        IdButton button = (primary ? builder.primary() : builder).build();
        this.buttonList.add(button); return button;
    }
    protected int bodyBottom() { return backY() - 8 - Math.max(0, footerActions - 1) * dialog.buttonPitch(); }
    protected void drawBody(java.util.List<String> paragraphs, int color) {
        java.util.List<String> text = new java.util.ArrayList<>();
        for (String paragraph : paragraphs) text.addAll(PeerCraftUi.wrap(this.fontRendererObj, paragraph, Math.max(1, dialog.contentWidth() - 8)));
        bodyRows = Math.max(0, (bodyBottom() - dialog.contentTop()) / 12);
        bodyLines = text.size(); bodyOffset = Math.max(0, Math.min(bodyOffset, Math.max(0, bodyLines - bodyRows)));
        for (int i = 0; i < Math.min(bodyRows, bodyLines - bodyOffset); i++)
            this.drawCenteredString(this.fontRendererObj, text.get(bodyOffset + i), width / 2, dialog.contentTop() + i * 12, color);
        if (bodyLines > bodyRows && bodyRows > 0) {
            int track = bodyRows * 12, thumb = Math.max(8, track * bodyRows / bodyLines);
            int y = dialog.contentTop() + (track - thumb) * bodyOffset / (bodyLines - bodyRows);
            drawRect(dialog.left + dialog.width - 7, dialog.contentTop(), dialog.left + dialog.width - 5, dialog.contentTop() + track, SteampunkPalette.BORDER);
            drawRect(dialog.left + dialog.width - 7, y, dialog.left + dialog.width - 5, y + thumb, SteampunkPalette.ACCENT);
        }
    }
    @Override protected void keyTyped(char typedChar, int keyCode) {
        if (bodyLines > bodyRows && (keyCode == org.lwjgl.input.Keyboard.KEY_NEXT || keyCode == org.lwjgl.input.Keyboard.KEY_PRIOR)) {
            bodyOffset = Math.max(0, Math.min(Math.max(0, bodyLines - bodyRows), bodyOffset + (keyCode == org.lwjgl.input.Keyboard.KEY_NEXT ? 1 : -1) * Math.max(1, bodyRows))); return;
        }
        super.keyTyped(typedChar, keyCode);
    }
    @Override public void handleMouseInput() {
        super.handleMouseInput();
        int delta = org.lwjgl.input.Mouse.getEventDWheel();
        int x = org.lwjgl.input.Mouse.getEventX() * width / mc.displayWidth;
        int y = height - org.lwjgl.input.Mouse.getEventY() * height / mc.displayHeight - 1;
        if (delta != 0 && x >= dialog.contentX() && x < dialog.left + dialog.width && y >= dialog.contentTop() && y < bodyBottom())
            bodyOffset = Math.max(0, Math.min(Math.max(0, bodyLines - bodyRows), bodyOffset - (int) Math.signum(delta) * 3));
    }
}
