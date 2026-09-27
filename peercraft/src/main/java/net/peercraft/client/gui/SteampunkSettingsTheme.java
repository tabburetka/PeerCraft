//? if =1.21.1 {
package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Flat brass-and-charcoal styling for the 1.21.1 settings screen. */
final class SteampunkSettingsTheme {
    static final int BACKGROUND = 0xFF111110;
    static final int PANEL = 0xFF1C1814;
    static final int BORDER = 0xFF715333;
    static final int BORDER_HOVER = 0xFFBB8B4B;
    static final int TEXT = 0xFFE9DFCB;
    static final int MUTED = 0xFFA99D89;
    static final int ACCENT = 0xFFE8AF50;

    private static final int CONTROL = 0xFF15120F;
    private static final int CONTROL_HOVER = 0xFF2C241B;
    private static final int PRIMARY = 0xFFC89545;
    private static final int PRIMARY_HOVER = 0xFFE5AF52;
    private static final int DISABLED = 0xFF211D18;

    private SteampunkSettingsTheme() {
    }

    static void frame(GuiGraphics graphics, int x, int y, int width, int height, int fill, int border) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.fill(x, y, x + width, y + height, border);
        if (width > 2 && height > 2) {
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
        }
    }

    /** Positions are derived from elapsed time, so animation speed does not depend on FPS. */
    static void particles(GuiGraphics graphics, int screenWidth, int screenHeight,
                          int panelLeft, int panelRight, long elapsedMillis) {
        if (screenHeight < 40) {
            return;
        }
        double seconds = Math.max(0L, elapsedMillis) / 1000.0;
        particleMargin(graphics, 8, panelLeft - 12, screenHeight, seconds, 0);
        particleMargin(graphics, panelRight + 12, screenWidth - 8, screenHeight, seconds, 17);
    }

    private static void particleMargin(GuiGraphics graphics, int left, int right, int height,
                                       double seconds, int seed) {
        if (right - left < 12) {
            return;
        }
        int count = Math.min(10, Math.max(3, height / 42));
        double travel = height + 24.0;
        for (int i = 0; i < count; i++) {
            int id = i + seed;
            double phase = fraction(id * 0.61803398875 + 0.13);
            double progress = fraction(phase + seconds * (8.0 + fraction(id * 0.381966) * 8.0) / travel);
            int y = (int) Math.round(height + 12 - progress * travel);
            double drift = Math.sin(seconds * 0.42 + id * 2.3) * 3.0;
            int x = (int) Math.round(left + 3 + fraction(id * 0.754877666 + 0.27)
                    * (right - left - 6) + drift);
            x = Math.max(left, Math.min(right - 2, x));
            double fade = Math.max(0.0, Math.min(1.0, Math.min(y / 24.0, (height - y) / 24.0)));
            double glow = fade * (0.48 + 0.16 * Math.sin(seconds * 1.15 + id));
            if (glow <= 0.0) {
                continue;
            }
            graphics.fill(x - 2, y - 2, x + 3, y + 3, alpha(0xF6A53E, (int) (glow * 24)));
            graphics.fill(x - 1, y - 1, x + 2, y + 2, alpha(0xFFB850, (int) (glow * 60)));
            graphics.fill(x, y, x + 1, y + (i % 3 == 0 ? 2 : 1), alpha(0xFFD580, (int) (glow * 255)));
        }
    }

    private static double fraction(double value) {
        return value - Math.floor(value);
    }

    private static int alpha(int color, int opacity) {
        return (Math.max(0, Math.min(255, opacity)) << 24) | (color & 0xFFFFFF);
    }

    static Button action(int x, int y, int width, int height, Component message,
                         Button.OnPress onPress, boolean primary) {
        return action(x, y, width, height, message, onPress, primary, message);
    }

    static Button action(int x, int y, int width, int height, Component message,
                         Button.OnPress onPress, boolean primary, Component narrationLabel) {
        return new Action(x, y, width, height, message, onPress, primary, narrationLabel);
    }

    private static int outline(Button button) {
        if (!button.active) {
            return BORDER;
        }
        return button.isFocused() ? ACCENT : button.isHovered() ? BORDER_HOVER : BORDER;
    }

    private static void buttonFrame(GuiGraphics graphics, Button button, boolean primary) {
        int fill = !button.active ? DISABLED : primary
                ? (button.isHoveredOrFocused() ? PRIMARY_HOVER : PRIMARY)
                : (button.isHoveredOrFocused() ? CONTROL_HOVER : CONTROL);
        int border = primary && button.active && !button.isFocused() ? BORDER_HOVER : outline(button);
        frame(graphics, button.getX(), button.getY(), button.getWidth(), button.getHeight(), fill, border);
    }

    private static String fitted(Font font, String value, int width) {
        if (font.width(value) <= width) {
            return value;
        }
        String suffix = "…";
        return font.plainSubstrByWidth(value, Math.max(0, width - font.width(suffix))) + suffix;
    }

    private static void centered(GuiGraphics graphics, Font font, Component message,
                                 int left, int top, int width, int height, int color, boolean wrap) {
        int available = Math.max(1, width);
        List<FormattedText> lines = font.getSplitter().splitLines(message, available, Style.EMPTY);
        int count = wrap && height >= 2 * font.lineHeight ? Math.min(2, lines.size()) : 1;
        count = Math.max(1, count);
        int y = top + (height - count * font.lineHeight) / 2;
        for (int i = 0; i < count; i++) {
            String line = count == 1 ? message.getString() : lines.get(i).getString();
            if (count == 2 && i == 1 && lines.size() > 2) {
                line += "…";
            }
            line = fitted(font, line, available);
            graphics.drawString(font, line, left + (width - font.width(line)) / 2,
                    y + i * font.lineHeight, color, false);
        }
    }

    private static final class Action extends Button {
        private final boolean primary;
        private final Component narrationLabel;

        private Action(int x, int y, int width, int height, Component message,
                       OnPress onPress, boolean primary, Component narrationLabel) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
            this.primary = primary;
            this.narrationLabel = narrationLabel;
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            return wrapDefaultNarrationMessage(this.narrationLabel);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            buttonFrame(graphics, this, this.primary);
            centered(graphics, Minecraft.getInstance().font, getMessage(), getX() + 6, getY(),
                    getWidth() - 12, getHeight(), this.active ? this.primary ? CONTROL : TEXT : MUTED, false);
        }
    }

    static final class Toggle extends Button {
        private final Component label;
        private final Consumer<Boolean> onChange;
        private boolean selected;
        private Component narrationLabel;

        Toggle(int x, int y, int width, int height, Component message, boolean initial,
               Consumer<Boolean> onChange) {
            super(x, y, width, height, message, button -> ((Toggle) button).toggle(), DEFAULT_NARRATION);
            this.label = message;
            this.onChange = Objects.requireNonNull(onChange);
            setSelected(initial);
        }

        boolean selected() {
            return this.selected;
        }

        void setNarrationLabel(Component label) {
            this.narrationLabel = label;
        }

        /** Updates the displayed state without firing a user-change callback. */
        void setSelected(boolean selected) {
            this.selected = selected;
            setMessage(this.label.getString().isEmpty() ? stateLabel() : this.label);
        }

        private Component stateLabel() {
            return Component.translatable(this.selected ? "options.on" : "options.off");
        }

        private void toggle() {
            setSelected(!this.selected);
            this.onChange.accept(this.selected);
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            Component caption = this.narrationLabel == null ? this.label : this.narrationLabel;
            Component description = caption.getString().isEmpty() ? stateLabel()
                    : caption.copy().append(": ").append(stateLabel());
            return wrapDefaultNarrationMessage(description);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            buttonFrame(graphics, this, false);
            int trackX = getX() + 6;
            int trackY = getY() + (getHeight() - 12) / 2;
            int trackColor = this.selected && this.active ? 0xFF78542B : 0xFF29231C;
            frame(graphics, trackX, trackY, 28, 12, trackColor,
                    this.selected && this.active ? BORDER_HOVER : BORDER);
            int knobX = trackX + (this.selected ? 16 : 2);
            graphics.fill(knobX, trackY + 2, knobX + 10, trackY + 10,
                    this.active ? (this.selected ? 0xFFF1C779 : 0xFF9D8B70) : BORDER);
            Font font = Minecraft.getInstance().font;
            String text = fitted(font, getMessage().getString(), Math.max(0, getWidth() - 44));
            graphics.drawString(font, text, getX() + 40, getY() + (getHeight() - font.lineHeight) / 2,
                    this.active ? TEXT : MUTED, false);
        }
    }

    static final class Choice extends Button {
        private final List<String> values;
        private final Function<String, Component> labels;
        private int index;
        private Component narrationLabel;

        Choice(int x, int y, int width, int height, List<String> values, String initial,
               Function<String, Component> labels) {
            super(x, y, width, height, Component.empty(),
                    button -> ((Choice) button).cycle(Screen.hasShiftDown() ? -1 : 1), DEFAULT_NARRATION);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("A settings choice needs at least one value");
            }
            this.values = List.copyOf(values);
            this.labels = Objects.requireNonNull(labels);
            this.index = Math.max(0, this.values.indexOf(initial));
            setMessage(this.labels.apply(getValue()));
        }

        String getValue() {
            return this.values.get(this.index);
        }

        void setNarrationLabel(Component label) {
            this.narrationLabel = label;
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            Component description = this.narrationLabel == null ? getMessage()
                    : this.narrationLabel.copy().append(": ").append(getMessage());
            return wrapDefaultNarrationMessage(description);
        }

        private void cycle(int direction) {
            this.index = Math.floorMod(this.index + direction, this.values.size());
            setMessage(this.labels.apply(getValue()));
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            int direction = mouseX < getX() + getWidth() / 2.0 ? -1 : 1;
            cycle(Screen.hasShiftDown() ? -direction : direction);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (this.active && this.visible && isFocused()
                    && (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT)) {
                cycle(keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1);
                playDownSound(Minecraft.getInstance().getSoundManager());
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            buttonFrame(graphics, this, false);
            Font font = Minecraft.getInstance().font;
            int color = this.active ? TEXT : MUTED;
            int centerY = getY() + (getHeight() - 7) / 2;
            int arrowColor = this.active && isHoveredOrFocused() ? ACCENT : BORDER_HOVER;
            chevron(graphics, getX() + 6, centerY, false, arrowColor);
            chevron(graphics, getX() + getWidth() - 10, centerY, true, arrowColor);
            centered(graphics, font, getMessage(), getX() + 16, getY(), getWidth() - 32, getHeight(), color, true);
        }

        private static void chevron(GuiGraphics graphics, int x, int y, boolean right, int color) {
            for (int i = 0; i < 4; i++) {
                int px = x + (right ? i : 3 - i);
                graphics.fill(px, y + i, px + 1, y + i + 1, color);
                graphics.fill(px, y + 6 - i, px + 1, y + 7 - i, color);
            }
        }
    }

    static final class Field extends EditBox {
        private boolean renderingText;
        private boolean editable = true;

        Field(Font font, int x, int y, int width, int height, Component message) {
            super(font, x, y, width, height, message);
            setTextColor(TEXT);
            setTextColorUneditable(MUTED);
        }

        @Override
        public void setEditable(boolean editable) {
            super.setEditable(editable);
            this.editable = editable;
        }

        @Override
        public boolean isBordered() {
            // 1.21.1 checks this accessor for the vanilla sprite, but uses its private
            // bordered flag for text/caret insets. Keep those native insets intact.
            return !this.renderingText && super.isBordered();
        }

        @Override
        public int getInnerWidth() {
            // The superclass normally calls isBordered(), which is suppressed only while
            // drawing text. Use the actual flag so scrolling and click positions agree.
            return Math.max(0, getWidth() - (super.isBordered() ? 8 : 0));
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean enabled = this.active && this.editable;
            int border = enabled && isFocused() ? ACCENT
                    : enabled && isHovered() ? BORDER_HOVER : BORDER;
            frame(graphics, getX(), getY(), getWidth(), getHeight(), enabled ? CONTROL : DISABLED, border);
            setTextColor(enabled ? TEXT : MUTED);
            this.renderingText = true;
            try {
                super.renderWidget(graphics, mouseX, mouseY, partialTick);
            } finally {
                this.renderingText = false;
            }
        }
    }
}
//?}
