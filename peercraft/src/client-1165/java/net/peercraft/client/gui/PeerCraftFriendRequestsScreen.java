package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.network.account.AccountClient;

import java.util.List;

/** Incoming friend requests — accept/decline. Same stacked-list-without-scrolling approach as the Friends tab in PeerCraftMultiplayerScreen. */
public class PeerCraftFriendRequestsScreen extends Screen {

    private static final int MAX_ROWS_SHOWN = 8;
    private static final int ROW_HEIGHT = 22;

    private final Screen lastScreen;
    private final List<AccountClient.IncomingRequest> requests;
    private Component statusMessage = TextComponent.EMPTY;
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftFriendRequestsScreen(Screen lastScreen) {
        this(lastScreen, null);
    }

    private PeerCraftFriendRequestsScreen(Screen lastScreen, List<AccountClient.IncomingRequest> requests) {
        super(new TranslatableComponent("peercraft.gui.friend_requests.title"));
        this.lastScreen = lastScreen;
        this.requests = requests;
    }

    @Override
    protected void init() {
        if (this.requests == null) {
            this.statusMessage = new TranslatableComponent("peercraft.gui.friend_requests.loading");
            AccountClient.INSTANCE.listIncomingRequests(new AccountClient.FriendRequestListCallback() {
                @Override
                public void onResult(List<AccountClient.IncomingRequest> result) {
                    runOnClientThread(() -> {
                        if (stillOnThisScreen()) {
                            PeerCraftUi.setScreen(minecraft, new PeerCraftFriendRequestsScreen(lastScreen, result));
                        }
                    });
                }

                @Override
                public void onTimeout() {
                    runOnClientThread(() -> {
                        if (stillOnThisScreen()) {
                            statusMessage = new TranslatableComponent("peercraft.gui.common.account_server_timeout");
                            statusColor = PeerCraftUi.TEXT_ERROR;
                        }
                    });
                }
            });

            this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                    .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());
            return;
        }

        int centerX = this.width / 2;
        int top = 40;
        int shown = Math.min(this.requests.size(), MAX_ROWS_SHOWN);
        for (int i = 0; i < shown; i++) {
            AccountClient.IncomingRequest request = this.requests.get(i);
            int rowY = top + i * ROW_HEIGHT;
            this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.friend_requests.accept"), b -> onRespond(request, true))
                    .bounds(centerX + 30, rowY, 90, 20).build());
            this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.friend_requests.decline"), b -> onRespond(request, false))
                    .bounds(centerX + 125, rowY, 90, 20).build());
        }
        if (this.requests.isEmpty()) {
            this.statusMessage = new TranslatableComponent("peercraft.gui.friend_requests.empty");
            this.statusColor = PeerCraftUi.TEXT_MUTED;
        } else if (this.requests.size() > MAX_ROWS_SHOWN) {
            this.statusMessage = new TranslatableComponent("peercraft.gui.common.shown_first", MAX_ROWS_SHOWN, this.requests.size());
            this.statusColor = PeerCraftUi.TEXT_MUTED;
        }

        this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                .bounds(centerX - 100, top + shown * ROW_HEIGHT + 20, 200, 20).build());
    }

    private void onRespond(AccountClient.IncomingRequest request, boolean accept) {
        AccountClient.INSTANCE.respondToRequest(request.fromAccountId(), accept, new AccountClient.AckCallback() {
            @Override
            public void onSuccess() {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        PeerCraftUi.setScreen(minecraft, new PeerCraftFriendRequestsScreen(lastScreen));
                    }
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        statusMessage = new TextComponent(reason);
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
        return PeerCraftUi.isCurrentScreen(this);
    }

    // 26.1 renamed GuiGraphics -> GuiGraphicsExtractor and replaced Screen#render with
    // #extractRenderState; drawString/drawCenteredString became text/centeredText.
    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        // 1.21.6 made Screen call renderBackground() itself before render() runs — calling it
        // again here double-fires the (now once-per-frame) blur effect and crashes.
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        GuiComponent.drawCenteredString(poseStack, this.font, this.title, centerX, 15, PeerCraftUi.TEXT_TITLE);

        if (this.requests != null) {
            int top = 40;
            int shown = Math.min(this.requests.size(), MAX_ROWS_SHOWN);
            for (int i = 0; i < shown; i++) {
                AccountClient.IncomingRequest request = this.requests.get(i);
                PeerCraftUi.drawNameWithBadge(poseStack, this.font, request.displayName(), request.licensed(), centerX - 200, top + i * ROW_HEIGHT + 6, PeerCraftUi.TEXT_TITLE);
            }
        }

        GuiComponent.drawCenteredString(poseStack, this.font, this.statusMessage, centerX, this.height - 45, this.statusColor);
    }
}
