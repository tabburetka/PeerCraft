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

import java.util.Locale;

/** Login for an unlicensed account on a new device — friend code + password, since nicknames aren't unique. */
public class PeerCraftLoginByCodeScreen extends Screen {

    private final Screen lastScreen;

    private EditBox friendCodeBox;
    private EditBox passwordBox;
    private Button loginButton;
    private Component statusMessage = Component.empty();
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftLoginByCodeScreen(Screen lastScreen) {
        super(Component.translatable("peercraft.gui.login_code.title"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 50;

        this.friendCodeBox = new EditBox(this.font, centerX - 100, y, 200, 20, Component.translatable("peercraft.gui.login_code.friend_code_field"));
        this.friendCodeBox.setMaxLength(6);
        this.friendCodeBox.setHint(Component.translatable("peercraft.gui.login_code.friend_code_hint"));
        this.addRenderableWidget(this.friendCodeBox);
        this.setInitialFocus(this.friendCodeBox);

        y += 26;
        this.passwordBox = new EditBox(this.font, centerX - 100, y, 200, 20, Component.translatable("peercraft.gui.register.password_field"));
        this.passwordBox.setMaxLength(64);
        this.passwordBox.setHint(Component.translatable("peercraft.gui.register.password_hint"));
        PeerCraftUi.maskAsPassword(this.passwordBox);
        this.addRenderableWidget(this.passwordBox);

        y += 26;
        this.loginButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.login_code.submit"), b -> onLogin())
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
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && this.loginButton.active) {
            onLogin();
            return true;
        }
        return false;
    }

    private void onLogin() {
        String friendCode = this.friendCodeBox.getValue().trim().toUpperCase(Locale.ROOT);
        String password = this.passwordBox.getValue();
        if (friendCode.length() != 6) {
            this.statusMessage = Component.translatable("peercraft.gui.login_code.code_length_error");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        if (password.isEmpty()) {
            this.statusMessage = Component.translatable("peercraft.gui.register.password_required");
            this.statusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }

        this.loginButton.active = false;
        this.statusMessage = Component.translatable("peercraft.gui.login_code.logging_in");
        this.statusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.loginByFriendCode(friendCode, password.toCharArray(), new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession session) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    AccountSessionHolder.persist(session);
                    minecraft.setScreen(new PeerCraftAccountScreen(lastScreen));
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    statusMessage = Component.literal(reason);
                    statusColor = PeerCraftUi.TEXT_ERROR;
                    loginButton.active = true;
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
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 80, PeerCraftUi.TEXT_TITLE);
        graphics.drawCenteredString(this.font, this.statusMessage, this.width / 2, this.height / 2 + 60, this.statusColor);
    }
}
