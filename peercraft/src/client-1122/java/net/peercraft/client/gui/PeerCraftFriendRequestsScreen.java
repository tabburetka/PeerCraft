package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.peercraft.network.account.AccountClient;

import java.io.IOException;
import java.util.List;

/** Forge 1.12.2 backport of {@code src/main/.../PeerCraftFriendRequestsScreen.java} (cf. 1.16.5 twin). */
public class PeerCraftFriendRequestsScreen extends GuiScreen {

    private static final int MAX_ROWS_SHOWN = 8;
    private static final int ROW_HEIGHT = 22;

    private final GuiScreen lastScreen;
    private final List<AccountClient.IncomingRequest> requests;
    private String statusMessage = "";
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftFriendRequestsScreen(GuiScreen lastScreen) {
        this(lastScreen, null);
    }

    private PeerCraftFriendRequestsScreen(GuiScreen lastScreen, List<AccountClient.IncomingRequest> requests) {
        this.lastScreen = lastScreen;
        this.requests = requests;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();

        if (this.requests == null) {
            this.statusMessage = PeerCraftLang.tr("peercraft.gui.friend_requests.loading");
            AccountClient.INSTANCE.listIncomingRequests(new AccountClient.FriendRequestListCallback() {
                @Override
                public void onResult(List<AccountClient.IncomingRequest> result) {
                    runOnClientThread(() -> {
                        if (stillOnThisScreen()) {
                            PeerCraftUi.setScreen(mc, new PeerCraftFriendRequestsScreen(lastScreen, result));
                        }
                    });
                }

                @Override
                public void onTimeout() {
                    runOnClientThread(() -> {
                        if (stillOnThisScreen()) {
                            statusMessage = PeerCraftLang.tr("peercraft.gui.common.account_server_timeout");
                            statusColor = PeerCraftUi.TEXT_ERROR;
                        }
                    });
                }
            });

            this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.common.back"),
                    () -> PeerCraftUi.setScreen(this.mc, this.lastScreen))
                    .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());
            return;
        }

        int centerX = this.width / 2;
        int top = 40;
        int shown = Math.min(this.requests.size(), MAX_ROWS_SHOWN);
        for (int i = 0; i < shown; i++) {
            AccountClient.IncomingRequest request = this.requests.get(i);
            int rowY = top + i * ROW_HEIGHT;
            this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.friend_requests.accept"), () -> onRespond(request, true))
                    .bounds(centerX + 30, rowY, 90, 20).build());
            this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.friend_requests.decline"), () -> onRespond(request, false))
                    .bounds(centerX + 125, rowY, 90, 20).build());
        }
        if (this.requests.isEmpty()) {
            this.statusMessage = PeerCraftLang.tr("peercraft.gui.friend_requests.empty");
            this.statusColor = PeerCraftUi.TEXT_MUTED;
        } else if (this.requests.size() > MAX_ROWS_SHOWN) {
            this.statusMessage = PeerCraftLang.tr("peercraft.gui.common.shown_first", MAX_ROWS_SHOWN, this.requests.size());
            this.statusColor = PeerCraftUi.TEXT_MUTED;
        }

        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.common.back"),
                () -> PeerCraftUi.setScreen(this.mc, this.lastScreen))
                .bounds(centerX - 100, top + shown * ROW_HEIGHT + 20, 200, 20).build());
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    private void onRespond(AccountClient.IncomingRequest request, boolean accept) {
        AccountClient.INSTANCE.respondToRequest(request.fromAccountId(), accept, new AccountClient.AckCallback() {
            @Override
            public void onSuccess() {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        PeerCraftUi.setScreen(mc, new PeerCraftFriendRequestsScreen(lastScreen));
                    }
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        statusMessage = reason;
                        statusColor = PeerCraftUi.TEXT_ERROR;
                    }
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
        super.drawScreen(mouseX, mouseY, partialTicks);
        int centerX = this.width / 2;
        this.drawCenteredString(this.fontRenderer, PeerCraftLang.tr("peercraft.gui.friend_requests.title"), centerX, 15, PeerCraftUi.TEXT_TITLE);

        if (this.requests != null) {
            int top = 40;
            int shown = Math.min(this.requests.size(), MAX_ROWS_SHOWN);
            for (int i = 0; i < shown; i++) {
                AccountClient.IncomingRequest request = this.requests.get(i);
                PeerCraftUi.drawNameWithBadge(this.fontRenderer, request.displayName(), request.licensed(),
                        centerX - 200, top + i * ROW_HEIGHT + 6, PeerCraftUi.TEXT_TITLE);
            }
        }

        if (!this.statusMessage.isEmpty()) {
            this.drawCenteredString(this.fontRenderer, this.statusMessage, centerX, this.height - 45, this.statusColor);
        }
    }
}
