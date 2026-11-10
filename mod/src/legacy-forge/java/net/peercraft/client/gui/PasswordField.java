package net.peercraft.client.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

/**
 //? if >=1.12 {
 * 1.12.2 password entry. The modern code masks in place with {@code EditBox.setFormatter} and
 * the 1.16.5 backport with {@code setFormatter(...)}; 1.12.2's {@link GuiTextField} has no
 * formatter hook and its backing text is private, so this keeps the real password in a side
 * buffer and shows only bullets in the widget. Editing is deliberately restricted to
 * append / backspace / paste with the cursor pinned to the end (standard for a password box).
 //?} else {
//~  * 1.7.10 password entry (twin of the {@code src/client-1122} {@code PasswordField}). 1.7.10's
//~  * {@link GuiTextField} has no formatter hook and its backing text is private, so this keeps the
//~  * real password in a side buffer and shows only bullets in the widget. Editing is deliberately
//~  * restricted to append / backspace / paste with the cursor pinned to the end (standard for a
//~  * password box).
//~  *
//~  * <p>1.7.10 deltas vs the 1.12.2 twin: {@link GuiTextField}'s constructor has no leading
//~  * {@code componentId} parameter (that arrived in 1.8), so neither does this class.
 //?}
 */
class PasswordField extends SteampunkField {

    private final StringBuilder real = new StringBuilder();
    private int maxLength = 32;

    //? if >=1.12 {
    PasswordField(int componentId, FontRenderer font, int x, int y, int width, int height) {
        super(componentId, font, x, y, width, height);
    //?} else {
//~     PasswordField(FontRenderer font, int x, int y, int width, int height) {
//~         super(font, x, y, width, height);
    //?}
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

    void setPassword(String value) {
        real.setLength(0);
        appendFiltered(value);
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
        //? if >=1.12 {
        if (GuiScreen.isKeyComboCtrlV(keyCode)) {
        //?} else {
//~         // 1.7.10 has no GuiScreen.isKeyComboCtrlV/C/X helpers (added in 1.8) — check by hand.
//~         if (GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_V) {
        //?}
            appendFiltered(GuiScreen.getClipboardString());
            return true;
        }
        //? if >=1.12 {
        if (GuiScreen.isKeyComboCtrlC(keyCode) || GuiScreen.isKeyComboCtrlX(keyCode)) {
        //?} else {
//~         if (GuiScreen.isCtrlKeyDown() && (keyCode == Keyboard.KEY_C || keyCode == Keyboard.KEY_X)) {
        //?}
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
