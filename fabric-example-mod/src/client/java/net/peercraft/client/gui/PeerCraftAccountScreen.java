package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.account.AccountSessionHolder;
import net.peercraft.network.account.AccountClient;

/**
 * Hub screen for the account system — opened from the title screen. Not logged in: three
 * ways to log in. Logged in: current identity, rename (unlicensed only), logout. Friends/
 * search/requests navigation is added here in a later phase (Фаза 3) — this screen is
 * deliberately just auth for now.
 */
public class PeerCraftAccountScreen extends Screen {

    private final Screen lastScreen;
    private Component statusMessage;
    private Button loginLicensedButton;

    public PeerCraftAccountScreen(Screen lastScreen) {
        super(Component.literal("PeerCraft — аккаунт"));
        this.lastScreen = lastScreen;
        this.statusMessage = Component.empty();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 70;

        AccountClient.AccountSession session = AccountSessionHolder.current();
        if (session == null) {
            this.loginLicensedButton = this.addRenderableWidget(Button.builder(Component.literal("Войти через Mojang (лицензия)"), b -> onLoginLicensed())
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
            this.addRenderableWidget(Button.builder(Component.literal("Зарегистрироваться"), b -> this.minecraft.setScreen(new PeerCraftRegisterScreen(this)))
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
            this.addRenderableWidget(Button.builder(Component.literal("Войти по коду дружбы"), b -> this.minecraft.setScreen(new PeerCraftLoginByCodeScreen(this)))
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;
        } else {
            String badge = session.licensed() ? " ✓" : " ☠";
            this.statusMessage = Component.literal("Вы вошли как " + session.displayName() + badge + " | код дружбы: " + session.friendCode());

            this.addRenderableWidget(Button.builder(Component.literal("Друзья"), b -> this.minecraft.setScreen(new PeerCraftFriendsScreen(this.lastScreen)))
                    .bounds(centerX - 100, y, 200, 20).build());
            y += 26;

            if (!session.licensed()) {
                this.addRenderableWidget(Button.builder(Component.literal("Изменить ник"), b -> this.minecraft.setScreen(new PeerCraftRenameScreen(this)))
                        .bounds(centerX - 100, y, 200, 20).build());
                y += 26;
            }
            this.addRenderableWidget(Button.builder(Component.literal("Выйти"), b -> {
                AccountSessionHolder.logout();
                this.minecraft.setScreen(new PeerCraftAccountScreen(this.lastScreen));
            }).bounds(centerX - 100, y, 200, 20).build());
            y += 26;
        }

        y += 4;
        this.addRenderableWidget(Button.builder(Component.literal("Назад"), b -> this.minecraft.setScreen(this.lastScreen))
                .bounds(centerX - 100, y, 200, 20).build());
    }

    private void onLoginLicensed() {
        Minecraft mc = Minecraft.getInstance();
        User user = mc.getUser();
        if (user.getType() == User.Type.LEGACY) {
            this.statusMessage = Component.literal("Игра запущена в offline-режиме — лицензионный вход недоступен. Зарегистрируйтесь без лицензии.");
            return;
        }

        this.loginLicensedButton.active = false;
        this.statusMessage = Component.literal("Подтверждаем лицензию через Mojang...");
        AccountClient.INSTANCE.loginLicensed(mc.getGameProfile(), user.getAccessToken(), mc.getMinecraftSessionService(), new AccountClient.AuthCallback() {
            @Override
            public void onSuccess(AccountClient.AccountSession session) {
                runOnClientThread(() -> {
                    AccountSessionHolder.persist(session);
                    if (stillOnThisScreen()) {
                        minecraft.setScreen(new PeerCraftAccountScreen(lastScreen));
                    }
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    statusMessage = Component.literal(reason);
                    loginLicensedButton.active = true;
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
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 90, 0xFFFFFF);
        graphics.drawCenteredString(this.font, this.statusMessage, this.width / 2, this.height / 2 + 70, 0xFFFF55);
    }
}
