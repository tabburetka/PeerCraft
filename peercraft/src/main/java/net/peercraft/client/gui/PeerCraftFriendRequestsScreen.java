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

/** Incoming friend requests — accept/decline. Panel-relative rows with bounded scrolling and in-place asynchronous updates. */
public class PeerCraftFriendRequestsScreen extends Screen {

    private static final int ROW_HEIGHT = 22;

    private final Screen lastScreen;
    private List<AccountClient.IncomingRequest> requests;
    private boolean loadingStarted;
    private int scroll;
    private final java.util.Set<java.util.UUID> pending = new java.util.HashSet<>();
    private Component statusMessage = Component.empty();
    private int statusColor = PeerCraftUi.TEXT_MUTED;
    //? if >=1.21.1 {
    private final long animationStart = System.nanoTime();
    private int panelWidth() { return Math.max(1, Math.min(440, this.width - 16)); }
    private int contentLeft() { return (this.width - panelWidth()) / 2 + 12; }
    private int contentWidth() { return Math.max(1, panelWidth() - 24); }
    private int actionWidth() { return Math.min(90, Math.max(32, contentWidth() / 4)); }
    private int panelHeight() { return Math.max(1, Math.min(this.height - 16, 480)); }
    private int panelTop() { return (this.height - panelHeight()) / 2; }
    private int rowsTop() { return panelTop() + 42; }
    private int backTop() { return panelTop() + panelHeight() - 32; }
    private int statusY() { return requests == null || requests.isEmpty() ? (rowsTop() + backTop() - 22) / 2 - 4 : backTop() - 16; }
    private int visibleRows() { return Math.max(0, (backTop() - 22 - rowsTop()) / ROW_HEIGHT); }
    private int maximumScroll() { return Math.max(0, (requests == null ? 0 : requests.size()) - visibleRows()); }
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
        clearWidgets();
        scroll = Math.max(0, Math.min(scroll, maximumScroll()));
        if (this.requests == null) {
            if (!loadingStarted) {
            loadingStarted = true;
            this.statusMessage = Component.translatable("peercraft.gui.friend_requests.loading");
            AccountClient.INSTANCE.listIncomingRequests(new AccountClient.FriendRequestListCallback() {
                @Override
                public void onResult(List<AccountClient.IncomingRequest> result) {
                    runOnClientThread(() -> {
                        if (stillOnThisScreen()) {
                            requests = result;
                            statusMessage = result.isEmpty() ? Component.translatable("peercraft.gui.friend_requests.empty") : Component.empty();
                            init();
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
            }

            //? if >=1.21.1 {
            this.addRenderableWidget(SteampunkSettingsTheme.action(contentLeft(), backTop(), contentWidth(), 20,
                    Component.translatable("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen), false));
            //?} else {
            /*this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                    .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());*/
            //?}
            return;
        }

        int centerX = this.width / 2;
        int top = 40;
        //? if >=1.21.1
        top = rowsTop();
        int shown = Math.min(this.requests.size() - scroll, visibleRows());
        for (int i = 0; i < shown; i++) {
            AccountClient.IncomingRequest request = this.requests.get(scroll + i);
            int rowY = top + i * ROW_HEIGHT;
            //? if >=1.21.1 {
            this.addRenderableWidget(SteampunkSettingsTheme.action(contentLeft() + contentWidth() - 2 * actionWidth() - 5, rowY, actionWidth(), 20,
                    Component.translatable("peercraft.gui.friend_requests.accept"), b -> onRespond(request, true), true)).active = !pending.contains(request.fromAccountId());
            this.addRenderableWidget(SteampunkSettingsTheme.action(contentLeft() + contentWidth() - actionWidth(), rowY, actionWidth(), 20,
                    Component.translatable("peercraft.gui.friend_requests.decline"), b -> onRespond(request, false), false)).active = !pending.contains(request.fromAccountId());
            //?} else {
            /*this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.friend_requests.accept"), b -> onRespond(request, true))
                    .bounds(contentLeft() + contentWidth() - 2 * actionWidth() - 5, rowY, actionWidth(), 20).build());
            this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.friend_requests.decline"), b -> onRespond(request, false))
                    .bounds(contentLeft() + contentWidth() - actionWidth(), rowY, actionWidth(), 20).build());*/
            //?}
        }
        if (this.requests.isEmpty()) {
            this.statusMessage = Component.translatable("peercraft.gui.friend_requests.empty");
            this.statusColor = PeerCraftUi.TEXT_MUTED;
        } else {
            this.statusMessage = Component.empty();
        }

        //? if >=1.21.1 {
        this.addRenderableWidget(SteampunkSettingsTheme.action(contentLeft(), backTop(), contentWidth(), 20,
                Component.translatable("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen), false));
        //?} else {
        /*this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.common.back"), b -> PeerCraftUi.setScreen(this.minecraft, this.lastScreen))
                .bounds(centerX - 100, top + shown * ROW_HEIGHT + 20, 200, 20).build());*/
        //?}
    }

    @Override
    public void onClose() { PeerCraftUi.setScreen(this.minecraft, this.lastScreen); }

    private Component hoveredName(int x, int y) {
        int row = (y - rowsTop()) / ROW_HEIGHT;
        if (requests == null || x < contentLeft() || x >= contentLeft() + contentWidth() - 2 * actionWidth() - 8
                || y < rowsTop() || row >= visibleRows() || row + scroll >= requests.size()) return null;
        return Component.literal(requests.get(row + scroll).displayName());
    }

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
                        statusMessage = Component.literal(reason);
                        statusColor = PeerCraftUi.TEXT_ERROR;
                    }
                });
            }
        });
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0 && x >= contentLeft() && x < contentLeft() + contentWidth()
                && y >= rowsTop() && y < backTop() - 22 && maximumScroll() > 0) {
            scroll = Math.max(0, Math.min(maximumScroll(), scroll - (int) Math.signum(vertical) * 3));
            init();
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    private boolean pageScroll(int key) {
        if (key != 266 && key != 267) return false;
        scroll = Math.max(0, Math.min(maximumScroll(), scroll + (key == 266 ? -1 : 1) * Math.max(1, visibleRows())));
        init();
        return true;
    }
    //? if <1.21.9 {
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        return pageScroll(key) || super.keyPressed(key, scan, modifiers);
    }
    //?} else {
    /*@Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        return pageScroll(event.key()) || super.keyPressed(event);
    }*/
    //?}

    private void runOnClientThread(Runnable action) {
        Minecraft.getInstance().execute(action);
    }

    private boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    // 26.1 renamed GuiGraphics -> GuiGraphicsExtractor and replaced Screen#render with
    // #extractRenderState; drawString/drawCenteredString became text/centeredText.
    //? if <26.1 {
    //? if >=1.21.1 {
    //? if <26.1 {
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        SteampunkSettingsTheme.screenBackground(graphics, this.width, this.height,
                (this.width - panelWidth) / 2, (this.height - panelHeight) / 2, panelWidth, panelHeight,
                (System.nanoTime() - this.animationStart) / 1_000_000L);
    }
    //?} else {
    /* */
    //?}
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
            int shown = Math.min(this.requests.size() - scroll, visibleRows());
            for (int i = 0; i < shown; i++) {
                AccountClient.IncomingRequest request = this.requests.get(scroll + i);
                PeerCraftUi.drawNameWithBadge(graphics, this.font, this.font.plainSubstrByWidth(request.displayName(), Math.max(1, contentWidth() - 2 * actionWidth() - 24)), request.licensed(), contentLeft(), top + i * ROW_HEIGHT + 6, PeerCraftUi.TEXT_TITLE);
            }
        }

        if (requests != null && maximumScroll() > 0 && visibleRows() > 0) {
            int trackHeight = visibleRows() * ROW_HEIGHT;
            int thumb = Math.max(12, trackHeight * visibleRows() / requests.size());
            int thumbY = rowsTop() + (trackHeight - thumb) * scroll / maximumScroll();
            int x = contentLeft() + contentWidth() + 3;
            graphics.fill(x, rowsTop(), x + 3, rowsTop() + trackHeight, 0xFF2C241B);
            graphics.fill(x, thumbY, x + 3, thumbY + thumb, 0xFFBB8B4B);
        }
        graphics.drawCenteredString(this.font, this.statusMessage, centerX, statusY(), this.statusColor);
        Component fullName = hoveredName(mouseX, mouseY);
        if (fullName != null) {
            //? if <1.21.6 {
            graphics.renderTooltip(this.font, fullName, mouseX, mouseY);
            //?} else {
            /*graphics.setTooltipForNextFrame(this.font, fullName, mouseX, mouseY);*/
            //?}
        }
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        graphics.centeredText(this.font, this.title, centerX, panelTop() + 15, SteampunkSettingsTheme.ACCENT);

        if (this.requests != null) {
            int top = rowsTop();
            int shown = Math.min(this.requests.size() - scroll, visibleRows());
            for (int i = 0; i < shown; i++) {
                AccountClient.IncomingRequest request = this.requests.get(scroll + i);
                PeerCraftUi.drawNameWithBadge(graphics, this.font, this.font.plainSubstrByWidth(request.displayName(), Math.max(1, contentWidth() - 2 * actionWidth() - 24)), request.licensed(), contentLeft(), top + i * ROW_HEIGHT + 6, PeerCraftUi.TEXT_TITLE);
            }
        }

        if (requests != null && maximumScroll() > 0 && visibleRows() > 0) {
            int trackHeight = visibleRows() * ROW_HEIGHT;
            int thumb = Math.max(12, trackHeight * visibleRows() / requests.size());
            int thumbY = rowsTop() + (trackHeight - thumb) * scroll / maximumScroll();
            int x = contentLeft() + contentWidth() + 3;
            graphics.fill(x, rowsTop(), x + 3, rowsTop() + trackHeight, 0xFF2C241B);
            graphics.fill(x, thumbY, x + 3, thumbY + thumb, 0xFFBB8B4B);
        }
        graphics.centeredText(this.font, this.statusMessage, centerX, statusY(), this.statusColor);
        Component fullName = hoveredName(mouseX, mouseY);
        if (fullName != null) graphics.setTooltipForNextFrame(this.font, fullName, mouseX, mouseY);
    }*/
    //?}
    //? if >=26.1 {
    /*    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        SteampunkSettingsTheme.screenBackground(graphics, this.width, this.height,
                (this.width - panelWidth) / 2, (this.height - panelHeight) / 2, panelWidth, panelHeight,
                (System.nanoTime() - this.animationStart) / 1_000_000L);
    }*/
    //?}

}
