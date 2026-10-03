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

/** Incoming friend requests — accept/decline. Scrollable panel with in-place asynchronous updates. */
public class PeerCraftFriendRequestsScreen extends PeerCraftDialogScreen {

    private int scrollOffset;
    private int rowsShown;
    private static final int ROW_HEIGHT = 22;

    private final Screen lastScreen;
    private List<AccountClient.IncomingRequest> requests;
    private boolean loadingStarted;
    private final java.util.Set<java.util.UUID> pending = new java.util.HashSet<>();
    private Component statusMessage = TextComponent.EMPTY;
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftFriendRequestsScreen(Screen lastScreen) {
        this(lastScreen, null);
    }

    private PeerCraftFriendRequestsScreen(Screen lastScreen, List<AccountClient.IncomingRequest> requests) {
        super(new TranslatableComponent("peercraft.gui.friend_requests.title"), 500);
        this.lastScreen = lastScreen;
        this.requests = requests;
    }

    @Override
    protected void init() {
        setFocused(null);
        buttons.clear(); children.clear();
        super.init();
        dialog = new SteampunkDialog(width, height, 500, title, 440);
        rowsShown = Math.max(0, (backTop() - 24 - dialog.contentTop()) / ROW_HEIGHT);
        if (requests != null) scrollOffset = Math.max(0, Math.min(scrollOffset, requests.size() - rowsShown));
        if (this.requests == null) {
            if (!loadingStarted) {
            loadingStarted = true;
            this.statusMessage = new TranslatableComponent("peercraft.gui.friend_requests.loading");
            AccountClient.INSTANCE.listIncomingRequests(new AccountClient.FriendRequestListCallback() {
                @Override
                public void onResult(List<AccountClient.IncomingRequest> result) {
                    runOnClientThread(() -> {
                        if (stillOnThisScreen()) {
                            requests = result;
                            statusMessage = TextComponent.EMPTY;
                            init();
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
            }

            this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                    .bounds(dialog.contentX(), backTop(), dialog.contentWidth(), dialog.buttonHeight()).build());
            return;
        }

        int centerX = this.width / 2;
        int top = dialog.contentTop();
        int shown = Math.min(this.requests.size() - scrollOffset, rowsShown);
        for (int i = 0; i < shown; i++) {
            AccountClient.IncomingRequest request = this.requests.get(i + scrollOffset);
            int rowY = top + i * ROW_HEIGHT;
            this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.friend_requests.accept"), b -> onRespond(request, true))
                    .bounds(dialog.contentX() + dialog.contentWidth() - 2 * actionWidth() - 5, rowY, actionWidth(), 20).primary().build()).active = !pending.contains(request.fromAccountId());
            this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.friend_requests.decline"), b -> onRespond(request, false))
                    .bounds(dialog.contentX() + dialog.contentWidth() - actionWidth(), rowY, actionWidth(), 20).build()).active = !pending.contains(request.fromAccountId());
        }
        if (this.requests.isEmpty()) {
            this.statusMessage = new TranslatableComponent("peercraft.gui.friend_requests.empty");
            this.statusColor = PeerCraftUi.TEXT_MUTED;
        }

        this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                .bounds(dialog.contentX(), backTop(), dialog.contentWidth(), dialog.buttonHeight()).build());
    }

    private int backTop() { return dialog.top + dialog.height - dialog.buttonHeight() - 12; }
    private int actionWidth() { return Math.min(90, Math.max(32, dialog.contentWidth() / 4)); }

    private void onRespond(AccountClient.IncomingRequest request, boolean accept) {
        if (!pending.add(request.fromAccountId())) return;
        init();
        AccountClient.INSTANCE.respondToRequest(request.fromAccountId(), accept, new AccountClient.AckCallback() {
            @Override
            public void onSuccess() {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        pending.remove(request.fromAccountId());
                        requests = new java.util.ArrayList<>(requests);
                        requests.remove(request);
                        statusMessage = TextComponent.EMPTY;
                        init();
                    }
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        pending.remove(request.fromAccountId());
                        init();
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
        renderBackground(poseStack);
        if (requests != null) {
            int shown = Math.min(requests.size() - scrollOffset, rowsShown);
            for (int i = 0; i < shown; i++) {
                AccountClient.IncomingRequest request = requests.get(i + scrollOffset);
                String name = font.plainSubstrByWidth(request.displayName(), Math.max(8, dialog.contentWidth() - 2 * actionWidth() - 30));
                PeerCraftUi.drawNameWithBadge(poseStack, font, name, request.licensed(), dialog.contentX() + 4,
                        dialog.contentTop() + i * ROW_HEIGHT + 6, PeerCraftUi.TEXT_TITLE);
            }
            if (rowsShown > 0 && requests.size() > rowsShown) {
                int trackHeight = rowsShown * ROW_HEIGHT;
                int thumbHeight = Math.max(8, trackHeight * rowsShown / requests.size());
                int y = dialog.contentTop() + (trackHeight - thumbHeight) * scrollOffset / (requests.size() - rowsShown);
                GuiComponent.fill(poseStack, dialog.left + dialog.width - 7, dialog.contentTop(), dialog.left + dialog.width - 5, dialog.contentTop() + trackHeight, 0xFF49331F);
                GuiComponent.fill(poseStack, dialog.left + dialog.width - 7, y, dialog.left + dialog.width - 5, y + thumbHeight, PeerCraftUi.TEXT_ACCENT);
            }
        }
        int statusTop = dialog.top + dialog.height - dialog.buttonHeight() - 35;
        if (requests == null || requests.isEmpty()) statusTop = (dialog.contentTop() + statusTop) / 2 - 9;
        dialog.status(poseStack, font, statusMessage, statusTop, 18, statusColor);
        super.render(poseStack, mouseX, mouseY, partialTick);
        int row = (mouseY - dialog.contentTop()) / ROW_HEIGHT;
        if (requests != null && mouseX >= dialog.contentX() && mouseX < dialog.contentX() + dialog.contentWidth() - 2 * actionWidth() - 8
                && mouseY >= dialog.contentTop() && row < rowsShown && row + scrollOffset < requests.size()) {
            renderTooltip(poseStack, new TextComponent(requests.get(row + scrollOffset).displayName()), mouseX, mouseY);
        }
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (requests != null && mouseX >= dialog.left && mouseX < dialog.left + dialog.width
                && mouseY >= dialog.contentTop() && mouseY < dialog.contentTop() + rowsShown * ROW_HEIGHT) {
            int next = Math.max(0, Math.min(Math.max(0, requests.size() - rowsShown), scrollOffset - (int) Math.signum(delta)));
            if (next != scrollOffset) {
                scrollOffset = next;
                setFocused(null);
                init();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (requests != null && (key == 266 || key == 267)) {
            scrollOffset = Math.max(0, Math.min(Math.max(0, requests.size() - rowsShown),
                    scrollOffset + (key == 266 ? -1 : 1) * Math.max(1, rowsShown)));
            init(); return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void onClose() { PeerCraftUi.setScreen(minecraft, lastScreen); }
}
