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
public class PeerCraftRenameScreen extends GuiScreen {

    private final GuiScreen lastScreen;

    private GuiTextField newNameBox;
    private IdButton saveButton;
    private String statusMessage = "";
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftRenameScreen(GuiScreen lastScreen) {
        this.lastScreen = lastScreen;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();

        int centerX = this.width / 2;
        int y = this.height / 2 - 30;

        this.newNameBox = new GuiTextField(this.fontRendererObj, centerX - 100, y, 200, 20);
        this.newNameBox.setMaxStringLength(16);
        AccountClient.AccountSession session = AccountSessionHolder.current();
        if (session != null) {
            this.newNameBox.setText(session.displayName());
        }
        this.newNameBox.setFocused(true);

        y += 26;
        this.saveButton = this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.rename.save"), this::onSave)
                .bounds(centerX - 100, y, 200, 20).build());

        y += 26;
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.common.back"),
                        () -> PeerCraftUi.setScreen(this.mc, this.lastScreen))
                .bounds(centerX - 100, y, 200, 20).build());
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
        this.newNameBox.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
        this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.gui.rename.title"),
                this.width / 2, this.height / 2 - 60, PeerCraftUi.TEXT_TITLE);
        this.drawCenteredString(this.fontRendererObj, this.statusMessage,
                this.width / 2, this.height / 2 + 40, this.statusColor);
    }
}
