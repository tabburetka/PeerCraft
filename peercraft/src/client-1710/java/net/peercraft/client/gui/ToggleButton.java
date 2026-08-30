package net.peercraft.client.gui;

import cpw.mods.fml.client.config.GuiCheckBox;

import java.util.function.Consumer;

/**
 * 1.7.10 checkbox with an after-toggle callback (twin of the {@code src/client-1122}
 * {@code ToggleButton}). 1.7.10 has {@link GuiCheckBox} — a {@code GuiButton} subclass that
 * flips {@code isChecked()} on click — but under {@code cpw.mods.fml.client.config} (the FML
 * package moved to {@code net.minecraftforge.fml} only in 1.8). Like every other 1.7.10 widget
 * it carries no callback, so the owning screen forwards clicks from
 * {@code actionPerformed(GuiButton)}:
 * {@code if (b instanceof ToggleButton) ((ToggleButton) b).fire();}
 *
 * <p>Mutually-exclusive checkboxes (friends-only / public-room) are handled by disabling the
 * other one, never by force-unchecking — same as the original.
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
