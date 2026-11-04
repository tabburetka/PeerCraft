package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.peercraft.client.account.ProgressTransferForm;
import net.peercraft.client.handoff.SuccessorLauncher;
import net.peercraft.world.PlayerProgressCatalog;
import org.lwjgl.input.Keyboard;
import java.io.IOException;
import java.util.*;

/** Native screen for explicit assignment of a stopped world's anonymous save. */
public final class PeerCraftProgressTransferScreen extends PeerCraftDialogScreen {
    private final GuiScreen parent;
    private final ProgressTransferForm form = new ProgressTransferForm();
    private GuiTextField code;
    private boolean loaded;
    private int offset, statusY, statusHeight;
    public PeerCraftProgressTransferScreen(GuiScreen parent) {
        super(PeerCraftLang.tr("peercraft.gui.transfer.title"), 280); this.parent = parent;
    }
    private void schedule(Runnable task) { Minecraft.getMinecraft().addScheduledTask(task); }
    @Override public void initGui() {
        String previous = code == null ? form.friendCode : code.getText(); code = null;
        super.initGui(); buttonList.clear(); Keyboard.enableRepeatEvents(true);
        boolean account = form.stage == ProgressTransferForm.Stage.ACCOUNT;
        int rowLimit = Math.max(1, Math.min(4, (height - 140) / 26));
        int naturalStatus = Math.max(36, PeerCraftUi.wrap(fontRenderer, message(), dialog.contentWidth() - 8).size() * 12);
        while (rowLimit > 1 && dialog.headerHeight + 20 + (rowLimit + 3) * dialog.buttonPitch() + naturalStatus > height - 16) rowLimit--;
        final int rows = rowLimit;
        List<?> choices = form.stage == ProgressTransferForm.Stage.WORLDS ? form.worlds : form.players;
        offset = Math.max(0, Math.min(offset, Math.max(0, choices.size() - rows)));
        statusHeight = Math.max(36, PeerCraftUi.wrap(fontRenderer, message(), dialog.contentWidth() - 8).size() * 12);
        int bodyRows = account ? 2 : form.stage == ProgressTransferForm.Stage.COMPLETE ? 0 : rows;
        int actions = account ? 2 : choices.size() > rows && form.stage != ProgressTransferForm.Stage.COMPLETE ? 3 : 1;
        statusHeight = Math.max(9, Math.min(statusHeight, height - 16 - dialog.headerHeight - 20 - (bodyRows + actions) * dialog.buttonPitch()));
        dialog = new SteampunkDialog(width, height, dialog.headerHeight + 6 + bodyRows * dialog.buttonPitch() + statusHeight + actions * dialog.buttonPitch() + 14, PeerCraftLang.tr("peercraft.gui.transfer.title"));
        if (account) {
            code = new SteampunkField(0, fontRenderer, dialog.contentX(), dialog.contentTop() + 12, dialog.contentWidth(), dialog.buttonHeight());
            code.setMaxStringLength(6); code.setText(previous); code.setEnabled(!form.busy); code.setFocused(true);
            dialogAction(PeerCraftLang.tr("peercraft.gui.transfer.verify"), this::lookup, true, 0, actions).enabled = !form.busy;
        } else if (form.stage != ProgressTransferForm.Stage.COMPLETE) {
            for (int row = 0; row < rows && offset + row < choices.size(); row++) {
                final Object choice = choices.get(offset + row);
                String label = choice instanceof PlayerProgressCatalog.World ? ((PlayerProgressCatalog.World) choice).name
                    : PeerCraftLang.tr("peercraft.gui.transfer.player", ((PlayerProgressCatalog.Player) choice).id.toString().substring(0, 8), ((PlayerProgressCatalog.Player) choice).experience);
                IdButton button = IdButton.builder(fontRenderer.trimStringToWidth(label, dialog.contentWidth() - 12), () -> {
                    offset = 0;
                    if (choice instanceof PlayerProgressCatalog.World) form.chooseWorld((PlayerProgressCatalog.World) choice, this::schedule, this::refresh);
                    else { form.choosePlayer((PlayerProgressCatalog.Player) choice); refresh(); }
                }).bounds(dialog.contentX(), dialog.contentTop() + row * dialog.buttonPitch(), dialog.contentWidth(), dialog.buttonHeight()).build();
                button.enabled = !form.busy; buttonList.add(button);
            }
        }
        if (actions == 3) {
            dialogAction(PeerCraftLang.tr("peercraft.gui.transfer.previous"), () -> { offset = Math.max(0, offset - rows); refresh(); }, false, 0, actions).enabled = !form.busy && offset > 0;
            dialogAction(PeerCraftLang.tr("peercraft.gui.transfer.next"), () -> { offset += rows; refresh(); }, false, 1, actions).enabled = !form.busy && offset + rows < choices.size();
        }
        dialogAction(PeerCraftLang.tr("peercraft.gui.common.back"), this::back, false, actions - 1, actions).enabled = !form.busy;
        statusY = dialog.contentTop() + bodyRows * dialog.buttonPitch() + 4;
        if (!loaded) {
            loaded = true;
            if (mc.getIntegratedServer() != null) { form.status = "peercraft.gui.transfer.close_world"; refresh(); }
            else form.loadWorlds(SuccessorLauncher.savesDirectory(), this::schedule, this::refresh);
        }
    }
    private String message() {
        return form.stage == ProgressTransferForm.Stage.ACCOUNT && !form.busy && form.status.equals("peercraft.gui.transfer.enter_account")
            ? PeerCraftLang.tr("peercraft.gui.transfer.details", form.player.id, form.player.experience, form.player.inventory) : PeerCraftLang.tr(form.status);
    }
    private void lookup() { if (code != null) form.lookup(code.getText(), this::schedule, this::refresh, this::confirm); }
    private void confirm() {
        PeerCraftUi.setScreen(mc, new PeerCraftConfirmScreen(accepted -> {
            PeerCraftUi.setScreen(mc, this);
            if (accepted) form.transfer(mc.getIntegratedServer() != null, this::schedule, this::refresh);
        }, PeerCraftLang.tr("peercraft.gui.transfer.confirm_title"), PeerCraftLang.tr("peercraft.gui.transfer.confirm_message", form.world.name, form.player.id, form.targetName, form.friendCode, form.target), PeerCraftLang.tr("peercraft.gui.transfer.confirm"), PeerCraftLang.tr("peercraft.gui.common.back")));
    }
    private void back() {
        if (form.busy) return; offset = 0;
        if (form.stage == ProgressTransferForm.Stage.ACCOUNT) { form.cancelLookup(); form.chooseWorld(form.world, this::schedule, this::refresh); }
        else if (form.stage == ProgressTransferForm.Stage.PLAYERS) form.loadWorlds(SuccessorLauncher.savesDirectory(), this::schedule, this::refresh);
        else PeerCraftUi.setScreen(mc, parent);
    }
    private void refresh() { if (PeerCraftUi.isCurrentScreen(this)) initGui(); }
    @Override public void onGuiClosed() { Keyboard.enableRepeatEvents(false); }
    @Override protected void actionPerformed(GuiButton button) throws IOException { if (button instanceof IdButton && button.enabled) ((IdButton) button).onPress.run(); }
    @Override protected void keyTyped(char typed, int key) throws IOException {
        if (key == Keyboard.KEY_ESCAPE) { back(); return; }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) { if (!form.busy) lookup(); return; }
        if (code != null && code.textboxKeyTyped(typed, key)) return; super.keyTyped(typed, key);
    }
    @Override protected void mouseClicked(int x, int y, int button) throws IOException { super.mouseClicked(x, y, button); if (code != null) code.mouseClicked(x, y, button); }
    @Override public void updateScreen() { if (code != null) code.updateCursorCounter(); }
    @Override public void drawScreen(int x, int y, float delta) {
        drawDefaultBackground();
        if (code != null) { label("peercraft.gui.transfer.friend_code", dialog.contentTop() + 1); code.drawTextBox(); }
        dialog.status(fontRenderer, message(), statusY, statusHeight, PeerCraftUi.TEXT_MUTED); super.drawScreen(x, y, delta);
        if (x >= dialog.contentX() && x < dialog.contentX() + dialog.contentWidth()
                && y >= statusY && y < statusY + statusHeight) {
            java.util.List<String> fullStatus = PeerCraftUi.wrap(fontRenderer, message(), dialog.contentWidth());
            if (fullStatus.size() * fontRenderer.FONT_HEIGHT > statusHeight) drawHoveringText(fullStatus, x, y);
        }
    }
}
