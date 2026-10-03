package net.peercraft.client.gui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.Gui;
import net.peercraft.client.theme.SteampunkPalette;
/** Brass frame around the original text editor; selection and cursor remain vanilla. */
class SteampunkField extends GuiTextField {
    SteampunkField(int id, FontRenderer font, int x, int y, int width, int height) {
        super(id, font, x + 5, y, Math.max(1, width - 10), height);
        setEnableBackgroundDrawing(false);
        setTextColor(SteampunkPalette.TEXT);
        setDisabledTextColour(SteampunkPalette.MUTED);
    }
    @Override public void drawTextBox() {
        if (!getVisible()) return;
        int border = isFocused() ? SteampunkPalette.BORDER_HOVER : SteampunkPalette.BORDER;
        Gui.drawRect(x - 5, y, x + width + 5, y + height, border);
        Gui.drawRect(x - 4, y + 1, x + width + 4, y + height - 1, SteampunkPalette.CONTROL);
        int originalY = y;
        y += (height - 8) / 2;
        try { super.drawTextBox(); } finally { y = originalY; }
    }
}
