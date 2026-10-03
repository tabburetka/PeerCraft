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

/** Forge 1.12.2 backport of {@code src/main/.../PeerCraftRegisterScreen.java} (cf. 1.16.5 twin). */
public class PeerCraftRegisterScreen extends PeerCraftDialogScreen {

    private final GuiScreen lastScreen;
    /** null while the form is showing; set to the assigned code once registration succeeds. */
    private final String registeredFriendCode;

    private GuiTextField nicknameBox;
    private PasswordField passwordBox;
    private IdButton registerButton;
    private String statusMessage = "";
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftRegisterScreen(GuiScreen lastScreen) {
        this(lastScreen, null);
    }

    private PeerCraftRegisterScreen(GuiScreen lastScreen, String registeredFriendCode) {
        super(PeerCraftLang.tr("peercraft.gui.register.title"), 300);
        this.lastScreen = lastScreen;
        this.registeredFriendCode = registeredFriendCode;
    }

    @Override
    public void initGui() {
        String previous = this.nicknameBox == null ? null : this.nicknameBox.getText();
        boolean enabled = this.registerButton == null || this.registerButton.enabled;
        String previousPassword = this.passwordBox == null ? "" : this.passwordBox.getPassword();
        super.initGui();
        boolean compact = height < 300;
        int desiredHeight = (compact ? 36 : 46) + 6 + 24 + 3 * (compact ? 22 : 30)
                + 36 + 8 + (compact ? 18 : 24) + 12;
        if (registeredFriendCode != null) desiredHeight = (compact ? 36 : 46) + 6 + 16
                + 2 * (compact ? 22 : 30) + 36 + 8 + (compact ? 18 : 24) + 12;
        dialog = new SteampunkDialog(width, height, desiredHeight, PeerCraftLang.tr("peercraft.gui.register.title"));
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();
        int x = dialog.contentX(), w = dialog.contentWidth(), y = dialog.contentTop() + 12;
        if (this.registeredFriendCode != null) {
            this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.register.copy_code"), this::onCopyCode)
                    .bounds(x, backY() - dialog.buttonPitch(), w, dialog.buttonHeight()).build());
            this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.register.continue"),
                    () -> PeerCraftUi.setScreen(this.mc, new PeerCraftAccountScreen(this.lastScreen)))
                    .primary().bounds(x, backY(), w, dialog.buttonHeight()).build());
            return;
        }
        this.nicknameBox = new SteampunkField(0, this.fontRenderer, x, y, w, dialog.buttonHeight());
        this.nicknameBox.setMaxStringLength(16);
        this.nicknameBox.setText(previous == null ? "" : previous);
        this.nicknameBox.setFocused(true);
        y += dialog.buttonPitch() + 12;
        this.passwordBox = new PasswordField(1, this.fontRenderer, x, y, w, dialog.buttonHeight());
        this.passwordBox.setMaxStringLength(64);
        this.passwordBox.setPassword(previousPassword);
        y += dialog.buttonPitch();
        this.registerButton = this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.register.submit"), this::onRegister)
                .primary().bounds(x, y, w, dialog.buttonHeight()).build());
        this.registerButton.enabled = enabled;
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
        if (keyCode == Keyboard.KEY_TAB && this.registeredFriendCode == null) {
            boolean passwordFocused = this.passwordBox.isFocused();
            this.passwordBox.setFocused(!passwordFocused);
            this.nicknameBox.setFocused(passwordFocused);
            return;
        }
        if ((keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)
                && this.registeredFriendCode == null && this.registerButton.enabled) {
            onRegister();
            return;
        }
        if (this.registeredFriendCode == null
                && (this.nicknameBox.textboxKeyTyped(typedChar, keyCode) || this.passwordBox.textboxKeyTyped(typedChar, keyCode))) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (this.registeredFriendCode == null) {
            this.nicknameBox.mouseClicked(mouseX, mouseY, mouseButton);
            this.passwordBox.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    public void updateScreen() {
        if (this.registeredFriendCode == null) {
            this.nicknameBox.updateCursorCounter();
            this.passwordBox.updateCursorCounter();
        }
    }

    private void onCopyCode() {
        GuiScreen.setClipboardString(this.registeredFriendCode);
        this.statusMessage = PeerCraftLang.tr("peercraft.gui.register.code_copied");
        this.statusColor = PeerCraftUi.TEXT_SUCCESS;
    }

    private void onRegister() {
        String nickname = this.nicknameBox.getText().trim();
        String password = this.passwordBox.getPassword();
        if (nickname.length() < 3 || nickname.length() > 16) {
            this.statusMessage = PeerCraftLang.tr("peercraft.gui.register.nickname_length_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        if (!PeerCraftUi.isValidUsername(nickname)) {
            this.statusMessage = PeerCraftLang.tr("peercraft.gui.register.nickname_charset_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        if (password.isEmpty()) {
            this.statusMessage = PeerCraftLang.tr("peercraft.gui.register.password_required");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }

        this.registerButton.enabled = false;
        this.statusMessage = PeerCraftLang.tr("peercraft.gui.register.registering");
        this.statusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.registerUnlicensed(nickname, password.toCharArray(), new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession session) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    AccountSessionHolder.persist(session);
                    PeerCraftUi.setScreen(mc, new PeerCraftRegisterScreen(lastScreen, session.friendCode()));
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
                    registerButton.enabled = true;
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
        if (this.registeredFriendCode != null) {
            int y = dialog.contentTop();
            label("peercraft.gui.register.success_code_label", y);
            SteampunkDialog.frame(dialog.contentX(), y + 12, dialog.contentWidth(), dialog.buttonHeight(),
                    net.peercraft.client.theme.SteampunkPalette.CONTROL, net.peercraft.client.theme.SteampunkPalette.BORDER);
            this.drawCenteredString(this.fontRenderer, this.registeredFriendCode, width / 2, y + 12 + (dialog.buttonHeight() - 8) / 2, net.peercraft.client.theme.SteampunkPalette.ACCENT);
            dialog.status(this.fontRenderer, PeerCraftLang.tr("peercraft.gui.register.success_hint"), y + 16 + dialog.buttonPitch(),
                    Math.max(0, backY() - dialog.buttonPitch() - y - 24 - dialog.buttonPitch()), net.peercraft.client.theme.SteampunkPalette.MUTED);
            dialog.status(this.fontRenderer, statusMessage, backY() + dialog.buttonHeight() + 2, 10, statusColor);
        } else {
        label("peercraft.gui.register.nickname_field", dialog.contentTop());
        this.nicknameBox.drawTextBox();
        label("peercraft.gui.register.password_field", dialog.contentTop() + dialog.buttonPitch() + 12);
        this.passwordBox.drawTextBox();
        status(this.statusMessage, dialog.contentTop() + 12 + dialog.buttonPitch() * 3 + 12, this.statusColor);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
