package net.peercraft.client.gui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.Gui;
import net.peercraft.client.theme.SteampunkPalette;
/** Brass frame around the original text editor; selection and cursor remain vanilla. */
class SteampunkField extends GuiTextField {
    SteampunkField(FontRenderer font, int x, int y, int width, int height) {
        super(font, x + 5, y, Math.max(1, width - 10), height);
        setEnableBackgroundDrawing(false);
        setTextColor(SteampunkPalette.TEXT);
        setDisabledTextColour(SteampunkPalette.MUTED);
    }
    @Override public void drawTextBox() {
        if (!getVisible()) return;
        int border = isFocused() ? SteampunkPalette.BORDER_HOVER : SteampunkPalette.BORDER;
        Gui.drawRect(xPosition - 5, yPosition, xPosition + width + 5, yPosition + height, border);
        Gui.drawRect(xPosition - 4, yPosition + 1, xPosition + width + 4, yPosition + height - 1, SteampunkPalette.CONTROL);
        int originalY = yPosition;
        yPosition += (height - 8) / 2;
        try { super.drawTextBox(); } finally { yPosition = originalY; }
    }
}
