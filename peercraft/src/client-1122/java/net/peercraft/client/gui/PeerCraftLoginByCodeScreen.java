package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.network.account.AccountClient;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.Locale;

/** Forge 1.12.2 backport of {@code src/main/.../PeerCraftLoginByCodeScreen.java} (cf. 1.16.5 twin). */
public class PeerCraftLoginByCodeScreen extends PeerCraftDialogScreen {

    private final GuiScreen lastScreen;

    private GuiTextField friendCodeBox;
    private PasswordField passwordBox;
    private IdButton loginButton;
    private String statusMessage = "";
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftLoginByCodeScreen(GuiScreen lastScreen) {
        super(PeerCraftLang.tr("peercraft.gui.login_code.title"), 300);
        this.lastScreen = lastScreen;
    }

    @Override
    public void initGui() {
        String previous = this.friendCodeBox == null ? null : this.friendCodeBox.getText();
        boolean enabled = this.loginButton == null || this.loginButton.enabled;
        String previousPassword = this.passwordBox == null ? "" : this.passwordBox.getPassword();
        super.initGui();
        boolean compact = height < 300;
        int desiredHeight = (compact ? 36 : 46) + 6 + 24 + 3 * (compact ? 22 : 30)
                + 36 + 8 + (compact ? 18 : 24) + 12;
        dialog = new SteampunkDialog(width, height, desiredHeight, PeerCraftLang.tr("peercraft.gui.login_code.title"));
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();
        int x = dialog.contentX(), w = dialog.contentWidth(), y = dialog.contentTop() + 12;
        this.friendCodeBox = new SteampunkField(0, this.fontRenderer, x, y, w, dialog.buttonHeight());
        this.friendCodeBox.setMaxStringLength(6);
        this.friendCodeBox.setText(previous == null ? "" : previous);
        this.friendCodeBox.setFocused(true);
        y += dialog.buttonPitch() + 12;
        this.passwordBox = new PasswordField(1, this.fontRenderer, x, y, w, dialog.buttonHeight());
        this.passwordBox.setMaxStringLength(64);
        this.passwordBox.setPassword(previousPassword);
        y += dialog.buttonPitch();
        this.loginButton = this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.login_code.submit"), this::onLogin)
                .primary().bounds(x, y, w, dialog.buttonHeight()).build());
        this.loginButton.enabled = enabled;
        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.common.back"),
                () -> PeerCraftUi.setScreen(this.mc, this.lastScreen)).bounds(x, backY(), w, dialog.buttonHeight()).build());
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) { PeerCraftUi.setScreen(this.mc, this.lastScreen); return; }
        if (keyCode == Keyboard.KEY_TAB) {
            boolean passwordFocused = this.passwordBox.isFocused();
            this.passwordBox.setFocused(!passwordFocused);
            this.friendCodeBox.setFocused(passwordFocused);
            return;
        }
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
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
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
        Minecraft.getMinecraft().addScheduledTask(action);
    }

    private boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        label("peercraft.gui.login_code.friend_code_hint", dialog.contentTop());
        this.friendCodeBox.drawTextBox();
        label("peercraft.gui.register.password_field", dialog.contentTop() + dialog.buttonPitch() + 12);
        this.passwordBox.drawTextBox();
        status(this.statusMessage, dialog.contentTop() + 12 + dialog.buttonPitch() * 3 + 12, this.statusColor);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
