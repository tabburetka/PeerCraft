package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.network.account.AccountClient;

import java.util.List;

/** Incoming friend requests — accept/decline. Same stacked-list-without-scrolling approach as PeerCraftFriendsScreen. */
public class PeerCraftFriendRequestsScreen extends Screen {

    private static final int MAX_ROWS_SHOWN = 8;
    private static final int ROW_HEIGHT = 22;

    private final Screen lastScreen;
    private final List<AccountClient.IncomingRequest> requests;
    private Component statusMessage = Component.empty();

    public PeerCraftFriendRequestsScreen(Screen lastScreen) {
        this(lastScreen, null);
    }

    private PeerCraftFriendRequestsScreen(Screen lastScreen, List<AccountClient.IncomingRequest> requests) {
        super(Component.literal("PeerCraft — заявки в друзья"));
        this.lastScreen = lastScreen;
        this.requests = requests;
    }

    @Override
    protected void init() {
        if (this.requests == null) {
            this.statusMessage = Component.literal("Загружаем заявки...");
            AccountClient.INSTANCE.listIncomingRequests(new AccountClient.FriendRequestListCallback() {
                @Override
                public void onResult(List<AccountClient.IncomingRequest> result) {
                    runOnClientThread(() -> {
                        if (stillOnThisScreen()) {
                            minecraft.setScreen(new PeerCraftFriendRequestsScreen(lastScreen, result));
                        }
                    });
                }

                @Override
                public void onTimeout() {
                    runOnClientThread(() -> {
                        if (stillOnThisScreen()) {
                            statusMessage = Component.literal("Сервер аккаунтов не ответил");
                        }
                    });
                }
            });

            this.addRenderableWidget(Button.builder(Component.literal("Назад"), b -> this.minecraft.setScreen(this.lastScreen))
                    .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());
            return;
        }

        int centerX = this.width / 2;
        int top = 40;
        int shown = Math.min(this.requests.size(), MAX_ROWS_SHOWN);
        for (int i = 0; i < shown; i++) {
            AccountClient.IncomingRequest request = this.requests.get(i);
            int rowY = top + i * ROW_HEIGHT;
            this.addRenderableWidget(Button.builder(Component.literal("Принять"), b -> onRespond(request, true))
                    .bounds(centerX + 30, rowY, 90, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Отклонить"), b -> onRespond(request, false))
                    .bounds(centerX + 125, rowY, 90, 20).build());
        }
        if (this.requests.isEmpty()) {
            this.statusMessage = Component.literal("Нет входящих заявок");
        } else if (this.requests.size() > MAX_ROWS_SHOWN) {
            this.statusMessage = Component.literal("Показаны первые " + MAX_ROWS_SHOWN + " из " + this.requests.size());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Назад"), b -> this.minecraft.setScreen(this.lastScreen))
                .bounds(centerX - 100, top + shown * ROW_HEIGHT + 20, 200, 20).build());
    }

    private void onRespond(AccountClient.IncomingRequest request, boolean accept) {
        AccountClient.INSTANCE.respondToRequest(request.fromAccountId(), accept, new AccountClient.AckCallback() {
            @Override
            public void onSuccess() {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        minecraft.setScreen(new PeerCraftFriendRequestsScreen(lastScreen));
                    }
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        statusMessage = Component.literal(reason);
                    }
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
        graphics.drawCenteredString(this.font, this.title, centerX, 15, 0xFFFFFF);

        if (this.requests != null) {
            int top = 40;
            int shown = Math.min(this.requests.size(), MAX_ROWS_SHOWN);
            for (int i = 0; i < shown; i++) {
                AccountClient.IncomingRequest request = this.requests.get(i);
                String badge = request.licensed() ? " ✓" : " ☠";
                graphics.drawString(this.font, request.displayName() + badge, centerX - 200, top + i * ROW_HEIGHT + 6, 0xFFFFFF, false);
            }
        }

        graphics.drawCenteredString(this.font, this.statusMessage, centerX, this.height - 45, 0xFFFF55);
    }
}
