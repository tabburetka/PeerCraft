package net.peercraft.client.gui;

import net.minecraftforge.fml.client.config.GuiCheckBox;

import java.util.function.Consumer;

/**
 * 1.12.2 checkbox with an after-toggle callback. 1.12.2 has {@link GuiCheckBox} (a
 * {@code GuiButton} subclass that flips {@code isChecked()} on click) but, like every other
 * 1.12.2 widget, no callback — the owning screen forwards clicks from
 * {@code actionPerformed(GuiButton)}:
 * {@code if (b instanceof ToggleButton) ((ToggleButton) b).fire();}
 *
 * <p>Mirrors {@code CallbackCheckbox} in the 1.16.5 backport. Mutually-exclusive checkboxes
 * (friends-only / public-room) are handled by disabling the other one, never by
 * force-unchecking — same as the original.
 */
class ToggleButton extends GuiCheckBox {

    private static int nextId = 9500;

    private final Consumer<Boolean> onChange;

    ToggleButton(int x, int y, String label, boolean initial, Consumer<Boolean> onChange) {
        super(nextId++, x, y, label, initial);
        this.onChange = onChange;
    }

    /** Called by the owning screen's {@code actionPerformed} after the click flipped the state. */
    void fire() {
        onChange.accept(this.isChecked());
    }
}
