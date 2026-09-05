package net.peercraft.client.gui;

import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 1.16.5 has no {@code Checkbox.builder(...).onValueChange(...)} (that fluent API arrived with
 * 1.20.1). This is the tiny stand-in the mod-sync confirm screen uses: a plain 1.16.5
 * {@link Checkbox} that also fires a callback with the new state after each toggle, so the
 * backported {@code ModSyncConfirmScreen} can read like its {@code src/main} original.
 */
final class ModSyncCheckbox extends Checkbox {

    private final Consumer<Boolean> onToggle;

    ModSyncCheckbox(int x, int y, int width, int height, Component message, boolean selected, Consumer<Boolean> onToggle) {
        super(x, y, width, height, message, selected);
        this.onToggle = onToggle;
    }

    @Override
    public void onPress() {
        super.onPress();
        onToggle.accept(this.selected());
    }
}
