package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
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
    //? if =1.21.1 {
    private final long animationStart = System.nanoTime();
    private int panelHeight() {
        return Math.min(this.height - 16, Math.max(150,
                100 + Math.min(MAX_ROWS_SHOWN, this.requests == null ? 0 : this.requests.size()) * ROW_HEIGHT));
    }
    private int panelTop() { return (this.height - panelHeight()) / 2; }
    private int rowsTop() { return panelTop() + 42; }
    private int backTop() { return panelTop() + panelHeight() - 32; }
    //?}

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
                            PeerCraftUi.setScreen(minecraft, new PeerCraftFriendRequestsScreen(lastScreen, result));
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

            //? if =1.21.1 {
            this.addRenderableWidget(SteampunkSettingsTheme.action(this.width / 2 - 100, backTop(), 200, 20,
                    Component.translatable("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen), false));
            //?} else {
            /*this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                    .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());*/
            //?}
            return;
        }

        int centerX = this.width / 2;
        int top = 40;
        //? if =1.21.1
        top = rowsTop();
        int shown = Math.min(this.requests.size(), MAX_ROWS_SHOWN);
        for (int i = 0; i < shown; i++) {
            AccountClient.IncomingRequest request = this.requests.get(i);
            int rowY = top + i * ROW_HEIGHT;
            //? if =1.21.1 {
            this.addRenderableWidget(SteampunkSettingsTheme.action(centerX + 30, rowY, 90, 20,
                    Component.translatable("peercraft.gui.friend_requests.accept"), b -> onRespond(request, true), true));
            this.addRenderableWidget(SteampunkSettingsTheme.action(centerX + 125, rowY, 90, 20,
                    Component.translatable("peercraft.gui.friend_requests.decline"), b -> onRespond(request, false), false));
            //?} else {
            /*this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.friend_requests.accept"), b -> onRespond(request, true))
                    .bounds(centerX + 30, rowY, 90, 20).build());
            this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.friend_requests.decline"), b -> onRespond(request, false))
                    .bounds(centerX + 125, rowY, 90, 20).build());*/
            //?}
        }
        if (this.requests.isEmpty()) {
            this.statusMessage = Component.translatable("peercraft.gui.friend_requests.empty");
            this.statusColor = PeerCraftUi.TEXT_MUTED;
        } else if (this.requests.size() > MAX_ROWS_SHOWN) {
            this.statusMessage = Component.translatable("peercraft.gui.common.shown_first", MAX_ROWS_SHOWN, this.requests.size());
            this.statusColor = PeerCraftUi.TEXT_MUTED;
        }

        //? if =1.21.1 {
        this.addRenderableWidget(SteampunkSettingsTheme.action(centerX - 100, backTop(), 200, 20,
                Component.translatable("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen), false));
        //?} else {
        /*this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                .bounds(centerX - 100, top + shown * ROW_HEIGHT + 20, 200, 20).build());*/
        //?}
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
        return PeerCraftUi.isCurrentScreen(this);
    }

    // 26.1 renamed GuiGraphics -> GuiGraphicsExtractor and replaced Screen#render with
    // #extractRenderState; drawString/drawCenteredString became text/centeredText.
    //? if <26.1 {
    //? if =1.21.1 {
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int panelWidth = Math.min(440, this.width - 16);
        int panelHeight = panelHeight();
        SteampunkSettingsTheme.screenBackground(graphics, this.width, this.height,
                (this.width - panelWidth) / 2, (this.height - panelHeight) / 2, panelWidth, panelHeight,
                (System.nanoTime() - this.animationStart) / 1_000_000L);
    }
    //?}
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 1.21.6 made Screen call renderBackground() itself before render() runs — calling it
        // again here double-fires the (now once-per-frame) blur effect and crashes.
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, centerX, panelTop() + 15, SteampunkSettingsTheme.ACCENT);

        if (this.requests != null) {
            int top = rowsTop();
            int shown = Math.min(this.requests.size(), MAX_ROWS_SHOWN);
            for (int i = 0; i < shown; i++) {
                AccountClient.IncomingRequest request = this.requests.get(i);
                PeerCraftUi.drawNameWithBadge(graphics, this.font, request.displayName(), request.licensed(), centerX - 200, top + i * ROW_HEIGHT + 6, PeerCraftUi.TEXT_TITLE);
            }
        }

        graphics.drawCenteredString(this.font, this.statusMessage, centerX, backTop() - 16, this.statusColor);
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        graphics.centeredText(this.font, this.title, centerX, 15, PeerCraftUi.TEXT_TITLE);

        if (this.requests != null) {
            int top = 40;
            int shown = Math.min(this.requests.size(), MAX_ROWS_SHOWN);
            for (int i = 0; i < shown; i++) {
                AccountClient.IncomingRequest request = this.requests.get(i);
                PeerCraftUi.drawNameWithBadge(graphics, this.font, request.displayName(), request.licensed(), centerX - 200, top + i * ROW_HEIGHT + 6, PeerCraftUi.TEXT_TITLE);
            }
        }

        graphics.centeredText(this.font, this.statusMessage, centerX, this.height - 45, this.statusColor);
    }*/
    //?}
}
