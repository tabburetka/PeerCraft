package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.network.account.AccountClient;

import java.util.List;

/** Incoming friend requests — accept/decline. Same stacked-list-without-scrolling approach as the Friends tab in PeerCraftMultiplayerScreen. */
public class PeerCraftFriendRequestsScreen extends Screen {

    private static final int MAX_ROWS_SHOWN = 8;
    private static final int ROW_HEIGHT = 22;

    private final Screen lastScreen;
    private final List<AccountClient.IncomingRequest> requests;
    private Component statusMessage = Component.empty();
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftFriendRequestsScreen(Screen lastScreen) {
        this(lastScreen, null);
    }

    private PeerCraftFriendRequestsScreen(Screen lastScreen, List<AccountClient.IncomingRequest> requests) {
        super(Component.translatable("peercraft.gui.friend_requests.title"));
        this.lastScreen = lastScreen;
        this.requests = requests;
    }

    @Override
    protected void init() {
        if (this.requests == null) {
            this.statusMessage = Component.translatable("peercraft.gui.friend_requests.loading");
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
                            statusMessage = Component.translatable("peercraft.gui.common.account_server_timeout");
                            statusColor = PeerCraftUi.TEXT_ERROR;
                        }
                    });
                }
            });

            this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.common.back"), b -> this.minecraft.setScreen(this.lastScreen))
                    .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());
            return;
        }

        int centerX = this.width / 2;
        int top = 40;
        int shown = Math.min(this.requests.size(), MAX_ROWS_SHOWN);
        for (int i = 0; i < shown; i++) {
            AccountClient.IncomingRequest request = this.requests.get(i);
            int rowY = top + i * ROW_HEIGHT;
            this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.friend_requests.accept"), b -> onRespond(request, true))
                    .bounds(centerX + 30, rowY, 90, 20).build());
            this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.friend_requests.decline"), b -> onRespond(request, false))
                    .bounds(centerX + 125, rowY, 90, 20).build());
        }
        if (this.requests.isEmpty()) {
            this.statusMessage = Component.translatable("peercraft.gui.friend_requests.empty");
            this.statusColor = PeerCraftUi.TEXT_MUTED;
        } else if (this.requests.size() > MAX_ROWS_SHOWN) {
            this.statusMessage = Component.translatable("peercraft.gui.common.shown_first", MAX_ROWS_SHOWN, this.requests.size());
            this.statusColor = PeerCraftUi.TEXT_MUTED;
        }

        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.common.back"), b -> this.minecraft.setScreen(this.lastScreen))
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
                        statusColor = PeerCraftUi.TEXT_ERROR;
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
        graphics.drawCenteredString(this.font, this.title, centerX, 15, PeerCraftUi.TEXT_TITLE);

        if (this.requests != null) {
            int top = 40;
            int shown = Math.min(this.requests.size(), MAX_ROWS_SHOWN);
            for (int i = 0; i < shown; i++) {
                AccountClient.IncomingRequest request = this.requests.get(i);
                PeerCraftUi.drawNameWithBadge(graphics, this.font, request.displayName(), request.licensed(), centerX - 200, top + i * ROW_HEIGHT + 6, PeerCraftUi.TEXT_TITLE);
            }
        }

        graphics.drawCenteredString(this.font, this.statusMessage, centerX, this.height - 45, this.statusColor);
    }
}
