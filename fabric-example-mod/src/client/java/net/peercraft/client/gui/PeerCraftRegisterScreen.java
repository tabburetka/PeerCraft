package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.network.account.AccountClient;

/**
 * Registration for unlicensed ("pirate") accounts — nickname + password. On success, shows
 * the assigned friend code prominently (it's the only recovery path if this device's saved
 * session is ever lost — see the accounts plan's accepted risk on this) instead of silently
 * returning to the previous screen.
 */
public class PeerCraftRegisterScreen extends Screen {

    private final Screen lastScreen;
    /** null while the form is showing; set to the assigned code once registration succeeds. */
    private final String registeredFriendCode;

    private EditBox nicknameBox;
    private EditBox passwordBox;
    private Button registerButton;
    private Component statusMessage = Component.empty();

    public PeerCraftRegisterScreen(Screen lastScreen) {
        this(lastScreen, null);
    }

    private PeerCraftRegisterScreen(Screen lastScreen, String registeredFriendCode) {
        super(Component.literal("PeerCraft — регистрация"));
        this.lastScreen = lastScreen;
        this.registeredFriendCode = registeredFriendCode;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 50;

        if (this.registeredFriendCode != null) {
            this.addRenderableWidget(Button.builder(Component.literal("Продолжить"), b -> this.minecraft.setScreen(new PeerCraftAccountScreen(this.lastScreen)))
                    .bounds(centerX - 100, y + 40, 200, 20).build());
            return;
        }

        this.nicknameBox = new EditBox(this.font, centerX - 100, y, 200, 20, Component.literal("Nickname"));
        this.nicknameBox.setMaxLength(16);
        this.nicknameBox.setHint(Component.literal("Ник (3-16 символов)"));
        this.addRenderableWidget(this.nicknameBox);
        this.setInitialFocus(this.nicknameBox);

        y += 26;
        this.passwordBox = new EditBox(this.font, centerX - 100, y, 200, 20, Component.literal("Password"));
        this.passwordBox.setMaxLength(64);
        this.passwordBox.setHint(Component.literal("Пароль"));
        this.addRenderableWidget(this.passwordBox);

        y += 26;
        this.registerButton = this.addRenderableWidget(Button.builder(Component.literal("Зарегистрироваться"), b -> onRegister())
                .bounds(centerX - 100, y, 200, 20).build());

        y += 26;
        this.addRenderableWidget(Button.builder(Component.literal("Назад"), b -> this.minecraft.setScreen(this.lastScreen))
                .bounds(centerX - 100, y, 200, 20).build());
    }

    private void onRegister() {
        String nickname = this.nicknameBox.getValue().trim();
        String password = this.passwordBox.getValue();
        if (nickname.length() < 3 || nickname.length() > 16) {
            this.statusMessage = Component.literal("Ник должен быть от 3 до 16 символов, без пробелов");
            return;
        }
        if (password.isEmpty()) {
            this.statusMessage = Component.literal("Введите пароль");
            return;
        }

        this.registerButton.active = false;
        this.statusMessage = Component.literal("Регистрируем...");
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
                    statusMessage = Component.literal(reason);
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
            graphics.drawCenteredString(this.font, Component.literal("Аккаунт создан!"), centerX, this.height / 2 - 90, 0xFFFFFF);
            graphics.drawCenteredString(this.font, Component.literal("Ваш код дружбы:"), centerX, this.height / 2 - 20, 0xFFFFFF);
            graphics.drawCenteredString(this.font, Component.literal(this.registeredFriendCode), centerX, this.height / 2 - 5, 0xFFFF55);
            graphics.drawCenteredString(this.font, Component.literal("Сохраните его — он нужен для входа с другого устройства"), centerX, this.height / 2 + 12, 0xAAAAAA);
        } else {
            graphics.drawCenteredString(this.font, this.title, centerX, this.height / 2 - 80, 0xFFFFFF);
            graphics.drawCenteredString(this.font, this.statusMessage, centerX, this.height / 2 + 60, 0xFFFF55);
        }
    }
}
