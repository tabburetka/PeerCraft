package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.account.ProgressTransferForm;
import net.peercraft.client.handoff.SuccessorLauncher;
import net.peercraft.world.PlayerProgressCatalog;
import java.util.*;

/** Host chooses a closed world, its old save and a verified account before confirming. */
public final class PeerCraftProgressTransferScreen extends PeerCraftDialogScreen {
    private final Screen parent;
    private final ProgressTransferForm form = new ProgressTransferForm();
    private EditBox code;
    private int offset, statusY, statusHeight;
    private boolean loaded;
    public PeerCraftProgressTransferScreen(Screen parent) { super(Component.translatable("peercraft.gui.transfer.title")); this.parent = parent; }
    @Override protected void init() {
        String previous = code == null ? form.friendCode : code.getValue(); code = null; super.init();
        boolean account = form.stage == ProgressTransferForm.Stage.ACCOUNT;
        int rowLimit = Math.max(1, Math.min(4, (height - 140) / 26));
        int naturalStatus = Math.max(36, font.split(status(), dialog.contentWidth() - 8).size() * 12);
        while (rowLimit > 1 && dialog.headerHeight + 20 + (rowLimit + 3) * dialog.buttonPitch() + naturalStatus > height - 16) rowLimit--;
        final int rows = rowLimit;
        List<?> choices = form.stage == ProgressTransferForm.Stage.WORLDS ? form.worlds : form.players;
        offset = Math.max(0, Math.min(offset, Math.max(0, choices.size() - rows)));
        Component status = status();
        statusHeight = Math.max(36, font.split(status, dialog.contentWidth() - 8).size() * 12);
        int bodyRows = account ? 2 : form.stage == ProgressTransferForm.Stage.COMPLETE ? 0 : rows;
        int actions = account ? 2 : choices.size() > rows && form.stage != ProgressTransferForm.Stage.COMPLETE ? 3 : 1;
        statusHeight = Math.max(9, Math.min(statusHeight, height - 16 - dialog.headerHeight - 20 - (bodyRows + actions) * dialog.buttonPitch()));
        dialog = new SteampunkDialog(width, height, dialog.headerHeight + 6 + bodyRows * dialog.buttonPitch() + statusHeight + actions * dialog.buttonPitch() + 14, title);
        if (account) {
            code = new SteampunkSettingsTheme.Field(font, dialog.contentX(), dialog.contentTop() + 12, dialog.contentWidth(), dialog.buttonHeight(),
                    Component.translatable("peercraft.gui.transfer.friend_code"));
            code.setMaxLength(6); code.setValue(previous); code.setEditable(!form.busy); addRenderableWidget(code); setInitialFocus(code);
            Button verify = addRenderableWidget(dialogAction(Component.translatable("peercraft.gui.transfer.verify"), b -> form.lookup(code.getValue(),
                    Minecraft.getInstance()::execute, this::refresh, this::confirm), true, 0, actions)); verify.active = !form.busy;
        } else if (form.stage != ProgressTransferForm.Stage.COMPLETE) {
            for (int row = 0; row < rows && offset + row < choices.size(); row++) {
                final Object choice = choices.get(offset + row);
                String label = choice instanceof PlayerProgressCatalog.World ? ((PlayerProgressCatalog.World) choice).name
                        : Component.translatable("peercraft.gui.transfer.player", ((PlayerProgressCatalog.Player) choice).id.toString().substring(0, 8),
                                ((PlayerProgressCatalog.Player) choice).experience).getString();
                Button select = addRenderableWidget(SteampunkSettingsTheme.action(dialog.contentX(), dialog.contentTop() + row * dialog.buttonPitch(),
                        dialog.contentWidth(), dialog.buttonHeight(), Component.literal(font.plainSubstrByWidth(label, dialog.contentWidth() - 12)), b -> {
                            offset = 0;
                            if (choice instanceof PlayerProgressCatalog.World) form.chooseWorld((PlayerProgressCatalog.World) choice, Minecraft.getInstance()::execute, this::refresh);
                            else { form.choosePlayer((PlayerProgressCatalog.Player) choice); refresh(); }
                        }, false)); select.active = !form.busy;
            }
        }
        if (actions == 3) {
            Button previousPage = addRenderableWidget(dialogAction(Component.translatable("peercraft.gui.transfer.previous"), b -> { offset = Math.max(0, offset - rows); refresh(); }, false, 0, actions));
            previousPage.active = !form.busy && offset > 0;
            Button nextPage = addRenderableWidget(dialogAction(Component.translatable("peercraft.gui.transfer.next"), b -> { offset += rows; refresh(); }, false, 1, actions));
            nextPage.active = !form.busy && offset + rows < choices.size();
        }
        Button back = addRenderableWidget(dialogAction(Component.translatable("peercraft.gui.common.back"), b -> back(), false, actions - 1, actions));
        back.active = !form.busy; statusY = dialog.contentTop() + bodyRows * dialog.buttonPitch() + 4;
        if (!loaded) {
            loaded = true;
            if (minecraft.getSingleplayerServer() != null) { form.status = "peercraft.gui.transfer.close_world"; refresh(); }
            else form.loadWorlds(SuccessorLauncher.savesDirectory(), Minecraft.getInstance()::execute, this::refresh);
        }
    }
    private Component status() {
        if (form.stage == ProgressTransferForm.Stage.ACCOUNT && !form.busy && form.status.equals("peercraft.gui.transfer.enter_account"))
            return Component.translatable("peercraft.gui.transfer.details", form.player.id, form.player.experience, form.player.inventory);
        return Component.translatable(form.status);
    }
    private void confirm() {
        PeerCraftUi.setScreen(minecraft, new PeerCraftConfirmScreen(accepted -> {
            PeerCraftUi.setScreen(minecraft, this);
            if (accepted) form.transfer(minecraft.getSingleplayerServer() != null, Minecraft.getInstance()::execute, this::refresh);
        }, Component.translatable("peercraft.gui.transfer.confirm_title"), Component.translatable("peercraft.gui.transfer.confirm_message",
                form.world.name, form.player.id, form.targetName, form.friendCode, form.target),
                Component.translatable("peercraft.gui.transfer.confirm"), Component.translatable("peercraft.gui.common.back")));
    }
    private void back() {
        if (form.busy) return;
        offset = 0;
        if (form.stage == ProgressTransferForm.Stage.ACCOUNT) {
            form.cancelLookup(); form.chooseWorld(form.world, Minecraft.getInstance()::execute, this::refresh);
        } else if (form.stage == ProgressTransferForm.Stage.PLAYERS) form.loadWorlds(SuccessorLauncher.savesDirectory(), Minecraft.getInstance()::execute, this::refresh);
        else PeerCraftUi.setScreen(minecraft, parent);
    }
    @Override public void onClose() { back(); }
    private void refresh() { if (PeerCraftUi.isCurrentScreen(this)) { clearWidgets(); init(); } }
    //? if <26.1 {
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        //? if <1.21.6
        renderBackground(graphics, mouseX, mouseY, delta);
        super.render(graphics, mouseX, mouseY, delta);
        if (code != null) graphics.drawString(font, Component.translatable("peercraft.gui.transfer.friend_code"), dialog.contentX(), code.getY() - 11, PeerCraftUi.TEXT_MUTED, false);
        dialog.status(graphics, font, status(), statusY, statusHeight, PeerCraftUi.TEXT_MUTED, mouseX, mouseY);
    }
    //?} else {
    /*@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (code != null) graphics.text(font, Component.translatable("peercraft.gui.transfer.friend_code"), dialog.contentX(), code.getY() - 11, PeerCraftUi.TEXT_MUTED, false);
        dialog.status(graphics, font, status(), statusY, statusHeight, PeerCraftUi.TEXT_MUTED, mouseX, mouseY);
    }*/
    //?}
}
