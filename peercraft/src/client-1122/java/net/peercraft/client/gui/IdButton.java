package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiButton;

/**
 * 1.12.2 stand-in for the modern {@code Button.builder(text, onPress).bounds(x,y,w,h).build()}
 * fluent API. 1.12.2's {@link GuiButton} has no per-button click callback — dispatch goes
 * through {@code GuiScreen.actionPerformed(GuiButton)} keyed on the integer {@code id}. This
 * wraps a {@link Runnable} on the button itself; the owning screen only needs
 * {@code if (button instanceof IdButton) ((IdButton) button).onPress.run();} in its
 * {@code actionPerformed}. Auto-assigns ids from a high base so they can't collide with a
 * vanilla screen's own numbered buttons.
 */
class IdButton extends GuiButton {

    private static int nextId = 9000;

    final Runnable onPress;
    private boolean primary;

    private IdButton(int x, int y, int width, int height, String text, Runnable onPress) {
        super(nextId++, x, y, width, height, text);
        this.onPress = onPress;
    }

    @Override
    public void drawButton(net.minecraft.client.Minecraft mc, int mouseX, int mouseY, float partialTick) {
        if (!this.visible) return;
        boolean hover = mouseX >= this.x && mouseX < this.x + this.width
                && mouseY >= this.y && mouseY < this.y + this.height;
        int border = hover && this.enabled ? net.peercraft.client.theme.SteampunkPalette.BORDER_HOVER : net.peercraft.client.theme.SteampunkPalette.BORDER;
        net.minecraft.client.gui.Gui.drawRect(this.x, this.y, this.x + this.width, this.y + this.height, border);
        net.minecraft.client.gui.Gui.drawRect(this.x + 1, this.y + 1, this.x + this.width - 1, this.y + this.height - 1,
                !this.enabled ? net.peercraft.client.theme.SteampunkPalette.DISABLED : primary ? (hover ? net.peercraft.client.theme.SteampunkPalette.PRIMARY_HOVER : net.peercraft.client.theme.SteampunkPalette.PRIMARY) : hover ? net.peercraft.client.theme.SteampunkPalette.CONTROL_HOVER : net.peercraft.client.theme.SteampunkPalette.CONTROL);
        // Draw the copy icon geometrically: the legacy font may omit this glyph.
        if ("⧉".equals(this.displayString)) {
            int color = this.enabled ? net.peercraft.client.theme.SteampunkPalette.TEXT
                    : net.peercraft.client.theme.SteampunkPalette.MUTED;
            int x = this.x + (this.width - 10) / 2;
            int y = this.y + (this.height - 11) / 2;
            drawSheet(x, y, color);
            net.minecraft.client.gui.Gui.drawRect(x + 3, y + 3, x + 10, y + 11,
                    !this.enabled ? net.peercraft.client.theme.SteampunkPalette.DISABLED
                            : hover ? net.peercraft.client.theme.SteampunkPalette.CONTROL_HOVER
                            : net.peercraft.client.theme.SteampunkPalette.CONTROL);
            drawSheet(x + 3, y + 3, color);
            return;
        }
        String label = mc.fontRenderer.trimStringToWidth(this.displayString, Math.max(0, this.width - 12));
        if (this.enabled && primary) {
            mc.fontRenderer.drawString(label, this.x + (this.width - mc.fontRenderer.getStringWidth(label)) / 2,
                    this.y + (this.height - 8) / 2, net.peercraft.client.theme.SteampunkPalette.CONTROL);
        } else {
        this.drawCenteredString(mc.fontRenderer, label, this.x + this.width / 2, this.y + (this.height - 8) / 2,
                this.enabled ? (primary ? net.peercraft.client.theme.SteampunkPalette.CONTROL : net.peercraft.client.theme.SteampunkPalette.TEXT) : net.peercraft.client.theme.SteampunkPalette.MUTED);
        }
    }

    private static void drawSheet(int x, int y, int color) {
        net.minecraft.client.gui.Gui.drawRect(x, y, x + 7, y + 1, color);
        net.minecraft.client.gui.Gui.drawRect(x, y + 7, x + 7, y + 8, color);
        net.minecraft.client.gui.Gui.drawRect(x, y, x + 1, y + 8, color);
        net.minecraft.client.gui.Gui.drawRect(x + 6, y, x + 7, y + 8, color);
    }

    static Builder builder(String text, Runnable onPress) {
        return new Builder(text, onPress);
    }

    static final class Builder {
        private final String text;
        private final Runnable onPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;
        private boolean primary;
        Builder primary() { this.primary = true; return this; }

        Builder(String text, Runnable onPress) {
            this.text = text;
            this.onPress = onPress;
        }

        Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        IdButton build() {
            IdButton button = new IdButton(x, y, width, height, text, onPress);
            button.primary = primary;
            return button;
        }
    }
}
