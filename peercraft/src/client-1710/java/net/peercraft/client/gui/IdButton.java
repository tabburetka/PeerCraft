package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiButton;

/**
 * 1.7.10 stand-in for the modern {@code Button.builder(text, onPress).bounds(x,y,w,h).build()}
 * fluent API — byte-identical to the {@code src/client-1122} twin ({@link GuiButton}'s
 * {@code (id,x,y,w,h,text)} constructor is unchanged between 1.7.10 and 1.12.2). 1.7.10's
 * {@link GuiButton} has no per-button click callback — dispatch goes through
 * {@code GuiScreen.actionPerformed(GuiButton)} keyed on the integer {@code id}. This wraps a
 * {@link Runnable} on the button itself; the owning screen only needs
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
    public void drawButton(net.minecraft.client.Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) return;
        boolean hover = mouseX >= this.xPosition && mouseX < this.xPosition + this.width
                && mouseY >= this.yPosition && mouseY < this.yPosition + this.height;
        int border = hover && this.enabled ? net.peercraft.client.theme.SteampunkPalette.BORDER_HOVER : net.peercraft.client.theme.SteampunkPalette.BORDER;
        net.minecraft.client.gui.Gui.drawRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + this.height, border);
        net.minecraft.client.gui.Gui.drawRect(this.xPosition + 1, this.yPosition + 1, this.xPosition + this.width - 1, this.yPosition + this.height - 1,
                !this.enabled ? net.peercraft.client.theme.SteampunkPalette.DISABLED : primary ? (hover ? net.peercraft.client.theme.SteampunkPalette.PRIMARY_HOVER : net.peercraft.client.theme.SteampunkPalette.PRIMARY) : hover ? net.peercraft.client.theme.SteampunkPalette.CONTROL_HOVER : net.peercraft.client.theme.SteampunkPalette.CONTROL);
        String label = mc.fontRenderer.trimStringToWidth(this.displayString, Math.max(0, this.width - 12));
        if (this.enabled && primary) {
            mc.fontRenderer.drawString(label, this.xPosition + (this.width - mc.fontRenderer.getStringWidth(label)) / 2,
                    this.yPosition + (this.height - 8) / 2, net.peercraft.client.theme.SteampunkPalette.CONTROL);
        } else {
        this.drawCenteredString(mc.fontRenderer, label, this.xPosition + this.width / 2, this.yPosition + (this.height - 8) / 2,
                this.enabled ? (primary ? net.peercraft.client.theme.SteampunkPalette.CONTROL : net.peercraft.client.theme.SteampunkPalette.TEXT) : net.peercraft.client.theme.SteampunkPalette.MUTED);
        }
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
