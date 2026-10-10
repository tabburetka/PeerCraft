//? if >=1.21.1 && <26.1 {
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

/** Shared brass-and-charcoal controls for PeerCraft screens. */
public final class SteampunkSettingsTheme {
    static final int BACKGROUND = net.peercraft.client.theme.SteampunkPalette.BACKGROUND;
    static final int PANEL = net.peercraft.client.theme.SteampunkPalette.PANEL;
    static final int BORDER = net.peercraft.client.theme.SteampunkPalette.BORDER;
    static final int BORDER_HOVER = net.peercraft.client.theme.SteampunkPalette.BORDER_HOVER;
    static final int TEXT = net.peercraft.client.theme.SteampunkPalette.TEXT;
    static final int MUTED = net.peercraft.client.theme.SteampunkPalette.MUTED;
    static final int ACCENT = net.peercraft.client.theme.SteampunkPalette.ACCENT;

    private static final int CONTROL = net.peercraft.client.theme.SteampunkPalette.CONTROL;
    private static final int CONTROL_HOVER = net.peercraft.client.theme.SteampunkPalette.CONTROL_HOVER;
    private static final int PRIMARY = net.peercraft.client.theme.SteampunkPalette.PRIMARY;
    private static final int PRIMARY_HOVER = net.peercraft.client.theme.SteampunkPalette.PRIMARY_HOVER;
    private static final int DISABLED = net.peercraft.client.theme.SteampunkPalette.DISABLED;

    private SteampunkSettingsTheme() {
    }

    static net.minecraft.client.gui.components.Tooltip tooltipOf(net.minecraft.client.gui.components.AbstractWidget widget) {
        //? if <1.21.6 {
        return widget.getTooltip();
        //?} else {
        /*return ((net.peercraft.client.mixin.PeerCraftWidgetTooltipAccessor) widget).peercraft$tooltip().get();*/
        //?}
    }

    static void pushScale(GuiGraphics graphics, float scale) {
        //? if <1.21.6 {
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1.0F);
        //?} else {
        /*graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);*/
        //?}
    }

    static void popScale(GuiGraphics graphics) {
        //? if <1.21.6 {
        graphics.pose().popPose();
        //?} else {
        /*graphics.pose().popMatrix();*/
        //?}
    }

    private static boolean shiftDown() {
        //? if <1.21.9 {
        return Screen.hasShiftDown();
        //?} else {
        /*return false;*/
        //?}
    }

    /** Draws the LAN dialog while preserving vanilla widgets as the input/state owners. */
    public static void renderLan(GuiGraphics graphics, Font font, int width, int height,
                                 List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children,
                                 int mouseX, int mouseY, float partialTick, long elapsedMillis) {
        int panelWidth = Math.min(360, width - 24);
        int left = (width - panelWidth) / 2;
        var controls = new java.util.ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
        var actions = new java.util.ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
        for (var child : children) {
            if (!(child instanceof net.minecraft.client.gui.components.AbstractWidget widget) || !widget.visible || widget instanceof net.minecraft.client.gui.components.StringWidget) continue;
            if (widget instanceof Button && !(widget instanceof net.minecraft.client.gui.components.CycleButton)) {
                actions.add(widget);
            } else {
                controls.add(widget);
            }
        }
        int pitch = Math.max(10, Math.min(28, (height - 104) / Math.max(1, controls.size())));
        int controlHeight = Math.max(9, Math.min(24, pitch - 3));
        int panelHeight = Math.min(height - 16, 88 + controls.size() * pitch);
        int top = (height - panelHeight) / 2;
        frame(graphics, left, top, panelWidth, panelHeight, PANEL, BORDER);
        graphics.drawCenteredString(font, "PeerCraft", width / 2, top + 10, ACCENT);
        graphics.drawCenteredString(font, Component.translatable("lanServer.title"), width / 2, top + 26, TEXT);
        int y = top + 48;
        for (var widget : controls) {
            widget.setX(left + 12);
            widget.setY(y);
            widget.setWidth(panelWidth - 24);
            ((net.peercraft.client.mixin.PeerCraftWidgetSizeAccessor) widget).peercraft$setHeight(controlHeight);
            if (widget instanceof EditBox field) {
                // The vanilla control order changes between versions (26.2 adds a LAN toggle).
                // Identify our world-name field by its translation key, never its row index.
                boolean worldName = field.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents contents
                        && "peercraft.mixin.share_to_lan.world_name".equals(contents.getKey());
                graphics.drawString(font, !worldName ? Component.translatable("lanServer.port") :
                        Component.translatable("peercraft.mixin.share_to_lan.world_name"), left + 12, y + Math.max(0, (controlHeight - 9) / 2), MUTED, false);
                widget.setX(left + panelWidth / 2);
                widget.setWidth(panelWidth / 2 - 12);
                field.setBordered(false);
                if (worldName) field.setHint(Component.literal(fitted(font,
                        Component.translatable("peercraft.mixin.share_to_lan.world_name_hint").getString(),
                        Math.max(1, widget.getWidth() - 4))));
                frame(graphics, widget.getX() - 4, y, widget.getWidth() + 4, controlHeight, CONTROL, BORDER);
                field.setY(y + Math.max(0, (controlHeight - 9) / 2));
                try { field.render(graphics, mouseX, mouseY, partialTick); }
                finally { field.setY(y); }
            } else {
                boolean hovered = mouseX >= widget.getX() && mouseX < widget.getX() + widget.getWidth()
                        && mouseY >= y && mouseY < y + widget.getHeight();
                frame(graphics, widget.getX(), y, widget.getWidth(), widget.getHeight(),
                        !widget.active ? DISABLED : hovered ? CONTROL_HOVER : CONTROL, hovered ? BORDER_HOVER : BORDER);
                Component label = widget.getMessage();
                if (widget instanceof net.minecraft.client.gui.components.Checkbox checkbox) {
                    label = Component.literal(checkbox.selected() ? "✓ " : "□ ").append(label);
                }
                centered(graphics, font, label, widget.getX() + 6, y, widget.getWidth() - 12,
                        widget.getHeight(), widget.active ? TEXT : MUTED, false);
            }
            y += pitch;
        }
        y = top + panelHeight - 32;
        int actionWidth = (panelWidth - 30) / 2;
        for (int i = 0; i < actions.size(); i++) {
            var widget = actions.get(i);
            widget.setX(left + 12 + i * (actionWidth + 6));
            widget.setY(y);
            widget.setWidth(actionWidth);
            boolean primary = i == 0;
            boolean hovered = widget.isMouseOver(mouseX, mouseY);
            frame(graphics, widget.getX(), y, actionWidth, widget.getHeight(),
                    !widget.active ? DISABLED : primary ? (hovered ? PRIMARY_HOVER : PRIMARY) : CONTROL, BORDER);
            centered(graphics, font, widget.getMessage(), widget.getX() + 4, y, actionWidth - 8,
                    widget.getHeight(), !widget.active ? MUTED : primary ? CONTROL : TEXT, false);
        }
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

    /** Shared flat shell for full PeerCraft screens; the caller still owns its content layout. */
    static void screenBackground(GuiGraphics graphics, int screenWidth, int screenHeight,
                                 int panelLeft, int panelTop, int panelWidth, int panelHeight,
                                 long elapsedMillis) {
        graphics.fill(0, 0, screenWidth, screenHeight, BACKGROUND);
        particles(graphics, screenWidth, screenHeight, panelLeft, panelLeft + panelWidth, elapsedMillis);
        frame(graphics, panelLeft, panelTop, panelWidth, panelHeight, PANEL, BORDER);
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
            // Each spark follows an independent, irrational cadence. A shared speed made the
            // previous animation periodically collapse into a visible horizontal row.
            double phase = fraction(id * 0.754877666 + 0.13
                    + seconds * (0.021 + fraction(id * 0.414213562) * 0.019));
            double progress = phase;
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
        return action(x, y, width, height, message, onPress, primary, null);
    }

    static Button action(int x, int y, int width, int height, Component message,
                         Button.OnPress onPress, boolean primary, Component narrationLabel) {
        return new Action(x, y, width, height, message, onPress, primary, narrationLabel);
    }

    /** Reuses an existing button's callback and state, including asynchronous login updates. */
    static Button decorate(Button original, int x, int y, int width, int height, boolean primary) {
        return new DecoratedAction(original, x, y, width, height, primary);
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

    private static class Action extends Button {
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
            return wrapDefaultNarrationMessage(this.narrationLabel == null ? getMessage() : this.narrationLabel);
        }

        @Override
        //? if <1.21.11
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if >=1.21.11
        /*protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {*/
            buttonFrame(graphics, this, this.primary);
            centered(graphics, Minecraft.getInstance().font, getMessage(), getX() + 6, getY(),
                    getWidth() - 12, getHeight(), this.active ? this.primary ? CONTROL : TEXT : MUTED, false);
        }
    }

    private static final class DecoratedAction extends Action {
        private final Button original;
        private final boolean copyIcon;

        private DecoratedAction(Button original, int x, int y, int width, int height, boolean primary) {
            super(x, y, width, height, original.getMessage(), button -> pressOriginal(original), primary, null);
            this.original = original;
            this.copyIcon = original.getWidth() == 14 && original.getHeight() == 14;
            synchronizeState();
        }

        private static void pressOriginal(Button original) {
            //? if <1.21.9 {
            original.onPress();
            //?} else {
            /*original.onPress(new net.minecraft.client.input.KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));*/
            //?}
        }

        private void synchronizeState() {
            this.active = this.original.active;
            this.visible = this.original.visible;
            setMessage(this.original.getMessage());
            setTooltip(tooltipOf(this.original));
        }

        //? if <1.21.9 {
        @Override
        public void onPress() {
            synchronizeState();
            if (this.active && this.visible) {
                super.onPress();
                synchronizeState();
            }
        }
        //?} else {
        /*        @Override
        public void onPress(net.minecraft.client.input.InputWithModifiers input) {
            synchronizeState();
            if (this.active && this.visible) {
                this.original.onPress(input);
                synchronizeState();
            }
        }*/
        //?}

        //? if <1.21.9 {
        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            synchronizeState();
            return super.mouseClicked(mouseX, mouseY, button);
        }
        //?} else {
        /*        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            synchronizeState();
            return super.mouseClicked(event, doubleClick);
        }*/
        //?}

        //? if <1.21.9 {
        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            synchronizeState();
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        //?} else {
        /*        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
            synchronizeState();
            return super.keyPressed(event);
        }*/
        //?}

        @Override
        //? if <1.21.11
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if >=1.21.11
        /*protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {*/
            synchronizeState();
            if (this.copyIcon) {
                buttonFrame(graphics, this, false);
                int x = getX() + (getWidth() - 9) / 2;
                int y = getY() + (getHeight() - 10) / 2;
                frame(graphics, x, y, 7, 8, CONTROL, this.active ? TEXT : MUTED);
                frame(graphics, x + 2, y + 2, 7, 8, CONTROL, this.active ? TEXT : MUTED);
            } else {
                //? if <1.21.11
                super.renderWidget(graphics, mouseX, mouseY, partialTick);
                //? if >=1.21.11
                /*super.renderContents(graphics, mouseX, mouseY, partialTick);*/
            }
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            return this.copyIcon
                    ? wrapDefaultNarrationMessage(Component.translatable("peercraft.gui.account.copy_code_tooltip"))
                    : super.createNarrationMessage();
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
        //? if <1.21.11
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if >=1.21.11
        /*protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {*/
            buttonFrame(graphics, this, false);
            if (getWidth() < 44) {
                frame(graphics, getX() + 3, getY() + 3, 14, 14, CONTROL, this.selected ? ACCENT : BORDER);
                if (this.selected) {
                    graphics.fill(getX() + 6, getY() + 6, getX() + 14, getY() + 14, this.active ? ACCENT : MUTED);
                }
                return;
            }
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
        private final Consumer<String> onChange;
        private int index;
        private Component narrationLabel;

        Choice(int x, int y, int width, int height, List<String> values, String initial,
               Function<String, Component> labels) {
            this(x, y, width, height, values, initial, labels, value -> { });
        }

        Choice(int x, int y, int width, int height, List<String> values, String initial,
               Function<String, Component> labels, Consumer<String> onChange) {
            super(x, y, width, height, Component.empty(),
                    button -> ((Choice) button).cycle(shiftDown() ? -1 : 1), DEFAULT_NARRATION);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("A settings choice needs at least one value");
            }
            this.values = List.copyOf(values);
            this.labels = Objects.requireNonNull(labels);
            this.onChange = Objects.requireNonNull(onChange);
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

        //? if >=1.21.9 {
        /*@Override
        public void onPress(net.minecraft.client.input.InputWithModifiers input) {
            cycle(input.hasShiftDown() ? -1 : 1);
        }*/
        //?}

        private void cycle(int direction) {
            this.index = Math.floorMod(this.index + direction, this.values.size());
            setMessage(this.labels.apply(getValue()));
            this.onChange.accept(getValue());
        }

        //? if <1.21.9 {
        @Override
        public void onClick(double mouseX, double mouseY) {
            int direction = mouseX < getX() + getWidth() / 2.0 ? -1 : 1;
            cycle(shiftDown() ? -direction : direction);
        }
        //?} else {
        /*        @Override
        public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            double mouseX = event.x();
            int direction = mouseX < getX() + getWidth() / 2.0 ? -1 : 1;
            cycle(event.hasShiftDown() ? -direction : direction);
        }*/
        //?}

        //? if <1.21.9 {
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
        //?} else {
        /*        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
            int keyCode = event.key();
            if (this.active && this.visible && isFocused()
                    && (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT)) {
                cycle(keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1);
                playDownSound(Minecraft.getInstance().getSoundManager());
                return true;
            }
            return super.keyPressed(event);
        }*/
        //?}

        @Override
        //? if <1.21.11
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if >=1.21.11
        /*protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {*/
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
//?} else {
/*package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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

public final class SteampunkSettingsTheme {
    static final int BACKGROUND = net.peercraft.client.theme.SteampunkPalette.BACKGROUND;
    static final int PANEL = net.peercraft.client.theme.SteampunkPalette.PANEL;
    static final int BORDER = net.peercraft.client.theme.SteampunkPalette.BORDER;
    static final int BORDER_HOVER = net.peercraft.client.theme.SteampunkPalette.BORDER_HOVER;
    static final int TEXT = net.peercraft.client.theme.SteampunkPalette.TEXT;
    static final int MUTED = net.peercraft.client.theme.SteampunkPalette.MUTED;
    static final int ACCENT = net.peercraft.client.theme.SteampunkPalette.ACCENT;

    private static final int CONTROL = net.peercraft.client.theme.SteampunkPalette.CONTROL;
    private static final int CONTROL_HOVER = net.peercraft.client.theme.SteampunkPalette.CONTROL_HOVER;
    private static final int PRIMARY = net.peercraft.client.theme.SteampunkPalette.PRIMARY;
    private static final int PRIMARY_HOVER = net.peercraft.client.theme.SteampunkPalette.PRIMARY_HOVER;
    private static final int DISABLED = net.peercraft.client.theme.SteampunkPalette.DISABLED;

    private SteampunkSettingsTheme() {
    }

    static net.minecraft.client.gui.components.Tooltip tooltipOf(net.minecraft.client.gui.components.AbstractWidget widget) {

        return ((net.peercraft.client.mixin.PeerCraftWidgetTooltipAccessor) widget).peercraft$tooltip().get();

    }

    static void pushScale(GuiGraphicsExtractor graphics, float scale) {

        graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);

    }

    static void popScale(GuiGraphicsExtractor graphics) {

        graphics.pose().popMatrix();

    }

    private static boolean shiftDown() {

        return false;

    }

    public static void renderLan(GuiGraphicsExtractor graphics, Font font, int width, int height,
                                 List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children,
                                 int mouseX, int mouseY, float partialTick, long elapsedMillis) {
        int panelWidth = Math.min(360, width - 24);
        int left = (width - panelWidth) / 2;
        var controls = new java.util.ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
        var actions = new java.util.ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
        for (var child : children) {
            if (!(child instanceof net.minecraft.client.gui.components.AbstractWidget widget) || !widget.visible || widget instanceof net.minecraft.client.gui.components.StringWidget) continue;
            if (widget instanceof Button && !(widget instanceof net.minecraft.client.gui.components.CycleButton)) {
                actions.add(widget);
            } else {
                controls.add(widget);
            }
        }
        int pitch = Math.max(10, Math.min(28, (height - 104) / Math.max(1, controls.size())));
        int controlHeight = Math.max(9, Math.min(24, pitch - 3));
        int panelHeight = Math.min(height - 16, 88 + controls.size() * pitch);
        int top = (height - panelHeight) / 2;
        frame(graphics, left, top, panelWidth, panelHeight, PANEL, BORDER);
        graphics.centeredText(font, "PeerCraft", width / 2, top + 10, ACCENT);
        graphics.centeredText(font, Component.translatable("lanServer.title"), width / 2, top + 26, TEXT);
        int y = top + 48;
        for (var widget : controls) {
            widget.setX(left + 12);
            widget.setY(y);
            widget.setWidth(panelWidth - 24);
            ((net.peercraft.client.mixin.PeerCraftWidgetSizeAccessor) widget).peercraft$setHeight(controlHeight);
            if (widget instanceof EditBox field) {
                // The vanilla control order changes between versions (26.2 adds a LAN toggle).
                // Identify our world-name field by its translation key, never its row index.
                boolean worldName = field.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents contents
                        && "peercraft.mixin.share_to_lan.world_name".equals(contents.getKey());
                graphics.text(font, !worldName ? Component.translatable("lanServer.port") :
                        Component.translatable("peercraft.mixin.share_to_lan.world_name"), left + 12, y + Math.max(0, (controlHeight - 9) / 2), MUTED, false);
                widget.setX(left + panelWidth / 2);
                widget.setWidth(panelWidth / 2 - 12);
                field.setBordered(false);
                if (worldName) field.setHint(Component.literal(fitted(font,
                        Component.translatable("peercraft.mixin.share_to_lan.world_name_hint").getString(),
                        Math.max(1, widget.getWidth() - 4))));
                frame(graphics, widget.getX() - 4, y, widget.getWidth() + 4, controlHeight, CONTROL, BORDER);
                field.setY(y + Math.max(0, (controlHeight - 9) / 2));
                try { field.extractRenderState(graphics, mouseX, mouseY, partialTick); }
                finally { field.setY(y); }
            } else {
                boolean hovered = mouseX >= widget.getX() && mouseX < widget.getX() + widget.getWidth()
                        && mouseY >= y && mouseY < y + widget.getHeight();
                frame(graphics, widget.getX(), y, widget.getWidth(), widget.getHeight(),
                        !widget.active ? DISABLED : hovered ? CONTROL_HOVER : CONTROL, hovered ? BORDER_HOVER : BORDER);
                Component label = widget.getMessage();
                if (widget instanceof net.minecraft.client.gui.components.Checkbox checkbox) {
                    label = Component.literal(checkbox.selected() ? "✓ " : "□ ").append(label);
                }
                centered(graphics, font, label, widget.getX() + 6, y, widget.getWidth() - 12,
                        widget.getHeight(), widget.active ? TEXT : MUTED, false);
            }
            y += pitch;
        }
        y = top + panelHeight - 32;
        int actionWidth = (panelWidth - 30) / 2;
        for (int i = 0; i < actions.size(); i++) {
            var widget = actions.get(i);
            widget.setX(left + 12 + i * (actionWidth + 6));
            widget.setY(y);
            widget.setWidth(actionWidth);
            boolean primary = i == 0;
            boolean hovered = widget.isMouseOver(mouseX, mouseY);
            frame(graphics, widget.getX(), y, actionWidth, widget.getHeight(),
                    !widget.active ? DISABLED : primary ? (hovered ? PRIMARY_HOVER : PRIMARY) : CONTROL, BORDER);
            centered(graphics, font, widget.getMessage(), widget.getX() + 4, y, actionWidth - 8,
                    widget.getHeight(), !widget.active ? MUTED : primary ? CONTROL : TEXT, false);
        }
    }

    static void frame(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int fill, int border) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.fill(x, y, x + width, y + height, border);
        if (width > 2 && height > 2) {
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill);
        }
    }

    static void screenBackground(GuiGraphicsExtractor graphics, int screenWidth, int screenHeight,
                                 int panelLeft, int panelTop, int panelWidth, int panelHeight,
                                 long elapsedMillis) {
        graphics.fill(0, 0, screenWidth, screenHeight, BACKGROUND);
        particles(graphics, screenWidth, screenHeight, panelLeft, panelLeft + panelWidth, elapsedMillis);
        frame(graphics, panelLeft, panelTop, panelWidth, panelHeight, PANEL, BORDER);
    }

    static void particles(GuiGraphicsExtractor graphics, int screenWidth, int screenHeight,
                          int panelLeft, int panelRight, long elapsedMillis) {
        if (screenHeight < 40) {
            return;
        }
        double seconds = Math.max(0L, elapsedMillis) / 1000.0;
        particleMargin(graphics, 8, panelLeft - 12, screenHeight, seconds, 0);
        particleMargin(graphics, panelRight + 12, screenWidth - 8, screenHeight, seconds, 17);
    }

    private static void particleMargin(GuiGraphicsExtractor graphics, int left, int right, int height,
                                       double seconds, int seed) {
        if (right - left < 12) {
            return;
        }
        int count = Math.min(10, Math.max(3, height / 42));
        double travel = height + 24.0;
        for (int i = 0; i < count; i++) {
            int id = i + seed;

            double phase = fraction(id * 0.754877666 + 0.13
                    + seconds * (0.021 + fraction(id * 0.414213562) * 0.019));
            double progress = phase;
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
        return action(x, y, width, height, message, onPress, primary, null);
    }

    static Button action(int x, int y, int width, int height, Component message,
                         Button.OnPress onPress, boolean primary, Component narrationLabel) {
        return new Action(x, y, width, height, message, onPress, primary, narrationLabel);
    }

    static Button decorate(Button original, int x, int y, int width, int height, boolean primary) {
        return new DecoratedAction(original, x, y, width, height, primary);
    }

    private static int outline(Button button) {
        if (!button.active) {
            return BORDER;
        }
        return button.isFocused() ? ACCENT : button.isHovered() ? BORDER_HOVER : BORDER;
    }

    private static void buttonFrame(GuiGraphicsExtractor graphics, Button button, boolean primary) {
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

    private static void centered(GuiGraphicsExtractor graphics, Font font, Component message,
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
            graphics.text(font, line, left + (width - font.width(line)) / 2,
                    y + i * font.lineHeight, color, false);
        }
    }

    private static class Action extends Button {
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
            return wrapDefaultNarrationMessage(this.narrationLabel == null ? getMessage() : this.narrationLabel);
        }

        @Override

        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            buttonFrame(graphics, this, this.primary);
            centered(graphics, Minecraft.getInstance().font, getMessage(), getX() + 6, getY(),
                    getWidth() - 12, getHeight(), this.active ? this.primary ? CONTROL : TEXT : MUTED, false);
        }
    }

    private static final class DecoratedAction extends Action {
        private final Button original;
        private final boolean copyIcon;

        private DecoratedAction(Button original, int x, int y, int width, int height, boolean primary) {
            super(x, y, width, height, original.getMessage(), button -> pressOriginal(original), primary, null);
            this.original = original;
            this.copyIcon = original.getWidth() == 14 && original.getHeight() == 14;
            synchronizeState();
        }

        private static void pressOriginal(Button original) {

            original.onPress(new net.minecraft.client.input.KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));

        }

        private void synchronizeState() {
            this.active = this.original.active;
            this.visible = this.original.visible;
            setMessage(this.original.getMessage());
            setTooltip(tooltipOf(this.original));
        }

                @Override
        public void onPress(net.minecraft.client.input.InputWithModifiers input) {
            synchronizeState();
            if (this.active && this.visible) {
                this.original.onPress(input);
                synchronizeState();
            }
        }

                @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            synchronizeState();
            return super.mouseClicked(event, doubleClick);
        }

                @Override
        public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
            synchronizeState();
            return super.keyPressed(event);
        }

        @Override

        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            synchronizeState();
            if (this.copyIcon) {
                buttonFrame(graphics, this, false);
                int x = getX() + (getWidth() - 9) / 2;
                int y = getY() + (getHeight() - 10) / 2;
                frame(graphics, x, y, 7, 8, CONTROL, this.active ? TEXT : MUTED);
                frame(graphics, x + 2, y + 2, 7, 8, CONTROL, this.active ? TEXT : MUTED);
            } else {

                super.extractContents(graphics, mouseX, mouseY, partialTick);
            }
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            return this.copyIcon
                    ? wrapDefaultNarrationMessage(Component.translatable("peercraft.gui.account.copy_code_tooltip"))
                    : super.createNarrationMessage();
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

        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            buttonFrame(graphics, this, false);
            if (getWidth() < 44) {
                frame(graphics, getX() + 3, getY() + 3, 14, 14, CONTROL, this.selected ? ACCENT : BORDER);
                if (this.selected) {
                    graphics.fill(getX() + 6, getY() + 6, getX() + 14, getY() + 14, this.active ? ACCENT : MUTED);
                }
                return;
            }
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
            graphics.text(font, text, getX() + 40, getY() + (getHeight() - font.lineHeight) / 2,
                    this.active ? TEXT : MUTED, false);
        }
    }

    static final class Choice extends Button {
        private final List<String> values;
        private final Function<String, Component> labels;
        private final Consumer<String> onChange;
        private int index;
        private Component narrationLabel;

        Choice(int x, int y, int width, int height, List<String> values, String initial,
               Function<String, Component> labels) {
            this(x, y, width, height, values, initial, labels, value -> { });
        }

        Choice(int x, int y, int width, int height, List<String> values, String initial,
               Function<String, Component> labels, Consumer<String> onChange) {
            super(x, y, width, height, Component.empty(),
                    button -> ((Choice) button).cycle(shiftDown() ? -1 : 1), DEFAULT_NARRATION);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("A settings choice needs at least one value");
            }
            this.values = List.copyOf(values);
            this.labels = Objects.requireNonNull(labels);
            this.onChange = Objects.requireNonNull(onChange);
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

        @Override
        public void onPress(net.minecraft.client.input.InputWithModifiers input) {
            cycle(input.hasShiftDown() ? -1 : 1);
        }

        private void cycle(int direction) {
            this.index = Math.floorMod(this.index + direction, this.values.size());
            setMessage(this.labels.apply(getValue()));
            this.onChange.accept(getValue());
        }

                @Override
        public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            double mouseX = event.x();
            int direction = mouseX < getX() + getWidth() / 2.0 ? -1 : 1;
            cycle(event.hasShiftDown() ? -direction : direction);
        }

                @Override
        public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
            int keyCode = event.key();
            if (this.active && this.visible && isFocused()
                    && (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT)) {
                cycle(keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1);
                playDownSound(Minecraft.getInstance().getSoundManager());
                return true;
            }
            return super.keyPressed(event);
        }

        @Override

        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            buttonFrame(graphics, this, false);
            Font font = Minecraft.getInstance().font;
            int color = this.active ? TEXT : MUTED;
            int centerY = getY() + (getHeight() - 7) / 2;
            int arrowColor = this.active && isHoveredOrFocused() ? ACCENT : BORDER_HOVER;
            chevron(graphics, getX() + 6, centerY, false, arrowColor);
            chevron(graphics, getX() + getWidth() - 10, centerY, true, arrowColor);
            centered(graphics, font, getMessage(), getX() + 16, getY(), getWidth() - 32, getHeight(), color, true);
        }

        private static void chevron(GuiGraphicsExtractor graphics, int x, int y, boolean right, int color) {
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

            return !this.renderingText && super.isBordered();
        }

        @Override
        public int getInnerWidth() {

            return Math.max(0, getWidth() - (super.isBordered() ? 8 : 0));
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            boolean enabled = this.active && this.editable;
            int border = enabled && isFocused() ? ACCENT
                    : enabled && isHovered() ? BORDER_HOVER : BORDER;
            frame(graphics, getX(), getY(), getWidth(), getHeight(), enabled ? CONTROL : DISABLED, border);
            setTextColor(enabled ? TEXT : MUTED);
            this.renderingText = true;
            try {
                super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
            } finally {
                this.renderingText = false;
            }
        }
    }
}*/
//?}
