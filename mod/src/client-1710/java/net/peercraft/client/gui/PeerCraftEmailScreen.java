package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.peercraft.client.account.EmailRecoveryForm;
import org.lwjgl.input.Keyboard;
import java.io.IOException;
import java.util.*;

/** Native Forge adapter for the shared recovery flow. */
public final class PeerCraftEmailScreen extends PeerCraftDialogScreen {
    private final GuiScreen parent;
    private final EmailRecoveryForm form;
    private final List<GuiTextField> fields = new ArrayList<GuiTextField>();
    private final List<String> labels = new ArrayList<String>();
    private EmailRecoveryForm.Stage renderedStage;
    private int statusY, statusHeight;
    public PeerCraftEmailScreen(GuiScreen parent, boolean binding) {
        super(PeerCraftLang.tr(binding ? "peercraft.gui.email.bind_title" : "peercraft.gui.email.reset_title"), 280);
        this.parent = parent; this.form = new EmailRecoveryForm(binding);
    }
    @Override public void initGui() {
        List<String> previous = new ArrayList<String>();
        if (renderedStage == form.stage) for (GuiTextField field : fields) previous.add(value(field));
        fields.clear(); labels.clear(); super.initGui(); buttonList.clear(); renderedStage = form.stage;
        Keyboard.enableRepeatEvents(true);
        int count = form.stage == EmailRecoveryForm.Stage.COMPLETE ? 0 : form.stage == EmailRecoveryForm.Stage.ADDRESS
                ? form.binding ? 2 : 1 : form.binding ? 1 : 3;
        int actions = form.stage == EmailRecoveryForm.Stage.CODE ? 3 : 2;
        statusHeight = Math.max(24, PeerCraftUi.wrap(fontRendererObj, PeerCraftLang.tr(form.status), dialog.contentWidth() - 8).size() * 12);
        int row = dialog.buttonHeight() + 10;
        statusHeight = Math.max(9, Math.min(statusHeight, height - 16 - dialog.headerHeight - 20 - count * row - actions * dialog.buttonPitch()));
        dialog = new SteampunkDialog(width, height, dialog.headerHeight + 6 + count * row + statusHeight + actions * dialog.buttonPitch() + 14,
                PeerCraftLang.tr(form.binding ? "peercraft.gui.email.bind_title" : "peercraft.gui.email.reset_title"));
        for (int i = 0; i < count; i++) {
            boolean password = form.stage == EmailRecoveryForm.Stage.ADDRESS ? i == 1 : i > 0;
            String label = form.stage == EmailRecoveryForm.Stage.ADDRESS ? i == 0 ? "address" : "current_password"
                    : i == 0 ? "code" : i == 1 ? "new_password" : "repeat_password";
            labels.add("peercraft.gui.email." + label);
            int y = dialog.contentTop() + 12 + i * row;
            GuiTextField field = password ? new PasswordField(fontRendererObj, dialog.contentX(), y, dialog.contentWidth(), dialog.buttonHeight())
                    : new SteampunkField(fontRendererObj, dialog.contentX(), y, dialog.contentWidth(), dialog.buttonHeight());
            field.setMaxStringLength(password ? 64 : form.stage == EmailRecoveryForm.Stage.ADDRESS ? 254 : 8);
            String text = i < previous.size() ? previous.get(i) : i == 0 && form.stage == EmailRecoveryForm.Stage.ADDRESS ? form.email : "";
            if (password) ((PasswordField) field).setPassword(text); else field.setText(text);
            field.setEnabled(!form.busy); field.setFocused(i == 0); fields.add(field);
        }
        statusY = dialog.contentTop() + count * row + 4;
        IdButton submit = dialogAction(PeerCraftLang.tr(form.stage == EmailRecoveryForm.Stage.COMPLETE
                ? form.binding ? "gui.done" : "peercraft.gui.email.login"
                : form.stage == EmailRecoveryForm.Stage.ADDRESS ? "peercraft.gui.email.send" : "peercraft.gui.email.confirm"), this::submit, true, 0, actions);
        submit.enabled = !form.busy;
        if (actions == 3) {
            IdButton restart = dialogAction(PeerCraftLang.tr("peercraft.gui.email.restart"), () -> { form.restart(); refresh(); }, false, 1, actions);
            restart.enabled = !form.busy;
        }
        dialogAction(PeerCraftLang.tr("peercraft.gui.common.back"), this::close, false, actions - 1, actions);
    }
    private String value(GuiTextField field) { return field instanceof PasswordField ? ((PasswordField) field).getPassword() : field.getText(); }
    private void submit() {
        if (form.busy) return;
        if (form.stage == EmailRecoveryForm.Stage.COMPLETE) {
            if (form.binding) close();
            else PeerCraftUi.setScreen(mc, new PeerCraftLoginByCodeScreen(parent, form.restored.accountId.toString()));
        } else if (form.stage == EmailRecoveryForm.Stage.ADDRESS) form.begin(value(fields.get(0)),
                form.binding ? value(fields.get(1)).toCharArray() : new char[0], task -> Minecraft.getMinecraft().func_152344_a(task), this::refresh);
        else form.confirm(value(fields.get(0)), form.binding ? new char[0] : value(fields.get(1)).toCharArray(),
                form.binding ? new char[0] : value(fields.get(2)).toCharArray(), task -> Minecraft.getMinecraft().func_152344_a(task), this::refresh);
    }
    private void refresh() { if (PeerCraftUi.isCurrentScreen(this)) initGui(); }
    private void close() { PeerCraftUi.setScreen(mc, parent); }
    @Override public void onGuiClosed() {
        for (GuiTextField field : fields) { if (field instanceof PasswordField) ((PasswordField) field).setPassword(""); else field.setText(""); }
        Keyboard.enableRepeatEvents(false);
    }
    @Override protected void actionPerformed(GuiButton button)  {
        if (button instanceof IdButton) ((IdButton) button).onPress.run();
    }
    @Override protected void keyTyped(char typed, int key)  {
        if (key == Keyboard.KEY_ESCAPE) { close(); return; }
        if (key == Keyboard.KEY_TAB && !fields.isEmpty()) {
            int focus = 0; for (int i = 0; i < fields.size(); i++) if (fields.get(i).isFocused()) focus = i;
            fields.get(focus).setFocused(false); fields.get((focus + 1) % fields.size()).setFocused(true); return;
        }
        if ((key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) && !form.busy) { submit(); return; }
        for (GuiTextField field : fields) if (field.textboxKeyTyped(typed, key)) return;
        super.keyTyped(typed, key);
    }
    @Override protected void mouseClicked(int x, int y, int button)  {
        super.mouseClicked(x, y, button); for (GuiTextField field : fields) field.mouseClicked(x, y, button);
    }
    @Override public void updateScreen() { for (GuiTextField field : fields) field.updateCursorCounter(); }
    @Override public void drawScreen(int mouseX, int mouseY, float delta) {
        drawDefaultBackground();
        for (int i = 0; i < fields.size(); i++) {
            label(labels.get(i), dialog.contentTop() + 1 + i * (dialog.buttonHeight() + 10)); fields.get(i).drawTextBox();
        }
        dialog.status(fontRendererObj, PeerCraftLang.tr(form.status), statusY, statusHeight, form.status.contains(".error.") ? PeerCraftUi.TEXT_ERROR : PeerCraftUi.TEXT_MUTED);
        super.drawScreen(mouseX, mouseY, delta);
        if (mouseX >= dialog.contentX() && mouseX < dialog.contentX() + dialog.contentWidth()
                && mouseY >= statusY && mouseY < statusY + statusHeight) {
            java.util.List<String> fullStatus = PeerCraftUi.wrap(fontRendererObj, PeerCraftLang.tr(form.status), dialog.contentWidth());
            if (fullStatus.size() * fontRendererObj.FONT_HEIGHT > statusHeight) drawHoveringText(fullStatus, mouseX, mouseY, fontRendererObj);
        }
    }
}
