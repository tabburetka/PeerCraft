package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.network.account.AccountClient;
import org.lwjgl.input.Keyboard;


/** Forge 1.7.10 backport of {@code src/main/.../PeerCraftRegisterScreen.java} (twin of the src/client-1122 backport). */
public class PeerCraftRegisterScreen extends GuiScreen {

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
        this.lastScreen = lastScreen;
        this.registeredFriendCode = registeredFriendCode;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();
        int centerX = this.width / 2;
        int y = this.height / 2 - 50;

        if (this.registeredFriendCode != null) {
            this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.register.copy_code"), this::onCopyCode)
                    .bounds(centerX - 100, this.height / 2 + 8, 200, 20).build());
            this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.register.continue"),
                    () -> PeerCraftUi.setScreen(this.mc, new PeerCraftAccountScreen(this.lastScreen)))
                    .bounds(centerX - 100, this.height / 2 + 34, 200, 20).build());
            return;
        }

        this.nicknameBox = new GuiTextField(this.fontRendererObj, centerX - 100, y, 200, 20);
        this.nicknameBox.setMaxStringLength(16);
        this.nicknameBox.setFocused(true);

        y += 26;
        this.passwordBox = new PasswordField(this.fontRendererObj, centerX - 100, y, 200, 20);
        this.passwordBox.setMaxStringLength(64);

        y += 26;
        this.registerButton = this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.register.submit"), this::onRegister)
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
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
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
        Minecraft.getMinecraft().func_152344_a(action);
    }

    private boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        if (this.registeredFriendCode == null) {
            this.nicknameBox.drawTextBox();
            this.passwordBox.drawTextBox();
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        int centerX = this.width / 2;
        if (this.registeredFriendCode != null) {
            this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.gui.register.success_title"), centerX, this.height / 2 - 90, PeerCraftUi.TEXT_TITLE);
            this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.gui.register.success_code_label"), centerX, this.height / 2 - 55, PeerCraftUi.TEXT_TITLE);
            drawRect(centerX - 60, this.height / 2 - 42, centerX + 60, this.height / 2 - 20, 0x80000000);
            this.drawCenteredString(this.fontRendererObj, this.registeredFriendCode, centerX, this.height / 2 - 36, PeerCraftUi.TEXT_ACCENT);
            this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.gui.register.success_hint"), centerX, this.height / 2 - 8, PeerCraftUi.TEXT_MUTED);
        } else {
            this.drawCenteredString(this.fontRendererObj, PeerCraftLang.tr("peercraft.gui.register.title"), centerX, this.height / 2 - 80, PeerCraftUi.TEXT_TITLE);
        }
        if (!this.statusMessage.isEmpty()) {
            this.drawCenteredString(this.fontRendererObj, this.statusMessage, centerX, this.height / 2 + 60, this.statusColor);
        }
    }
}
