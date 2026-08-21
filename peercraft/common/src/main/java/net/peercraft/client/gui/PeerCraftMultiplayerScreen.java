package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.account.AccountClient;
import net.peercraft.network.p2p.P2PBridge;
import net.peercraft.network.rendezvous.AccountProtocol;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Vanilla's {@link JoinMultiplayerScreen} extended with a PeerCraft tab bar (Favorites /
 * Friends / Find Players), replacing the old separate "Friends" screen reachable only from the
 * account hub. "Favorites" reuses the vanilla server list/buttons completely unchanged; the
 * other two tabs are PeerCraft content built alongside it and swapped in via widget visibility
 * rather than re-running {@code init()} — the server list and LAN detector must not be torn
 * down and restarted every time the player switches tabs.
 *
 * Subclassing (rather than mixing into {@link JoinMultiplayerScreen} itself) works because every
 * widget vanilla's {@code init()} creates — including the ones it only keeps as local variables —
 * passes through the overridable {@code addRenderableWidget}, so capturing them by call order
 * ({@link #peercraft$stripFavoritesToOwnTab}) needs no mixin field access at all.
 */
public class PeerCraftMultiplayerScreen extends JoinMultiplayerScreen {

    private enum Tab { FAVORITES, FRIENDS, DISCOVER }

    private static final int CONTENT_TOP = 58;
    private static final int MAX_ROWS_SHOWN = 6;
    private static final int ROW_HEIGHT = 20;

    private final Screen lastScreen;
    private Tab currentTab;

    // ---- tab bar ----
    private Button tabFavoritesButton;
    private Button tabFriendsButton;
    private Button tabDiscoverButton;

    // ---- favorites tab: vanilla widgets, captured by call order during super.init() ----
    private boolean peercraft$capturingFavorites;
    private final List<AbstractWidget> favoritesWidgets = new ArrayList<>();
    private Button customRefreshButton;
    /**
     * {@link net.minecraft.client.gui.components.AbstractSelectionList} overrides {@code
     * mouseClicked}/{@code isMouseOver} without checking {@code visible}/{@code active} (unlike
     * plain {@link AbstractWidget}), so hiding the server list via {@code visible = false} alone
     * still let clicks on the Friends/Find Players tabs fall through to it and join whatever
     * favorite server happened to be underneath. Moved off-screen instead when not on Favorites.
     */
    private AbstractWidget favoritesList;
    // Join Server/Direct Connection/Add Server/Edit/Delete — kept visible on every tab (just
    // disabled off Favorites) instead of vanishing, so the footer doesn't change shape when
    // switching tabs. See applyTabVisibility().
    private Button favoritesSelectButton;
    private Button favoritesDirectButton;
    private Button favoritesAddButton;
    private Button favoritesEditButton;
    private Button favoritesDeleteButton;

    // ---- friends tab ----
    private final List<AbstractWidget> friendsStaticWidgets = new ArrayList<>();
    private final List<AbstractWidget> friendRowWidgets = new ArrayList<>();
    private List<AccountClient.FriendInfo> friends;
    private EditBox addByCodeBox;
    private Button addByCodeButton;
    private Component friendsStatusMessage = Component.empty();
    private int friendsStatusColor = PeerCraftUi.TEXT_MUTED;
    private int ticksSincePoll;
    private static final int POLL_INTERVAL_TICKS = 100;

    // ---- discover (find players) tab ----
    private final List<AbstractWidget> discoverStaticWidgets = new ArrayList<>();
    private final List<AbstractWidget> discoverResultWidgets = new ArrayList<>();
    private List<AccountClient.SearchResult> searchResults = List.of();
    private EditBox searchQueryBox;
    private Button searchButton;
    private Component discoverStatusMessage = Component.empty();
    private int discoverStatusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftMultiplayerScreen(Screen lastScreen) {
        this(lastScreen, Tab.FAVORITES);
    }

    /** Reopens on whichever tab was active — used by the custom Refresh button, see {@link #peercraft$stripFavoritesToOwnTab}. */
    private PeerCraftMultiplayerScreen(Screen lastScreen, Tab initialTab) {
        super(lastScreen);
        this.lastScreen = lastScreen;
        this.currentTab = initialTab;
    }

    @Override
    protected <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget) {
        T result = super.addRenderableWidget(widget);
        if (this.peercraft$capturingFavorites && widget instanceof AbstractWidget abstractWidget) {
            this.favoritesWidgets.add(abstractWidget);
        }
        return result;
    }

    @Override
    protected void init() {
        this.favoritesWidgets.clear();
        this.friendsStaticWidgets.clear();
        this.friendRowWidgets.clear();
        this.discoverStaticWidgets.clear();
        this.discoverResultWidgets.clear();

        this.peercraft$capturingFavorites = true;
        super.init();
        this.peercraft$capturingFavorites = false;

        peercraft$stripFavoritesToOwnTab();
        buildTabBar();
        buildFriendsTab();
        buildDiscoverTab();
        applyTabVisibility();
    }

    /**
     * The list itself plus select/direct/add/edit/delete belong only to Favorites. Refresh
     * reopens {@code new JoinMultiplayerScreen(lastScreen)} from a private vanilla method we
     * can't override — replaced with our own that reopens this screen instead. Back
     * ({@code onClose()}) is left alone since it's public/overridable and already correct.
     */
    private void peercraft$stripFavoritesToOwnTab() {
        // capture order from JoinMultiplayerScreen#init: list, select, direct, add, edit,
        // delete, refresh, back
        AbstractWidget list = this.favoritesWidgets.get(0);
        this.favoritesSelectButton = (Button) this.favoritesWidgets.get(1);
        this.favoritesDirectButton = (Button) this.favoritesWidgets.get(2);
        this.favoritesAddButton = (Button) this.favoritesWidgets.get(3);
        this.favoritesEditButton = (Button) this.favoritesWidgets.get(4);
        this.favoritesDeleteButton = (Button) this.favoritesWidgets.get(5);
        Button vanillaRefresh = (Button) this.favoritesWidgets.get(6);
        Button vanillaBack = (Button) this.favoritesWidgets.get(7);

        this.favoritesList = list;
        list.setRectangle(this.width, this.height - 64 - CONTENT_TOP, 0, CONTENT_TOP);

        vanillaRefresh.visible = false;
        this.favoritesWidgets.remove(7); // back — always visible, not tab-gated
        this.favoritesWidgets.remove(6); // vanilla refresh — permanently hidden, replaced below

        this.customRefreshButton = this.addRenderableWidget(Button.builder(vanillaRefresh.getMessage(),
                        b -> this.minecraft.setScreen(new PeerCraftMultiplayerScreen(this.lastScreen, this.currentTab)))
                .bounds(vanillaRefresh.getX(), vanillaRefresh.getY(), vanillaRefresh.getWidth(), vanillaRefresh.getHeight())
                .build());

        // Account button — used to be a full-width entry on the title screen; every PeerCraft
        // path already funnels through this screen (see TitleScreenMixin), so a small icon
        // tucked next to Back reaches it without competing for space with vanilla's own menu.
        // Not tab-gated, same as Back itself. Plain vanilla-styled glyph rather than a custom
        // face render, to stay visually consistent with the rest of the button row.
        this.addRenderableWidget(PeerCraftUi.squareGlyphButton(
                vanillaBack.getX() + vanillaBack.getWidth() + 6, vanillaBack.getY(), vanillaBack.getHeight(),
                "☺", Component.translatable("peercraft.gui.multiplayer.account_tooltip").getString(), b -> this.minecraft.setScreen(new PeerCraftAccountScreen(this))));

        // "Join by code" — same relocation, next to Add Server. Skipped in host mode
        // like the old title-screen button was: a host never needs to join someone else's room.
        if (!PeerCraftConfig.MODE_HOST.equals(PeerCraftConfig.mode())) {
            this.addRenderableWidget(PeerCraftUi.squareGlyphButton(
                    this.favoritesAddButton.getX() + this.favoritesAddButton.getWidth() + 6, this.favoritesAddButton.getY(), this.favoritesAddButton.getHeight(),
                    "▶", Component.translatable("peercraft.gui.multiplayer.join_by_code_tooltip").getString(), b -> this.minecraft.setScreen(new PeerCraftJoinScreen(this))));
        }
    }

    private void buildTabBar() {
        int barWidth = Math.min(this.width - 20, 380);
        int tabWidth = (barWidth - 8) / 3;
        int startX = this.width / 2 - barWidth / 2;
        int y = 30;

        this.tabFavoritesButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.multiplayer.tab_favorites"), b -> switchTab(Tab.FAVORITES))
                .bounds(startX, y, tabWidth, 20).build());
        this.tabFriendsButton = this.addRenderableWidget(Button.builder(friendsTabLabel(), b -> switchTab(Tab.FRIENDS))
                .bounds(startX + tabWidth + 4, y, tabWidth, 20).build());
        this.tabDiscoverButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.multiplayer.tab_discover"), b -> switchTab(Tab.DISCOVER))
                .bounds(startX + 2 * (tabWidth + 4), y, tabWidth, 20).build());
    }

    private Component friendsTabLabel() {
        int count = this.friends == null ? 0 : this.friends.size();
        return Component.translatable("peercraft.gui.multiplayer.tab_friends", count);
    }

    private void switchTab(Tab tab) {
        this.currentTab = tab;
        applyTabVisibility();
    }

    private void applyTabVisibility() {
        boolean fav = this.currentTab == Tab.FAVORITES;
        boolean fr = this.currentTab == Tab.FRIENDS;
        boolean disc = this.currentTab == Tab.DISCOVER;

        // Join Server/Direct Connection/Add Server/Edit/Delete stay visible on every tab instead
        // of vanishing — just disabled off Favorites, the same "nothing selected" look vanilla
        // already uses there, so the footer doesn't change shape when switching tabs. The list
        // itself is still moved off-screen below regardless of this loop — see its field comment.
        for (AbstractWidget w : this.favoritesWidgets) w.visible = true;
        this.onSelectedChange(); // vanilla: resyncs select/edit/delete against the real list selection
        this.favoritesDirectButton.active = true;
        this.favoritesAddButton.active = true;
        if (!fav) {
            this.favoritesSelectButton.active = false;
            this.favoritesDirectButton.active = false;
            this.favoritesAddButton.active = false;
            this.favoritesEditButton.active = false;
            this.favoritesDeleteButton.active = false;
        }

        for (AbstractWidget w : this.friendsStaticWidgets) w.visible = fr;
        for (AbstractWidget w : this.friendRowWidgets) w.visible = fr;
        for (AbstractWidget w : this.discoverStaticWidgets) w.visible = disc;
        for (AbstractWidget w : this.discoverResultWidgets) w.visible = disc;
        this.tabFavoritesButton.active = !fav;
        this.tabFriendsButton.active = !fr;
        this.tabDiscoverButton.active = !disc;

        // visible = true above doesn't stop the list from eating clicks — see the field comment.
        int listY = fav ? CONTENT_TOP : -30000;
        this.favoritesList.setRectangle(this.width, this.height - 64 - CONTENT_TOP, 0, listY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode != GLFW.GLFW_KEY_ENTER && keyCode != GLFW.GLFW_KEY_KP_ENTER) {
            return false;
        }
        if (this.currentTab == Tab.FRIENDS && this.addByCodeBox.isFocused() && this.addByCodeButton.active) {
            onAddByCode();
            return true;
        }
        if (this.currentTab == Tab.DISCOVER && this.searchQueryBox.isFocused() && this.searchButton.active) {
            onSearch();
            return true;
        }
        return false;
    }

    // ==================== FRIENDS TAB ====================

    private void buildFriendsTab() {
        int centerX = this.width / 2;
        int rowsBottom = CONTENT_TOP + 4 + MAX_ROWS_SHOWN * ROW_HEIGHT;

        this.addByCodeBox = new EditBox(this.font, centerX - 200, rowsBottom + 8, 120, 20, Component.translatable("peercraft.gui.multiplayer.add_by_code_field"));
        this.addByCodeBox.setMaxLength(6);
        this.addByCodeBox.setHint(Component.translatable("peercraft.gui.multiplayer.add_by_code_field"));
        this.friendsStaticWidgets.add(this.addRenderableWidget(this.addByCodeBox));

        this.addByCodeButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.multiplayer.add_by_code_button"), b -> onAddByCode())
                .bounds(centerX - 70, rowsBottom + 8, 150, 20).build());
        this.friendsStaticWidgets.add(this.addByCodeButton);

        this.friendsStaticWidgets.add(this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.multiplayer.friend_requests_button"), b -> this.minecraft.setScreen(new PeerCraftFriendRequestsScreen(this)))
                .bounds(centerX - 100, rowsBottom + 34, 200, 20).build()));

        rebuildFriendRows();
        if (this.friends == null) {
            loadFriends();
        }
    }

    private void loadFriends() {
        this.friendsStatusMessage = Component.translatable("peercraft.gui.multiplayer.loading_friends");
        this.friendsStatusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.listFriends(new AccountClient.FriendListCallback() {
            @Override
            public void onResult(List<AccountClient.FriendInfo> result) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    friends = result;
                    friendsStatusMessage = Component.empty();
                    tabFriendsButton.setMessage(friendsTabLabel());
                    rebuildFriendRows();
                });
            }

            @Override
            public void onTimeout() {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        friendsStatusMessage = Component.translatable("peercraft.gui.common.account_server_timeout");
                        friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                    }
                });
            }
        });
    }

    private void rebuildFriendRows() {
        for (AbstractWidget w : this.friendRowWidgets) {
            this.removeWidget(w);
        }
        this.friendRowWidgets.clear();

        if (this.friends == null) {
            return;
        }

        int centerX = this.width / 2;
        int top = CONTENT_TOP + 4;
        int shown = Math.min(this.friends.size(), MAX_ROWS_SHOWN);
        boolean fr = this.currentTab == Tab.FRIENDS;
        for (int i = 0; i < shown; i++) {
            AccountClient.FriendInfo friend = this.friends.get(i);
            int rowY = top + i * ROW_HEIGHT;

            boolean canConnect = friend.status() == AccountProtocol.STATUS_HOSTING;
            Button.Builder connectBuilder = Button.builder(Component.translatable("peercraft.gui.multiplayer.connect"), b -> onConnectToFriend(friend))
                    .bounds(centerX + 20, rowY, 110, 18);
            if (!canConnect) {
                connectBuilder.tooltip(Tooltip.create(Component.translatable("peercraft.gui.multiplayer.not_hosting_tooltip")));
            }
            Button connectButton = connectBuilder.build();
            connectButton.active = canConnect;
            connectButton.visible = fr;
            this.addRenderableWidget(connectButton);
            this.friendRowWidgets.add(connectButton);

            Button removeButton = Button.builder(Component.translatable("peercraft.gui.multiplayer.remove"), b -> confirmRemoveFriend(friend))
                    .bounds(centerX + 135, rowY, 60, 18).build();
            removeButton.visible = fr;
            this.addRenderableWidget(removeButton);
            this.friendRowWidgets.add(removeButton);
        }

        if (this.friends.size() > MAX_ROWS_SHOWN) {
            this.friendsStatusMessage = Component.translatable("peercraft.gui.common.shown_first", MAX_ROWS_SHOWN, this.friends.size());
            this.friendsStatusColor = PeerCraftUi.TEXT_MUTED;
        } else if (this.friends.isEmpty()) {
            this.friendsStatusMessage = Component.translatable("peercraft.gui.multiplayer.no_friends_yet", Component.translatable("peercraft.gui.multiplayer.tab_discover"));
            this.friendsStatusColor = PeerCraftUi.TEXT_MUTED;
        } else {
            this.friendsStatusMessage = Component.empty();
        }
    }

    private void onAddByCode() {
        String code = this.addByCodeBox.getValue().trim().toUpperCase(Locale.ROOT);
        if (code.length() != 6) {
            this.friendsStatusMessage = Component.translatable("peercraft.gui.login_code.code_length_error");
            this.friendsStatusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        this.addByCodeButton.active = false;
        this.friendsStatusMessage = Component.translatable("peercraft.gui.multiplayer.searching_player");
        this.friendsStatusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.lookupFriendCode(code, new AccountClient.FriendCodeLookupCallback() {
            @Override
            public void onResult(boolean found, UUID accountId, boolean licensed, String displayName) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    if (!found) {
                        friendsStatusMessage = Component.translatable("peercraft.gui.multiplayer.player_not_found");
                        friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                        addByCodeButton.active = true;
                        return;
                    }
                    sendFriendRequest(accountId, displayName, ok -> {
                        if (ok) {
                            friendsStatusMessage = Component.translatable("peercraft.gui.multiplayer.request_sent", displayName);
                            friendsStatusColor = PeerCraftUi.TEXT_SUCCESS;
                        }
                    });
                    addByCodeButton.active = true;
                });
            }

            @Override
            public void onTimeout() {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    friendsStatusMessage = Component.translatable("peercraft.gui.common.account_server_timeout");
                    friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                    addByCodeButton.active = true;
                });
            }
        });
    }

    /** Removing a friend can't be undone from this screen — confirm first rather than losing them to a misclick. */
    private void confirmRemoveFriend(AccountClient.FriendInfo friend) {
        this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
            this.minecraft.setScreen(this);
            if (confirmed) {
                onRemoveFriend(friend);
            }
        }, Component.translatable("peercraft.gui.multiplayer.remove_friend_confirm_title"),
                Component.translatable("peercraft.gui.multiplayer.remove_friend_confirm_message", friend.displayName())));
    }

    private void onRemoveFriend(AccountClient.FriendInfo friend) {
        AccountClient.INSTANCE.removeFriend(friend.accountId(), new AccountClient.AckCallback() {
            @Override
            public void onSuccess() {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    friends.remove(friend);
                    tabFriendsButton.setMessage(friendsTabLabel());
                    rebuildFriendRows();
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        friendsStatusMessage = Component.literal(reason);
                        friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                    }
                });
            }
        });
    }

    /** The whole point of this feature — no room code ever typed, just the code the server already told us the friend is hosting under. */
    private void onConnectToFriend(AccountClient.FriendInfo friend) {
        this.friendsStatusMessage = Component.translatable("peercraft.gui.multiplayer.connecting_to", friend.displayName());
        this.friendsStatusColor = PeerCraftUi.TEXT_MUTED;
        P2PBridge.INSTANCE.startClientViaRendezvous(friend.roomCode(), PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(),
                new P2PBridge.ConnectListener() {
                    @Override
                    public void onStatus(String message) {
                        runOnClientThread(() -> {
                            if (stillOnThisScreen()) {
                                friendsStatusMessage = Component.literal(message);
                                friendsStatusColor = PeerCraftUi.TEXT_MUTED;
                            }
                        });
                    }

                    @Override
                    public void onConnected() {
                        runOnClientThread(() -> {
                            if (!stillOnThisScreen()) {
                                return;
                            }
                            int port = P2PBridge.INSTANCE.getProxyPort();
                            ServerAddress address = new ServerAddress("127.0.0.1", port);
                            ServerData serverData = new ServerData("PeerCraft", "127.0.0.1:" + port, ServerData.Type.OTHER);
                            ConnectScreen.startConnecting(lastScreen, minecraft, address, serverData, false, null);
                        });
                    }

                    @Override
                    public void onFailed(String reason) {
                        runOnClientThread(() -> {
                            if (stillOnThisScreen()) {
                                friendsStatusMessage = Component.literal(reason);
                                friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                            }
                        });
                    }
                });
    }

    // ==================== DISCOVER (FIND PLAYERS) TAB ====================

    private void buildDiscoverTab() {
        int centerX = this.width / 2;
        int top = CONTENT_TOP + 4;

        this.searchQueryBox = new EditBox(this.font, centerX - 100, top, 200, 20, Component.translatable("peercraft.gui.multiplayer.search_query_field"));
        this.searchQueryBox.setMaxLength(16);
        this.searchQueryBox.setHint(Component.translatable("peercraft.gui.multiplayer.search_query_hint"));
        this.discoverStaticWidgets.add(this.addRenderableWidget(this.searchQueryBox));

        this.searchButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.multiplayer.search_button"), b -> onSearch())
                .bounds(centerX + 105, top, 90, 20).build());
        this.discoverStaticWidgets.add(this.searchButton);

        rebuildSearchResults();
    }

    private void onSearch() {
        String query = this.searchQueryBox.getValue().trim();
        this.searchButton.active = false;
        this.discoverStatusMessage = Component.translatable("peercraft.gui.multiplayer.searching");
        this.discoverStatusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.searchAccounts(query, new AccountClient.SearchCallback() {
            @Override
            public void onResult(List<AccountClient.SearchResult> found) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    searchResults = found;
                    discoverStatusMessage = found.isEmpty() ? Component.translatable("peercraft.gui.multiplayer.nobody_found") : Component.empty();
                    discoverStatusColor = PeerCraftUi.TEXT_MUTED;
                    searchButton.active = true;
                    rebuildSearchResults();
                });
            }

            @Override
            public void onTimeout() {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    discoverStatusMessage = Component.translatable("peercraft.gui.common.account_server_timeout");
                    discoverStatusColor = PeerCraftUi.TEXT_ERROR;
                    searchButton.active = true;
                });
            }
        });
    }

    private void rebuildSearchResults() {
        for (AbstractWidget w : this.discoverResultWidgets) {
            this.removeWidget(w);
        }
        this.discoverResultWidgets.clear();

        int centerX = this.width / 2;
        int top = CONTENT_TOP + 32;
        boolean disc = this.currentTab == Tab.DISCOVER;
        int shown = Math.min(this.searchResults.size(), MAX_ROWS_SHOWN);
        for (int i = 0; i < shown; i++) {
            AccountClient.SearchResult result = this.searchResults.get(i);
            Button addButton = Button.builder(Component.translatable("peercraft.gui.multiplayer.add_friend"), b -> onAddFriend(result))
                    .bounds(centerX + 30, top + i * ROW_HEIGHT, 170, 18).build();
            addButton.visible = disc;
            this.addRenderableWidget(addButton);
            this.discoverResultWidgets.add(addButton);
        }
    }

    private void onAddFriend(AccountClient.SearchResult result) {
        sendFriendRequest(result.accountId(), result.displayName(), ok -> {
            if (ok) {
                discoverStatusMessage = Component.translatable("peercraft.gui.multiplayer.request_sent", result.displayName());
                discoverStatusColor = PeerCraftUi.TEXT_SUCCESS;
            }
        });
    }

    private interface RequestResultHandler {
        void handle(boolean success);
    }

    private void sendFriendRequest(UUID targetAccountId, String displayName, RequestResultHandler onDone) {
        AccountClient.INSTANCE.sendFriendRequest(targetAccountId, new AccountClient.AckCallback() {
            @Override
            public void onSuccess() {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        onDone.handle(true);
                    }
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        friendsStatusMessage = Component.literal(reason);
                        friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                        discoverStatusMessage = Component.literal(reason);
                        discoverStatusColor = PeerCraftUi.TEXT_ERROR;
                        onDone.handle(false);
                    }
                });
            }
        });
    }

    // ==================== shared plumbing ====================

    @Override
    public void tick() {
        super.tick();
        if (this.friends == null) {
            return; // initial load still in flight
        }
        if (++this.ticksSincePoll < POLL_INTERVAL_TICKS) {
            return;
        }
        this.ticksSincePoll = 0;
        AccountClient.INSTANCE.listFriends(new AccountClient.FriendListCallback() {
            @Override
            public void onResult(List<AccountClient.FriendInfo> result) {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        friends = result;
                        tabFriendsButton.setMessage(friendsTabLabel());
                        rebuildFriendRows();
                    }
                });
            }

            @Override
            public void onTimeout() {
                // silent — keep showing the last known list rather than clearing it
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
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;

        if (this.currentTab == Tab.FRIENDS) {
            int top = CONTENT_TOP + 4;
            int shown = this.friends == null ? 0 : Math.min(this.friends.size(), MAX_ROWS_SHOWN);
            for (int i = 0; i < shown; i++) {
                AccountClient.FriendInfo friend = this.friends.get(i);
                int rowY = top + i * ROW_HEIGHT + 5;
                int afterBadgeX = PeerCraftUi.drawNameWithBadge(graphics, this.font, friend.displayName(), friend.licensed(), centerX - 200, rowY, PeerCraftUi.TEXT_TITLE);

                String status = switch (friend.status()) {
                    case AccountProtocol.STATUS_HOSTING -> Component.translatable("peercraft.gui.multiplayer.status_hosting", friend.roomCode()).getString();
                    case AccountProtocol.STATUS_ONLINE -> Component.translatable("peercraft.gui.multiplayer.status_online").getString();
                    default -> Component.translatable("peercraft.gui.multiplayer.status_offline").getString();
                };
                int statusColor = switch (friend.status()) {
                    case AccountProtocol.STATUS_HOSTING -> PeerCraftUi.TEXT_ACCENT;
                    case AccountProtocol.STATUS_ONLINE -> PeerCraftUi.TEXT_SUCCESS;
                    default -> PeerCraftUi.TEXT_MUTED;
                };
                graphics.drawString(this.font, " — " + status, afterBadgeX, rowY, statusColor, false);
            }
            graphics.drawCenteredString(this.font, this.friendsStatusMessage, centerX, this.height - 78, this.friendsStatusColor);
        } else if (this.currentTab == Tab.DISCOVER) {
            int top = CONTENT_TOP + 32;
            int shown = Math.min(this.searchResults.size(), MAX_ROWS_SHOWN);
            for (int i = 0; i < shown; i++) {
                AccountClient.SearchResult result = this.searchResults.get(i);
                PeerCraftUi.drawNameWithBadge(graphics, this.font, result.displayName(), result.licensed(), centerX - 200, top + i * ROW_HEIGHT + 5, PeerCraftUi.TEXT_TITLE);
            }
            graphics.drawCenteredString(this.font, this.discoverStatusMessage, centerX, this.height - 78, this.discoverStatusColor);
        }
    }
}
