package net.peercraft.client.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

/**
 * 1.12.2 password entry. The modern code masks in place with {@code EditBox.setFormatter} and
 * the 1.16.5 backport with {@code setFormatter(...)}; 1.12.2's {@link GuiTextField} has no
 * formatter hook and its backing text is private, so this keeps the real password in a side
 * buffer and shows only bullets in the widget. Editing is deliberately restricted to
 * append / backspace / paste with the cursor pinned to the end (standard for a password box).
 */
class PasswordField extends GuiTextField {

    private final StringBuilder real = new StringBuilder();
    private int maxLength = 32;

    PasswordField(int componentId, FontRenderer font, int x, int y, int width, int height) {
        super(componentId, font, x, y, width, height);
    }

    @Override
    public void setMaxStringLength(int length) {
        this.maxLength = length;
        super.setMaxStringLength(length);
    }

    /** The actual typed password (the widget itself only ever holds bullets). */
    String getPassword() {
        return real.toString();
    }

    private void resync() {
        StringBuilder dots = new StringBuilder(real.length());
        for (int i = 0; i < real.length(); i++) {
            dots.append('•');
        }
        super.setText(dots.toString());
        super.setCursorPositionEnd();
    }

    @Override
    public boolean textboxKeyTyped(char typedChar, int keyCode) {
        if (!this.isFocused()) {
            return false;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (real.length() > 0) {
                real.deleteCharAt(real.length() - 1);
                resync();
            }
            return true;
        }
        if (GuiScreen.isKeyComboCtrlV(keyCode)) {
            appendFiltered(GuiScreen.getClipboardString());
            return true;
        }
        if (GuiScreen.isKeyComboCtrlC(keyCode) || GuiScreen.isKeyComboCtrlX(keyCode)) {
            // never expose the password to the clipboard
            return true;
        }
        if (ChatAllowedCharacters.isAllowedCharacter(typedChar)) {
            appendFiltered(String.valueOf(typedChar));
            return true;
        }
        // arrows / home / end / tab / esc etc. — let the screen handle navigation keys
        return keyCode == Keyboard.KEY_TAB || keyCode == Keyboard.KEY_ESCAPE
                ? false
                : true;
    }

    private void appendFiltered(String s) {
        for (int i = 0; i < s.length() && real.length() < maxLength; i++) {
            char c = s.charAt(i);
            if (ChatAllowedCharacters.isAllowedCharacter(c)) {
                real.append(c);
            }
        }
        resync();
    }
}
