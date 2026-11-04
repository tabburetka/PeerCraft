package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.network.account.AccountClient;
import org.lwjgl.input.Keyboard;


/**
 * Forge 1.7.10 backport of {@code src/main/.../PeerCraftRenameScreen.java} (twin of the
 * {@code src/client-1122} backport). Rename for unlicensed accounts only. Same shape as the
 * 1.12.2 twin — the GuiScreen API this screen touches ({@code initGui/drawScreen/keyTyped/
 * updateScreen}, hand-wired {@code GuiTextField}, {@code addScheduledTask}) is unchanged
 * between 1.7.10 and 1.12.2; only {@code fontRenderer} → {@code fontRendererObj},
 * {@code GuiTextField}'s dropped {@code componentId} ctor arg, and the removed
 * {@code throws IOException} on the input callbacks differ.
 */
public class PeerCraftRenameScreen extends PeerCraftDialogScreen {

    private final GuiScreen lastScreen;

    private GuiTextField newNameBox;
    private IdButton saveButton;
    private String statusMessage = "";
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftRenameScreen(GuiScreen lastScreen) {
        super(PeerCraftLang.tr("peercraft.gui.rename.title"), 230);
        this.lastScreen = lastScreen;
    }

    @Override
    public void initGui() {
        String previous = this.newNameBox == null ? null : this.newNameBox.getText();
        boolean enabled = this.saveButton == null || this.saveButton.enabled;
        super.initGui();
        boolean compact = height < 300;
        int desiredHeight = (compact ? 36 : 46) + 6 + 12 + 2 * (compact ? 22 : 30)
                + 36 + 8 + (compact ? 18 : 24) + 12;
        dialog = new SteampunkDialog(width, height, desiredHeight, PeerCraftLang.tr("peercraft.gui.rename.title"));
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();
        int x = dialog.contentX(), w = dialog.contentWidth(), y = dialog.contentTop() + 12;
        this.newNameBox = new SteampunkField(this.fontRendererObj, x, y, w, dialog.buttonHeight());
        this.newNameBox.setMaxStringLength(16);
        AccountClient.AccountSession session = AccountSessionHolder.current();
        this.newNameBox.setText(previous != null ? previous : session == null ? "" : session.displayName());
        this.newNameBox.setFocused(true);
        y += dialog.buttonPitch();
        this.saveButton = this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.rename.save"), this::onSave)
                .primary().bounds(x, y, w, dialog.buttonHeight()).build());
        this.saveButton.enabled = enabled;
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.common.back"),
                () -> PeerCraftUi.setScreen(this.mc, this.lastScreen)).bounds(x, backY(), w, dialog.buttonHeight()).build());
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @SuppressWarnings("unchecked")
    private <T extends GuiButton> T addButton(T button) {
        this.buttonList.add(button);
        return button;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) { PeerCraftUi.setScreen(this.mc, this.lastScreen); return; }
        if ((keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) && this.saveButton.enabled) {
            onSave();
            return;
        }
        if (this.newNameBox.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.newNameBox.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void updateScreen() {
        this.newNameBox.updateCursorCounter();
    }

    private void onSave() {
        String newName = this.newNameBox.getText().trim();
        if (newName.length() < 3 || newName.length() > 16) {
            this.statusMessage = PeerCraftLang.tr("peercraft.gui.register.nickname_length_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        if (!PeerCraftUi.isValidUsername(newName)) {
            this.statusMessage = PeerCraftLang.tr("peercraft.gui.register.nickname_charset_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }

        this.saveButton.enabled = false;
        this.statusMessage = PeerCraftLang.tr("peercraft.gui.rename.saving");
        this.statusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.renameDisplayName(newName, new AccountClient.RenameCallback() {
            @Override
            public void onSuccess(String appliedName) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    AccountClient.AccountSession updated = AccountSessionHolder.current();
                    if (updated != null) {
                        AccountSessionHolder.persist(updated);
                    }
                    PeerCraftUi.setScreen(mc, new PeerCraftAccountScreen(lastScreen));
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    statusMessage = reason;
                    statusColor = PeerCraftUi.TEXT_ERROR;
                    saveButton.enabled = true;
                });
            }
        });
    }

    private void runOnClientThread(Runnable action) {
        Minecraft.getMinecraft().func_152344_a(action);
    }

    private boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        label("peercraft.gui.rename.field", dialog.contentTop());
        this.newNameBox.drawTextBox();
        status(this.statusMessage, dialog.contentTop() + 12 + dialog.buttonPitch() * 2 + 0, this.statusColor);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
