package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.account.EmailRecoveryForm;
import java.util.*;

/** Existing PeerCraft dialog style, with separate address and mailbox-code steps. */
public final class PeerCraftEmailScreen extends PeerCraftDialogScreen {
    private final Screen parent;
    private final EmailRecoveryForm form;
    private final List<EditBox> fields = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private int statusY, statusHeight;
    private EmailRecoveryForm.Stage renderedStage;
    public PeerCraftEmailScreen(Screen parent, boolean binding) {
        super(Component.translatable(binding ? "peercraft.gui.email.bind_title" : "peercraft.gui.email.reset_title"));
        this.parent = parent; form = new EmailRecoveryForm(binding);
    }
    @Override protected void init() {
        List<String> previous = new ArrayList<>();
        if (renderedStage == form.stage) for (EditBox field : fields) previous.add(field.getValue());
        fields.clear(); labels.clear(); super.init(); renderedStage = form.stage;
        int count = form.stage == EmailRecoveryForm.Stage.COMPLETE ? 0 : form.stage == EmailRecoveryForm.Stage.ADDRESS
                ? form.binding ? 2 : 1 : form.binding ? 1 : 3;
        int actions = form.stage == EmailRecoveryForm.Stage.CODE ? 3 : 2;
        Component message = Component.translatable(form.status);
        statusHeight = Math.max(24, font.split(message, dialog.contentWidth() - 8).size() * 12);
        int row = dialog.buttonHeight() + 10;
        statusHeight = Math.max(9, Math.min(statusHeight, height - 16 - dialog.headerHeight - 20 - count * row - actions * dialog.buttonPitch()));
        dialog = new SteampunkDialog(width, height, dialog.headerHeight + 6 + count * row + statusHeight + actions * dialog.buttonPitch() + 14, title);
        for (int i = 0; i < count; i++) {
            boolean password = form.stage == EmailRecoveryForm.Stage.ADDRESS ? i == 1 : i > 0;
            String label = form.stage == EmailRecoveryForm.Stage.ADDRESS ? i == 0 ? "address" : "current_password"
                    : i == 0 ? "code" : i == 1 ? "new_password" : "repeat_password";
            labels.add("peercraft.gui.email." + label);
            EditBox field = new SteampunkSettingsTheme.Field(font, dialog.contentX(), dialog.contentTop() + 12 + i * row,
                    dialog.contentWidth(), dialog.buttonHeight(), Component.translatable(labels.get(i)));
            field.setMaxLength(password ? 64 : form.stage == EmailRecoveryForm.Stage.ADDRESS ? 254 : 8);
            if (password) PeerCraftUi.maskAsPassword(field);
            field.setValue(i < previous.size() ? previous.get(i) : i == 0 && form.stage == EmailRecoveryForm.Stage.ADDRESS ? form.email : "");
            field.setEditable(!form.busy); fields.add(field); addRenderableWidget(field);
        }
        if (!fields.isEmpty()) setInitialFocus(fields.get(0));
        statusY = dialog.contentTop() + count * row + 4;
        if (form.stage == EmailRecoveryForm.Stage.COMPLETE) {
            addRenderableWidget(dialogAction(Component.translatable(form.binding ? "gui.done" : "peercraft.gui.email.login"), b -> {
                if (form.binding) onClose();
                else PeerCraftUi.setScreen(minecraft, new PeerCraftLoginByCodeScreen(parent, form.restored.accountId.toString()));
            }, true, 0, actions));
        } else {
            net.minecraft.client.gui.components.Button submit = addRenderableWidget(dialogAction(Component.translatable(
                    form.stage == EmailRecoveryForm.Stage.ADDRESS ? "peercraft.gui.email.send" : "peercraft.gui.email.confirm"), b -> submit(), true, 0, actions));
            submit.active = !form.busy;
        }
        if (actions == 3) {
            net.minecraft.client.gui.components.Button restart = addRenderableWidget(dialogAction(Component.translatable("peercraft.gui.email.restart"), b -> {
                form.restart(); refresh();
            }, false, 1, actions)); restart.active = !form.busy;
        }
        addRenderableWidget(dialogAction(Component.translatable("peercraft.gui.common.back"), b -> onClose(), false, actions - 1, actions));
    }
    private void submit() {
        if (form.stage == EmailRecoveryForm.Stage.ADDRESS) form.begin(fields.get(0).getValue(),
                form.binding ? fields.get(1).getValue().toCharArray() : new char[0], Minecraft.getInstance()::execute, this::refresh);
        else form.confirm(fields.get(0).getValue(), form.binding ? new char[0] : fields.get(1).getValue().toCharArray(),
                form.binding ? new char[0] : fields.get(2).getValue().toCharArray(), Minecraft.getInstance()::execute, this::refresh);
    }
    private void refresh() {
        if (!PeerCraftUi.isCurrentScreen(this)) return;
        if (renderedStage != form.stage) for (EditBox field : fields) field.setValue("");
        clearWidgets(); init();
    }
    @Override public void onClose() { for (EditBox field : fields) field.setValue(""); PeerCraftUi.setScreen(minecraft, parent); }
    //? if <26.1 {
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        //? if <1.21.6
        renderBackground(graphics, mouseX, mouseY, delta);
        super.render(graphics, mouseX, mouseY, delta);
        for (int i = 0; i < fields.size(); i++) graphics.drawString(font, Component.translatable(labels.get(i)),
                dialog.contentX(), fields.get(i).getY() - 11, SteampunkSettingsTheme.MUTED, false);
        dialog.status(graphics, font, Component.translatable(form.status), statusY, statusHeight,
                form.status.contains(".error.") ? PeerCraftUi.TEXT_ERROR : PeerCraftUi.TEXT_MUTED, mouseX, mouseY);
    }
    //?} else {
    /*@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        for (int i = 0; i < fields.size(); i++) graphics.text(font, Component.translatable(labels.get(i)),
                dialog.contentX(), fields.get(i).getY() - 11, SteampunkSettingsTheme.MUTED, false);
        dialog.status(graphics, font, Component.translatable(form.status), statusY, statusHeight,
                form.status.contains(".error.") ? PeerCraftUi.TEXT_ERROR : PeerCraftUi.TEXT_MUTED, mouseX, mouseY);
    }*/
    //?}
}
