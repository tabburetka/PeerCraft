package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.network.account.AccountClient;
import org.lwjgl.glfw.GLFW;

/**
 * Registration for unlicensed ("pirate") accounts — nickname + password. On success, shows
 * the assigned friend code prominently (it's the only recovery path if this device's saved
 * session is ever lost — see the accounts plan's accepted risk on this) instead of silently
 * returning to the previous screen. The code also gets a highlighted box and a one-click
 * copy button, since asking a player to retype six characters correctly by hand is where a
 * lost-code support request starts.
 */
public class PeerCraftRegisterScreen extends Screen {

    private final Screen lastScreen;
    /** null while the form is showing; set to the assigned code once registration succeeds. */
    private final String registeredFriendCode;

    private EditBox nicknameBox;
    private EditBox passwordBox;
    private Button registerButton;
    private Component statusMessage = Component.empty();
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftRegisterScreen(Screen lastScreen) {
        this(lastScreen, null);
    }

    private PeerCraftRegisterScreen(Screen lastScreen, String registeredFriendCode) {
        super(Component.translatable("peercraft.gui.register.title"));
        this.lastScreen = lastScreen;
        this.registeredFriendCode = registeredFriendCode;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 50;

        if (this.registeredFriendCode != null) {
            this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.register.copy_code"), b -> onCopyCode())
                    .bounds(centerX - 100, this.height / 2 + 8, 200, 20).build());
            this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.register.continue"), b -> this.minecraft.setScreen(new PeerCraftAccountScreen(this.lastScreen)))
                    .bounds(centerX - 100, this.height / 2 + 34, 200, 20).build());
            return;
        }

        this.nicknameBox = new EditBox(this.font, centerX - 100, y, 200, 20, Component.translatable("peercraft.gui.register.nickname_field"));
        this.nicknameBox.setMaxLength(16);
        this.nicknameBox.setHint(Component.translatable("peercraft.gui.register.nickname_hint"));
        this.addRenderableWidget(this.nicknameBox);
        this.setInitialFocus(this.nicknameBox);

        y += 26;
        this.passwordBox = new EditBox(this.font, centerX - 100, y, 200, 20, Component.translatable("peercraft.gui.register.password_field"));
        this.passwordBox.setMaxLength(64);
        this.passwordBox.setHint(Component.translatable("peercraft.gui.register.password_hint"));
        PeerCraftUi.maskAsPassword(this.passwordBox);
        this.addRenderableWidget(this.passwordBox);

        y += 26;
        this.registerButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.register.submit"), b -> onRegister())
                .bounds(centerX - 100, y, 200, 20).build());

        y += 26;
        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.common.back"), b -> this.minecraft.setScreen(this.lastScreen))
                .bounds(centerX - 100, y, 200, 20).build());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && this.registeredFriendCode == null && this.registerButton.active) {
            onRegister();
            return true;
        }
        return false;
    }

    private void onCopyCode() {
        Minecraft.getInstance().keyboardHandler.setClipboard(this.registeredFriendCode);
        this.statusMessage = Component.translatable("peercraft.gui.register.code_copied");
        this.statusColor = PeerCraftUi.TEXT_SUCCESS;
    }

    private void onRegister() {
        String nickname = this.nicknameBox.getValue().trim();
        String password = this.passwordBox.getValue();
        if (nickname.length() < 3 || nickname.length() > 16) {
            this.statusMessage = Component.translatable("peercraft.gui.register.nickname_length_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        if (password.isEmpty()) {
            this.statusMessage = Component.translatable("peercraft.gui.register.password_required");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }

        this.registerButton.active = false;
        this.statusMessage = Component.translatable("peercraft.gui.register.registering");
        this.statusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.registerUnlicensed(nickname, password.toCharArray(), new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession session) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    AccountSessionHolder.persist(session);
                    minecraft.setScreen(new PeerCraftRegisterScreen(lastScreen, session.friendCode()));
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    // `reason` comes from the account server, not authored here — can't localize it.
                    statusMessage = Component.literal(reason);
                    statusColor = PeerCraftUi.TEXT_ERROR;
                    registerButton.active = true;
                });
            }
        });
    }

    private void runOnClientThread(Runnable action) {
        Minecraft.getInstance().execute(action);
    }

    private boolean stillOnThisScreen() {
        return Minecraft.getInstance().screen == this;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        if (this.registeredFriendCode != null) {
            graphics.drawCenteredString(this.font, Component.translatable("peercraft.gui.register.success_title"), centerX, this.height / 2 - 90, PeerCraftUi.TEXT_TITLE);
            graphics.drawCenteredString(this.font, Component.translatable("peercraft.gui.register.success_code_label"), centerX, this.height / 2 - 55, PeerCraftUi.TEXT_TITLE);

            graphics.fill(centerX - 60, this.height / 2 - 42, centerX + 60, this.height / 2 - 20, 0x80000000);
            graphics.drawCenteredString(this.font, Component.literal(this.registeredFriendCode), centerX, this.height / 2 - 36, PeerCraftUi.TEXT_ACCENT);

            graphics.drawCenteredString(this.font, Component.translatable("peercraft.gui.register.success_hint"), centerX, this.height / 2 - 8, PeerCraftUi.TEXT_MUTED);
            graphics.drawCenteredString(this.font, this.statusMessage, centerX, this.height / 2 + 60, this.statusColor);
        } else {
            graphics.drawCenteredString(this.font, this.title, centerX, this.height / 2 - 80, PeerCraftUi.TEXT_TITLE);
            graphics.drawCenteredString(this.font, this.statusMessage, centerX, this.height / 2 + 60, this.statusColor);
        }
    }
}
