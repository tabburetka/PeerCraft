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

    private IdButton(int x, int y, int width, int height, String text, Runnable onPress) {
        super(nextId++, x, y, width, height, text);
        this.onPress = onPress;
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
            return new IdButton(x, y, width, height, text, onPress);
        }
    }
}
