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
    @Override public void drawButton(net.minecraft.client.Minecraft mc, int mouseX, int mouseY) {
        if (!visible) return;
        int size = Math.min(16, Math.min(width, height));
        boolean hover = mouseX >= xPosition && mouseX < xPosition + width && mouseY >= yPosition && mouseY < yPosition + height;
        int border = enabled && hover ? net.peercraft.client.theme.SteampunkPalette.BORDER_HOVER : net.peercraft.client.theme.SteampunkPalette.BORDER;
        net.minecraft.client.gui.Gui.drawRect(xPosition, yPosition, xPosition + size, yPosition + size, border);
        net.minecraft.client.gui.Gui.drawRect(xPosition + 1, yPosition + 1, xPosition + size - 1, yPosition + size - 1, net.peercraft.client.theme.SteampunkPalette.CONTROL);
        if (isChecked()) this.drawCenteredString(mc.fontRenderer, "✓", xPosition + size / 2, yPosition + (size - 8) / 2,
                enabled ? net.peercraft.client.theme.SteampunkPalette.ACCENT : net.peercraft.client.theme.SteampunkPalette.MUTED);
        String label = mc.fontRenderer.trimStringToWidth(displayString, Math.max(0, width - size - 5));
        mc.fontRenderer.drawStringWithShadow(label, xPosition + size + 5, yPosition + (height - 8) / 2,
                enabled ? net.peercraft.client.theme.SteampunkPalette.TEXT : net.peercraft.client.theme.SteampunkPalette.MUTED);
    }
}
