package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.peercraft.network.account.AccountClient;

import java.io.IOException;
import java.util.List;

/** Forge 1.12.2 backport of {@code src/main/.../PeerCraftFriendRequestsScreen.java} (cf. 1.16.5 twin). */
public class PeerCraftFriendRequestsScreen extends PeerCraftDialogScreen {

    private int scrollOffset;
    private int visibleRows;
    private int scrollbarGrab = -1;
    private boolean loadingStarted;
    private final java.util.Set<java.util.UUID> pending = new java.util.HashSet<>();

    private final GuiScreen lastScreen;
    private List<AccountClient.IncomingRequest> requests;
    private String statusMessage = "";
    private int statusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftFriendRequestsScreen(GuiScreen lastScreen) {
        this(lastScreen, null);
    }

    private PeerCraftFriendRequestsScreen(GuiScreen lastScreen, List<AccountClient.IncomingRequest> requests) {
        super(PeerCraftLang.tr("peercraft.gui.friend_requests.title"), 480, 440);
        this.lastScreen = lastScreen;
        this.requests = requests;
    }

    @Override
    public void initGui() {
        super.initGui();
        rebuildRows();
        if (this.requests == null && !loadingStarted) {
            loadingStarted = true;
            this.statusMessage = PeerCraftLang.tr("peercraft.gui.friend_requests.loading");
            AccountClient.INSTANCE.listIncomingRequests(new AccountClient.FriendRequestListCallback() {
                @Override public void onResult(List<AccountClient.IncomingRequest> result) {
                    runOnClientThread(() -> {
                        if (stillOnThisScreen()) {
                            requests = result;
                            statusMessage = result.isEmpty() ? PeerCraftLang.tr("peercraft.gui.friend_requests.empty") : "";
                            statusColor = PeerCraftUi.TEXT_MUTED;
                            rebuildRows();
                        }
                    });
                }
                @Override public void onTimeout() {
                    runOnClientThread(() -> {
                        if (stillOnThisScreen()) {
                            statusMessage = PeerCraftLang.tr("peercraft.gui.common.account_server_timeout");
                            statusColor = PeerCraftUi.TEXT_ERROR;
                        }
                    });
                }
            });
        }
    }

    private int rowPitch() { return dialog.buttonPitch(); }
    private int listBottom() { return backY() - 36; }
    private int actionWidth() { return Math.max(1, Math.min(72, (dialog.contentWidth() - 24) / 3)); }
    private int actionsX() { return dialog.contentX() + dialog.contentWidth() - actionWidth() * 2 - 10; }
    private int maxScroll() { return requests == null ? 0 : Math.max(0, requests.size() - visibleRows); }
    private void rebuildRows() {
        this.buttonList.clear();
        visibleRows = Math.max(0, (listBottom() - dialog.contentTop()) / rowPitch());
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll()));
        if (requests != null) {
            int shown = Math.min(visibleRows, requests.size() - scrollOffset);
            for (int i = 0; i < shown; i++) {
                AccountClient.IncomingRequest request = requests.get(i + scrollOffset);
                int y = dialog.contentTop() + i * rowPitch();
                IdButton accept = IdButton.builder(PeerCraftLang.tr("peercraft.gui.friend_requests.accept"), () -> onRespond(request, true))
                        .primary().bounds(actionsX(), y, actionWidth(), dialog.buttonHeight()).build();
                IdButton decline = IdButton.builder(PeerCraftLang.tr("peercraft.gui.friend_requests.decline"), () -> onRespond(request, false))
                        .bounds(actionsX() + actionWidth() + 4, y, actionWidth(), dialog.buttonHeight()).build();
                accept.enabled = decline.enabled = !pending.contains(request.fromAccountId());
                this.buttonList.add(accept);
                this.buttonList.add(decline);
            }
        }
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.common.back"),
                () -> PeerCraftUi.setScreen(this.mc, this.lastScreen))
                .bounds(dialog.contentX(), backY(), dialog.contentWidth(), dialog.buttonHeight()).build());
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    private void onRespond(AccountClient.IncomingRequest request, boolean accept) {
        if (!pending.add(request.fromAccountId())) return;
        rebuildRows();
        AccountClient.INSTANCE.respondToRequest(request.fromAccountId(), accept, new AccountClient.AckCallback() {
            @Override
            public void onSuccess() {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        pending.remove(request.fromAccountId());
                        requests = null;
                        loadingStarted = false;
                        initGui();
                    }
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        pending.remove(request.fromAccountId());
                        rebuildRows();
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
        String hoveredName = null;
        if (requests != null) {
            int shown = Math.min(visibleRows, requests.size() - scrollOffset);
            for (int i = 0; i < shown; i++) {
                AccountClient.IncomingRequest request = requests.get(i + scrollOffset);
                int y = dialog.contentTop() + i * rowPitch();
                String badge = PeerCraftUi.badgeText(request.licensed());
                int nameWidth = Math.max(1, actionsX() - dialog.contentX() - 8 - this.fontRenderer.getStringWidth(badge));
                String name = this.fontRenderer.trimStringToWidth(request.displayName(), nameWidth);
                PeerCraftUi.drawNameWithBadge(this.fontRenderer, name, request.licensed(), dialog.contentX(),
                        y + (dialog.buttonHeight() - 8) / 2, PeerCraftUi.TEXT_TITLE);
                if (!name.equals(request.displayName()) && mouseX >= dialog.contentX() && mouseX < actionsX() - 4
                        && mouseY >= y && mouseY < y + dialog.buttonHeight()) hoveredName = request.displayName();
            }
            if (maxScroll() > 0 && visibleRows > 0) {
                int track = visibleRows * rowPitch();
                int thumb = Math.max(8, track * visibleRows / requests.size());
                int y = dialog.contentTop() + (track - thumb) * scrollOffset / maxScroll();
                drawRect(dialog.left + dialog.width - 7, dialog.contentTop(), dialog.left + dialog.width - 5,
                        dialog.contentTop() + track, net.peercraft.client.theme.SteampunkPalette.BORDER);
                drawRect(dialog.left + dialog.width - 7, y, dialog.left + dialog.width - 5, y + thumb,
                        net.peercraft.client.theme.SteampunkPalette.ACCENT);
            }
        }
        int statusY = requests == null || requests.isEmpty() ? (dialog.contentTop() + backY() - 28) / 2 - 4 : backY() - 28;
        status(statusMessage, statusY, statusColor);
        if (hoveredName != null) this.drawHoveringText(PeerCraftUi.wrap(this.fontRenderer, hoveredName, Math.max(1, dialog.contentWidth())), mouseX, mouseY);
    }

    private int trackHeight() { return visibleRows * rowPitch(); }
    private int thumbHeight() { return requests == null || requests.isEmpty() ? 8 : Math.max(8, trackHeight() * visibleRows / requests.size()); }
    private int thumbY() { return dialog.contentTop() + (trackHeight() - thumbHeight()) * scrollOffset / Math.max(1, maxScroll()); }
    private void dragScrollbar(int y) {
        int range = trackHeight() - thumbHeight();
        if (range <= 0) return;
        scrollOffset = (int) Math.round((y - dialog.contentTop() - scrollbarGrab) * (double) maxScroll() / range);
        rebuildRows();
    }
    @Override protected void mouseClicked(int x, int y, int button) throws IOException {
        if (button == 0 && maxScroll() > 0 && visibleRows > 0 && x >= dialog.left + dialog.width - 10
                && x < dialog.left + dialog.width - 2 && y >= dialog.contentTop() && y < dialog.contentTop() + trackHeight()) {
            scrollbarGrab = y >= thumbY() && y < thumbY() + thumbHeight() ? y - thumbY() : thumbHeight() / 2;
            dragScrollbar(y); return;
        }
        super.mouseClicked(x, y, button);
    }
    @Override protected void mouseClickMove(int x, int y, int button, long elapsed) {
        if (button == 0 && scrollbarGrab >= 0) { dragScrollbar(y); return; }
        super.mouseClickMove(x, y, button, elapsed);
    }
    @Override protected void mouseReleased(int x, int y, int button) {
        if (button == 0) scrollbarGrab = -1;
        super.mouseReleased(x, y, button);
    }

    @Override protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) { PeerCraftUi.setScreen(mc, lastScreen); return; }
        if (keyCode == org.lwjgl.input.Keyboard.KEY_NEXT || keyCode == org.lwjgl.input.Keyboard.KEY_PRIOR) {
            scrollOffset += (keyCode == org.lwjgl.input.Keyboard.KEY_NEXT ? 1 : -1) * Math.max(1, visibleRows);
            rebuildRows(); return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int delta = org.lwjgl.input.Mouse.getEventDWheel();
        int x = org.lwjgl.input.Mouse.getEventX() * width / mc.displayWidth;
        int y = height - org.lwjgl.input.Mouse.getEventY() * height / mc.displayHeight - 1;
        if (delta != 0 && x >= dialog.contentX() && x < dialog.left + dialog.width
                && y >= dialog.contentTop() && y < listBottom()) {
            scrollOffset -= (int) Math.signum(delta) * 3;
            rebuildRows();
        }
    }
}
