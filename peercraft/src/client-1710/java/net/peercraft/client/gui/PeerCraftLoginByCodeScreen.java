package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.network.account.AccountClient;
import org.lwjgl.input.Keyboard;

import java.util.Locale;

/** Forge 1.7.10 backport of {@code src/main/.../PeerCraftLoginByCodeScreen.java} (twin of the src/client-1122 backport). */
public class PeerCraftLoginByCodeScreen extends GuiScreen {

    private final GuiScreen lastScreen;

    private GuiTextField friendCodeBox;
    private PasswordField passwordBox;
    private IdButton loginButton;
    private String statusMessage = "";
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftLoginByCodeScreen(GuiScreen lastScreen) {
        this.lastScreen = lastScreen;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();
        int centerX = this.width / 2;
        int y = this.height / 2 - 50;

        this.friendCodeBox = new GuiTextField(this.fontRendererObj, centerX - 100, y, 200, 20);
        this.friendCodeBox.setMaxStringLength(6);
        this.friendCodeBox.setFocused(true);

        y += 26;
        this.passwordBox = new PasswordField(this.fontRendererObj, centerX - 100, y, 200, 20);
        this.passwordBox.setMaxStringLength(64);

        y += 26;
        this.loginButton = this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.login_code.submit"), this::onLogin)
                .bounds(centerX - 100, y, 200, 20).build());

        y += 26;
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.common.back"),
                () -> PeerCraftUi.setScreen(this.mc, this.lastScreen)).bounds(centerX - 100, y, 200, 20).build());
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
        if ((keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) && this.loginButton.enabled) {
            onLogin();
            return;
        }
        if (this.friendCodeBox.textboxKeyTyped(typedChar, keyCode) || this.passwordBox.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.friendCodeBox.mouseClicked(mouseX, mouseY, mouseButton);
        this.passwordBox.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void updateScreen() {
        this.friendCodeBox.updateCursorCounter();
        this.passwordBox.updateCursorCounter();
    }

    private void onLogin() {
        String friendCode = this.friendCodeBox.getText().trim().toUpperCase(Locale.ROOT);
        String password = this.passwordBox.getPassword();
        if (friendCode.length() != 6) {
            this.statusMessage = PeerCraftLang.tr("peercraft.gui.login_code.code_length_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        if (password.isEmpty()) {
            this.statusMessage = PeerCraftLang.tr("peercraft.gui.register.password_required");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }

        this.loginButton.enabled = false;
        this.statusMessage = PeerCraftLang.tr("peercraft.gui.login_code.logging_in");
        this.statusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.loginByFriendCode(friendCode, password.toCharArray(), new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession session) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    AccountSessionHolder.persist(session);
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
                    loginButton.enabled = true;
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
        this.friendCodeBox.drawTextBox();
        this.passwordBox.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
        this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.gui.login_code.title"),
                this.width / 2, this.height / 2 - 80, PeerCraftUi.TEXT_TITLE);
        if (!this.statusMessage.isEmpty()) {
            this.drawCenteredString(this.fontRendererObj, this.statusMessage, this.width / 2, this.height / 2 + 60, this.statusColor);
        }
    }
}
