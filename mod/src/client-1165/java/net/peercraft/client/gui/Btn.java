package net.peercraft.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Tiny stand-in for 1.20+'s {@code Button.builder(msg, onPress).bounds(x, y, w, h).build()}
 * fluent API, which does not exist on 1.16.5 ({@code new SteampunkButton(x, y, w, h, msg, onPress)}).
 * Keeping the same call shape lets the backported screens read like their {@code src/main}
 * originals — only the leading {@code Button.builder} becomes {@code Btn.builder}.
 */
final class Btn {

    static Builder builder(Component message, Button.OnPress onPress) {
        return new Builder(message, onPress);
    }

    private Btn() {
    }

    static final class Builder {
        private final Component message;
        private final Button.OnPress onPress;
        private boolean primary;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;

        Builder(Component message, Button.OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        Builder primary() { this.primary = true; return this; }

        Button build() {
            SteampunkButton button = new SteampunkButton(x, y, width, height, message, onPress);
            return primary ? button.primary() : button;
        }
    }
}
