package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.client.modsync.ClientModSyncAgent;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.account.AccountClient;
import net.peercraft.network.p2p.P2PBridge;
import net.peercraft.network.rendezvous.AccountProtocol;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../PeerCraftMultiplayerScreen.java} — vanilla's
 * {@link JoinMultiplayerScreen} with a PeerCraft tab bar (Favorites / Friends / Find Players /
 * Games). Backport differences:
 * <ul>
 *   <li>Widget capture funnels through {@code addButton} (there is no {@code addRenderableWidget}
 *       on 1.16.5); the server list is the real {@code this.serverSelectionList} field, not a
 *       captured widget, and is hidden by moving it off-screen with {@code updateSize}.</li>
 *   <li>No {@code repositionElements()} — 1.16.5 re-runs the full {@code init()} on every
 *       re-show/resize, so the tab state is simply reapplied at the end of {@code init()}.</li>
 *   <li>The version filter has no {@code CycleButton} — it's a plain {@link Button} that cycles
 *       the observed version list and rewrites its own label.</li>
 *   <li>{@code Tooltip} is gone (dropped); {@code ConfirmLinkScreen.confirmLink} is inlined;
 *       {@code Button.builder} → {@link Btn}; {@code Component.translatable/literal} →
 *       {@code TranslatableComponent}/{@code TextComponent}; switch expressions → statements;
 *       {@code String.isBlank} → {@link #blank}.</li>
 * </ul>
 */
public class PeerCraftMultiplayerScreen extends JoinMultiplayerScreen {

    private enum Tab { FAVORITES, FRIENDS, DISCOVER, GAMES }

    private static final int CONTENT_TOP = 58;
    private static final int MAX_ROWS_SHOWN = 6;
    private static final int ROW_HEIGHT = 20;

    private static final String FEEDBACK_MAILTO = "mailto:peercraft2@gmail.com?subject=PeerCraft%20feedback";
    private static final String DONATE_URL = "https://boosty.to/peercraft";
    private static final int FOOTER_BUTTON_WIDTH = 70;
    private static final int FOOTER_BUTTON_HEIGHT = 20;

    private final Screen lastScreen;
    private Tab currentTab;

    // ---- tab bar ----
    private Button tabFavoritesButton;
    private Button tabFriendsButton;
    private Button tabDiscoverButton;
    private Button tabGamesButton;

    // ---- favorites tab: vanilla buttons, captured by call order during super.init() ----
    private boolean peercraft$capturingFavorites;
    private final List<AbstractWidget> favoritesWidgets = new ArrayList<>();
    private Button customRefreshButton;
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
    private Component friendsStatusMessage = TextComponent.EMPTY;
    private int friendsStatusColor = PeerCraftUi.TEXT_MUTED;
    private int ticksSincePoll;
    private static final int POLL_INTERVAL_TICKS = 100;

    // ---- discover (find players) tab ----
    private final List<AbstractWidget> discoverStaticWidgets = new ArrayList<>();
    private final List<AbstractWidget> discoverResultWidgets = new ArrayList<>();
    private List<AccountClient.SearchResult> searchResults = Collections.emptyList();
    private EditBox searchQueryBox;
    private Button searchButton;
    private Component discoverStatusMessage = TextComponent.EMPTY;
    private int discoverStatusColor = PeerCraftUi.TEXT_MUTED;
    // First visible search-result row — the mouse wheel walks this offset when the result list
    // is longer than the rows that fit, with a scrollbar down the right edge showing the spot.
    private int discoverScroll;

    // ---- games (public game browser) tab ----
    private final List<AbstractWidget> gamesStaticWidgets = new ArrayList<>();
    private final List<AbstractWidget> gameRowWidgets = new ArrayList<>();
    private List<AccountClient.PublicGameInfo> games;
    private List<AccountClient.PublicGameInfo> filteredGames = Collections.emptyList();
    private EditBox gameSearchBox;
    private Button gameVersionFilterButton;
    private List<String> versionFilterValues = new ArrayList<>();
    private String selectedVersionFilter = "";
    private Component gamesStatusMessage = TextComponent.EMPTY;
    private int gamesStatusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftMultiplayerScreen(Screen lastScreen) {
        this(lastScreen, Tab.FAVORITES);
    }

    private PeerCraftMultiplayerScreen(Screen lastScreen, Tab initialTab) {
        super(lastScreen);
        this.lastScreen = lastScreen;
        this.currentTab = initialTab;
    }

    @Override
    protected <T extends AbstractWidget> T addButton(T widget) {
        T result = super.addButton(widget);
        if (this.peercraft$capturingFavorites) {
            this.favoritesWidgets.add(widget);
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
        this.gamesStaticWidgets.clear();
        this.gameRowWidgets.clear();

        this.peercraft$capturingFavorites = true;
        super.init();
        this.peercraft$capturingFavorites = false;

        peercraft$stripFavoritesToOwnTab();
        peercraft$addFooterButtons();
        buildTabBar();
        buildFriendsTab();
        buildDiscoverTab();
        buildGamesTab();
        applyTabVisibility();
    }

    private void peercraft$addFooterButtons() {
        // Feedback/Donate stack in the bottom-left corner. Vanilla's two footer button rows
        // (y = height-52 / height-28) reach left to width/2-154; when the window is wide
        // enough to leave a clear gutter there, sit in it as before, otherwise lift the stack
        // above both rows so it never lands on "Join Server" / "Edit".
        boolean gutterFits = this.width / 2 - 154 >= FOOTER_BUTTON_WIDTH + 6;
        int donateY = gutterFits ? this.height - 4 - FOOTER_BUTTON_HEIGHT
                                 : this.height - 52 - 6 - FOOTER_BUTTON_HEIGHT;
        int feedbackY = donateY - FOOTER_BUTTON_HEIGHT - 4;

        this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.title.feedback_button"), b -> openLink(FEEDBACK_MAILTO))
                .bounds(2, feedbackY, FOOTER_BUTTON_WIDTH, FOOTER_BUTTON_HEIGHT)
                .build());

        this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.title.donate_button"), b -> openLink(DONATE_URL))
                .bounds(2, donateY, FOOTER_BUTTON_WIDTH, FOOTER_BUTTON_HEIGHT)
                .build());
    }

    /** 1.16.5 has no {@code ConfirmLinkScreen.confirmLink} helper — inline vanilla's own URL-confirm flow. */
    private void openLink(String url) {
        this.minecraft.setScreen(new ConfirmLinkScreen(confirmed -> {
            if (confirmed) {
                Util.getPlatform().openUri(url);
            }
            this.minecraft.setScreen(this);
        }, url, false));
    }

    private void peercraft$stripFavoritesToOwnTab() {
        // addButton capture order from JoinMultiplayerScreen#init on 1.16.5 (the server list is
        // added via addWidget, not addButton, so it is NOT in this list): select, direct, add,
        // edit, delete, refresh, back.
        this.favoritesSelectButton = (Button) this.favoritesWidgets.get(0);
        this.favoritesDirectButton = (Button) this.favoritesWidgets.get(1);
        this.favoritesAddButton = (Button) this.favoritesWidgets.get(2);
        this.favoritesEditButton = (Button) this.favoritesWidgets.get(3);
        this.favoritesDeleteButton = (Button) this.favoritesWidgets.get(4);
        Button vanillaRefresh = (Button) this.favoritesWidgets.get(5);
        Button vanillaBack = (Button) this.favoritesWidgets.get(6);

        layoutFavoritesList(CONTENT_TOP);

        vanillaRefresh.visible = false;
        this.favoritesWidgets.remove(6); // back — always visible, not tab-gated
        this.favoritesWidgets.remove(5); // vanilla refresh — permanently hidden, replaced below

        this.customRefreshButton = this.addButton(Btn.builder(vanillaRefresh.getMessage(),
                        b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftMultiplayerScreen(this.lastScreen, this.currentTab)))
                .bounds(vanillaRefresh.x, vanillaRefresh.y, vanillaRefresh.getWidth(), vanillaRefresh.getHeight())
                .build());

        int glyphSize = vanillaBack.getHeight();
        int accountGlyphX = vanillaBack.x + vanillaBack.getWidth() + 6;
        this.addButton(PeerCraftUi.squareGlyphButton(
                accountGlyphX, vanillaBack.y, glyphSize,
                "☺", new TranslatableComponent("peercraft.gui.multiplayer.account_tooltip").getString(),
                b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftAccountScreen(this))));

        this.addButton(PeerCraftUi.squareGlyphButton(
                accountGlyphX + glyphSize + 6, vanillaBack.y, glyphSize,
                "⚙", new TranslatableComponent("peercraft.gui.settings.glyph_tooltip").getString(),
                b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftSettingsScreen(this))));

        if (!PeerCraftConfig.MODE_HOST.equals(PeerCraftConfig.mode())) {
            this.addButton(PeerCraftUi.squareGlyphButton(
                    this.favoritesAddButton.x + this.favoritesAddButton.getWidth() + 6, this.favoritesAddButton.y, this.favoritesAddButton.getHeight(),
                    "▶", new TranslatableComponent("peercraft.gui.multiplayer.join_by_code_tooltip").getString(),
                    b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftJoinScreen(this))));
        }
    }

    /** Off-screen {@code top} hides the list without letting it eat clicks (see the src/main field comment). */
    private void layoutFavoritesList(int top) {
        int bottom = top < 0 ? top + (this.height - 64 - CONTENT_TOP) : this.height - 64;
        this.serverSelectionList.updateSize(this.width, this.height, top, bottom);
        this.serverSelectionList.setLeftPos(0);
    }

    private void buildTabBar() {
        int barWidth = Math.min(this.width - 20, 460);
        int tabWidth = (barWidth - 12) / 4;
        int startX = this.width / 2 - barWidth / 2;
        int y = 30;

        this.tabFavoritesButton = this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.multiplayer.tab_favorites"), b -> switchTab(Tab.FAVORITES))
                .bounds(startX, y, tabWidth, 20).build());
        this.tabFriendsButton = this.addButton(Btn.builder(friendsTabLabel(), b -> switchTab(Tab.FRIENDS))
                .bounds(startX + tabWidth + 4, y, tabWidth, 20).build());
        this.tabDiscoverButton = this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.multiplayer.tab_discover"), b -> switchTab(Tab.DISCOVER))
                .bounds(startX + 2 * (tabWidth + 4), y, tabWidth, 20).build());
        this.tabGamesButton = this.addButton(Btn.builder(gamesTabLabel(), b -> switchTab(Tab.GAMES))
                .bounds(startX + 3 * (tabWidth + 4), y, tabWidth, 20).build());
    }

    private Component friendsTabLabel() {
        int count = this.friends == null ? 0 : this.friends.size();
        return new TranslatableComponent("peercraft.gui.multiplayer.tab_friends", count);
    }

    private Component gamesTabLabel() {
        int count = this.games == null ? 0 : this.games.size();
        return new TranslatableComponent("peercraft.gui.multiplayer.tab_games", count);
    }

    private void switchTab(Tab tab) {
        this.currentTab = tab;
        applyTabVisibility();
    }

    // ---- adaptive vertical layout (kept in step with src/main) --------------------------
    // Keeps the Friends input row and the Friends/Discover/Games result lists clear of the
    // bottom control strip at a small window size / high GUI scale — a fixed y=182 layout
    // let them ride onto the footer (see the bug report).

    /** Y of the Friends "Add by code" row: just below a full friend list, pulled up on a short window. */
    private int friendsInputTop() {
        return Math.min(CONTENT_TOP + 4 + MAX_ROWS_SHOWN * ROW_HEIGHT, this.height - 124);
    }

    /** ROW_HEIGHT rows that fit in [rowsTop, rowsBottom], capped at MAX_ROWS_SHOWN. */
    private int rowsFitting(int rowsTop, int rowsBottom) {
        return Math.max(0, Math.min(MAX_ROWS_SHOWN, (rowsBottom - rowsTop) / ROW_HEIGHT));
    }

    /** Y of the per-tab status line, just above the persistent footer row. */
    private int statusLineY() {
        return this.height - 64;
    }

    // ---- discover (find players) result scrolling (kept in step with src/main) ------------

    private int discoverResultsTop() {
        return CONTENT_TOP + 32;
    }

    private int discoverVisibleRows() {
        return rowsFitting(discoverResultsTop(), statusLineY() - 4);
    }

    private int discoverMaxScroll() {
        return Math.max(0, this.searchResults.size() - discoverVisibleRows());
    }

    private void setDiscoverScroll(int value) {
        int clamped = Math.max(0, Math.min(value, discoverMaxScroll()));
        if (clamped != this.discoverScroll) {
            this.discoverScroll = clamped;
            rebuildSearchResults();
        }
    }

    private boolean overDiscoverResults(double mouseY) {
        return mouseY >= discoverResultsTop()
                && mouseY < discoverResultsTop() + discoverVisibleRows() * ROW_HEIGHT;
    }

    /** 1.16.5's pre-1.20.2 scroll callback: one delta arg, positive = wheel up. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.currentTab == Tab.DISCOVER && delta != 0 && discoverMaxScroll() > 0 && overDiscoverResults(mouseY)) {
            setDiscoverScroll(this.discoverScroll - (int) Math.signum(delta));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void applyTabVisibility() {
        boolean fav = this.currentTab == Tab.FAVORITES;
        boolean fr = this.currentTab == Tab.FRIENDS;
        boolean disc = this.currentTab == Tab.DISCOVER;
        boolean games = this.currentTab == Tab.GAMES;

        // Join Server/Direct Connection/Add Server/Edit/Delete stay visible on every tab —
        // just disabled (greyed) off Favorites, so the footer doesn't change shape between tabs.
        for (AbstractWidget w : this.favoritesWidgets) {
            w.visible = true;
        }
        this.onSelectedChange();
        this.favoritesDirectButton.active = true;
        this.favoritesAddButton.active = true;
        if (!fav) {
            this.favoritesSelectButton.active = false;
            this.favoritesDirectButton.active = false;
            this.favoritesAddButton.active = false;
            this.favoritesEditButton.active = false;
            this.favoritesDeleteButton.active = false;
        }

        for (AbstractWidget w : this.friendsStaticWidgets) {
            w.visible = fr;
        }
        for (AbstractWidget w : this.friendRowWidgets) {
            w.visible = fr;
        }
        for (AbstractWidget w : this.discoverStaticWidgets) {
            w.visible = disc;
        }
        for (AbstractWidget w : this.discoverResultWidgets) {
            w.visible = disc;
        }
        for (AbstractWidget w : this.gamesStaticWidgets) {
            w.visible = games;
        }
        for (AbstractWidget w : this.gameRowWidgets) {
            w.visible = games;
        }
        this.tabFavoritesButton.active = !fav;
        this.tabFriendsButton.active = !fr;
        this.tabDiscoverButton.active = !disc;
        this.tabGamesButton.active = !games;

        layoutFavoritesList(fav ? CONTENT_TOP : -30000);
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
        int rowsBottom = friendsInputTop();

        this.addByCodeBox = new EditBox(this.font, centerX - 200, rowsBottom + 8, 120, 20, new TranslatableComponent("peercraft.gui.multiplayer.add_by_code_field"));
        this.addByCodeBox.setMaxLength(6);
        PeerCraftUi.placeholder(this.addByCodeBox, new TranslatableComponent("peercraft.gui.multiplayer.add_by_code_field").getString());
        this.friendsStaticWidgets.add(this.addButton(this.addByCodeBox));

        this.addByCodeButton = this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.multiplayer.add_by_code_button"), b -> onAddByCode())
                .bounds(centerX - 70, rowsBottom + 8, 150, 20).build());
        this.friendsStaticWidgets.add(this.addByCodeButton);

        this.friendsStaticWidgets.add(this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.multiplayer.friend_requests_button"), b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftFriendRequestsScreen(this)))
                .bounds(centerX - 100, rowsBottom + 34, 200, 20).build()));

        rebuildFriendRows();
        if (this.friends == null) {
            loadFriends();
        }
    }

    private void loadFriends() {
        this.friendsStatusMessage = new TranslatableComponent("peercraft.gui.multiplayer.loading_friends");
        this.friendsStatusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.listFriends(new AccountClient.FriendListCallback() {
            @Override
            public void onResult(List<AccountClient.FriendInfo> result) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    friends = result;
                    friendsStatusMessage = TextComponent.EMPTY;
                    tabFriendsButton.setMessage(friendsTabLabel());
                    rebuildFriendRows();
                });
            }

            @Override
            public void onTimeout() {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        friendsStatusMessage = new TranslatableComponent("peercraft.gui.common.account_server_timeout");
                        friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                    }
                });
            }
        });
    }

    private void rebuildFriendRows() {
        for (AbstractWidget w : this.friendRowWidgets) {
            peercraft$remove(w);
        }
        this.friendRowWidgets.clear();

        if (this.friends == null) {
            return;
        }

        int centerX = this.width / 2;
        int top = CONTENT_TOP + 4;
        int shown = Math.min(this.friends.size(), rowsFitting(top, friendsInputTop() + 4));
        boolean fr = this.currentTab == Tab.FRIENDS;
        for (int i = 0; i < shown; i++) {
            AccountClient.FriendInfo friend = this.friends.get(i);
            int rowY = top + i * ROW_HEIGHT;

            boolean canConnect = friend.status() == AccountProtocol.STATUS_HOSTING;
            Button connectButton = Btn.builder(new TranslatableComponent("peercraft.gui.multiplayer.connect"), b -> onConnectToFriend(friend))
                    .bounds(centerX + 20, rowY, 110, 18).build();
            connectButton.active = canConnect;
            connectButton.visible = fr;
            this.addButton(connectButton);
            this.friendRowWidgets.add(connectButton);

            Button removeButton = Btn.builder(new TranslatableComponent("peercraft.gui.multiplayer.remove"), b -> confirmRemoveFriend(friend))
                    .bounds(centerX + 135, rowY, 60, 18).build();
            removeButton.visible = fr;
            this.addButton(removeButton);
            this.friendRowWidgets.add(removeButton);
        }

        if (this.friends.size() > shown) {
            this.friendsStatusMessage = new TranslatableComponent("peercraft.gui.common.shown_first", shown, this.friends.size());
            this.friendsStatusColor = PeerCraftUi.TEXT_MUTED;
        } else if (this.friends.isEmpty()) {
            this.friendsStatusMessage = new TranslatableComponent("peercraft.gui.multiplayer.no_friends_yet", new TranslatableComponent("peercraft.gui.multiplayer.tab_discover"));
            this.friendsStatusColor = PeerCraftUi.TEXT_MUTED;
        } else {
            this.friendsStatusMessage = TextComponent.EMPTY;
        }
    }

    private void onAddByCode() {
        String code = this.addByCodeBox.getValue().trim().toUpperCase(Locale.ROOT);
        if (code.length() != 6) {
            this.friendsStatusMessage = new TranslatableComponent("peercraft.gui.login_code.code_length_error");
            this.friendsStatusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        this.addByCodeButton.active = false;
        this.friendsStatusMessage = new TranslatableComponent("peercraft.gui.multiplayer.searching_player");
        this.friendsStatusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.lookupFriendCode(code, new AccountClient.FriendCodeLookupCallback() {
            @Override
            public void onResult(boolean found, UUID accountId, boolean licensed, String displayName) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    if (!found) {
                        friendsStatusMessage = new TranslatableComponent("peercraft.gui.multiplayer.player_not_found");
                        friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                        addByCodeButton.active = true;
                        return;
                    }
                    sendFriendRequest(accountId, displayName, ok -> {
                        if (ok) {
                            friendsStatusMessage = new TranslatableComponent("peercraft.gui.multiplayer.request_sent", displayName);
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
                    friendsStatusMessage = new TranslatableComponent("peercraft.gui.common.account_server_timeout");
                    friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                    addByCodeButton.active = true;
                });
            }
        });
    }

    private void confirmRemoveFriend(AccountClient.FriendInfo friend) {
        PeerCraftUi.setScreen(this.minecraft, new ConfirmScreen(confirmed -> {
            PeerCraftUi.setScreen(this.minecraft, this);
            if (confirmed) {
                onRemoveFriend(friend);
            }
        }, new TranslatableComponent("peercraft.gui.multiplayer.remove_friend_confirm_title"),
                new TranslatableComponent("peercraft.gui.multiplayer.remove_friend_confirm_message", friend.displayName())));
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
                        friendsStatusMessage = new TextComponent(reason);
                        friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                    }
                });
            }
        });
    }

    private void onConnectToFriend(AccountClient.FriendInfo friend) {
        this.friendsStatusMessage = new TranslatableComponent("peercraft.gui.multiplayer.connecting_to", friend.displayName());
        this.friendsStatusColor = PeerCraftUi.TEXT_MUTED;
        P2PBridge.INSTANCE.startClientViaRendezvous(friend.roomCode(), PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(),
                new P2PBridge.ConnectListener() {
                    @Override
                    public void onStatus(String message) {
                        runOnClientThread(() -> {
                            if (stillOnThisScreen()) {
                                friendsStatusMessage = new TranslatableComponent(message);
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
                            startVanillaConnect();
                        });
                    }

                    @Override
                    public void onFailed(String reason) {
                        runOnClientThread(() -> {
                            if (stillOnThisScreen()) {
                                friendsStatusMessage = new TranslatableComponent(reason);
                                friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                            }
                        });
                    }
                }, new ClientModSyncAgent(this, friend.roomCode()));
    }

    /** 1.16.5 has no {@code ConnectScreen.startConnecting} — the constructor parses host:port out of ServerData.ip and starts the connection itself. */
    private void startVanillaConnect() {
        int port = P2PBridge.INSTANCE.getProxyPort();
        ServerData serverData = new ServerData("PeerCraft", "127.0.0.1:" + port, false);
        this.minecraft.setScreen(new ConnectScreen(this.lastScreen, this.minecraft, serverData));
    }

    // ==================== DISCOVER (FIND PLAYERS) TAB ====================

    private void buildDiscoverTab() {
        int centerX = this.width / 2;
        int top = CONTENT_TOP + 4;

        this.searchQueryBox = new EditBox(this.font, centerX - 100, top, 200, 20, new TranslatableComponent("peercraft.gui.multiplayer.search_query_field"));
        this.searchQueryBox.setMaxLength(16);
        PeerCraftUi.placeholder(this.searchQueryBox, new TranslatableComponent("peercraft.gui.multiplayer.search_query_hint").getString());
        this.discoverStaticWidgets.add(this.addButton(this.searchQueryBox));

        this.searchButton = this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.multiplayer.search_button"), b -> onSearch())
                .bounds(centerX + 105, top, 90, 20).build());
        this.discoverStaticWidgets.add(this.searchButton);

        rebuildSearchResults();
    }

    private void onSearch() {
        // Player search is an account query — with no session AccountClient.searchAccounts()
        // just returns an empty list, which the "Nobody found" line makes indistinguishable
        // from a real no-match. Tell the player they need to log in instead.
        if (AccountClient.INSTANCE.getCurrentSession() == null) {
            this.discoverStatusMessage = new TranslatableComponent("peercraft.gui.multiplayer.search_requires_login");
            this.discoverStatusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        String query = this.searchQueryBox.getValue().trim();
        this.searchButton.active = false;
        this.discoverStatusMessage = new TranslatableComponent("peercraft.gui.multiplayer.searching");
        this.discoverStatusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.searchAccounts(query, new AccountClient.SearchCallback() {
            @Override
            public void onResult(List<AccountClient.SearchResult> found) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    searchResults = found;
                    discoverScroll = 0; // a fresh result set starts at the top
                    discoverStatusMessage = found.isEmpty() ? new TranslatableComponent("peercraft.gui.multiplayer.nobody_found") : TextComponent.EMPTY;
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
                    discoverStatusMessage = new TranslatableComponent("peercraft.gui.common.account_server_timeout");
                    discoverStatusColor = PeerCraftUi.TEXT_ERROR;
                    searchButton.active = true;
                });
            }
        });
    }

    private void rebuildSearchResults() {
        for (AbstractWidget w : this.discoverResultWidgets) {
            peercraft$remove(w);
        }
        this.discoverResultWidgets.clear();

        int centerX = this.width / 2;
        int top = discoverResultsTop();
        boolean disc = this.currentTab == Tab.DISCOVER;
        this.discoverScroll = Math.max(0, Math.min(this.discoverScroll, discoverMaxScroll()));
        int shown = Math.min(discoverVisibleRows(), this.searchResults.size() - this.discoverScroll);
        for (int i = 0; i < shown; i++) {
            AccountClient.SearchResult result = this.searchResults.get(this.discoverScroll + i);
            Button addButton = Btn.builder(new TranslatableComponent("peercraft.gui.multiplayer.add_friend"), b -> onAddFriend(result))
                    .bounds(centerX + 30, top + i * ROW_HEIGHT, 170, 18).build();
            addButton.visible = disc;
            this.addButton(addButton);
            this.discoverResultWidgets.add(addButton);
        }
    }

    private void onAddFriend(AccountClient.SearchResult result) {
        sendFriendRequest(result.accountId(), result.displayName(), ok -> {
            if (ok) {
                discoverStatusMessage = new TranslatableComponent("peercraft.gui.multiplayer.request_sent", result.displayName());
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
                        friendsStatusMessage = new TextComponent(reason);
                        friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                        discoverStatusMessage = new TextComponent(reason);
                        discoverStatusColor = PeerCraftUi.TEXT_ERROR;
                        onDone.handle(false);
                    }
                });
            }
        });
    }

    // ==================== GAMES (public game browser) TAB ====================

    private void buildGamesTab() {
        int centerX = this.width / 2;
        int top = CONTENT_TOP + 4;

        this.gameSearchBox = new EditBox(this.font, centerX - 200, top, 190, 20, new TranslatableComponent("peercraft.gui.multiplayer.game_search_field"));
        this.gameSearchBox.setMaxLength(32);
        // This box drives live filtering, so it needs its own responder — fold the placeholder
        // clear/restore into it rather than using PeerCraftUi.placeholder (which would replace it).
        String gameSearchHint = new TranslatableComponent("peercraft.gui.multiplayer.game_search_hint").getString();
        this.gameSearchBox.setSuggestion(gameSearchHint);
        this.gameSearchBox.setResponder(value -> {
            this.gameSearchBox.setSuggestion(value.isEmpty() ? gameSearchHint : "");
            rebuildGameRows();
        });
        this.gamesStaticWidgets.add(this.addButton(this.gameSearchBox));

        rebuildVersionFilterButton();

        rebuildGameRows();
        if (this.games == null) {
            loadGames();
        }
    }

    /** (Re)creates the version-filter stepper for the versions currently present in {@code games}. */
    private void rebuildVersionFilterButton() {
        List<String> versions = new ArrayList<>();
        versions.add(""); // "All" sentinel, always first
        if (this.games != null) {
            List<String> seen = new ArrayList<>();
            for (AccountClient.PublicGameInfo g : this.games) {
                String v = g.mcVersion();
                if (!blank(v) && !seen.contains(v)) {
                    seen.add(v);
                }
            }
            Collections.sort(seen);
            versions.addAll(seen);
        }
        if (!versions.contains(this.selectedVersionFilter)) {
            this.selectedVersionFilter = "";
        }
        this.versionFilterValues = versions;

        if (this.gameVersionFilterButton != null) {
            peercraft$remove(this.gameVersionFilterButton);
            this.gamesStaticWidgets.remove(this.gameVersionFilterButton);
        }

        int centerX = this.width / 2;
        int top = CONTENT_TOP + 4;
        this.gameVersionFilterButton = new Button(centerX + 10, top, 110, 20, versionFilterLabel(this.selectedVersionFilter), b -> {
            int idx = this.versionFilterValues.indexOf(this.selectedVersionFilter);
            idx = (idx + 1) % this.versionFilterValues.size();
            this.selectedVersionFilter = this.versionFilterValues.get(idx);
            b.setMessage(versionFilterLabel(this.selectedVersionFilter));
            rebuildGameRows();
        });
        this.gameVersionFilterButton.visible = this.currentTab == Tab.GAMES;
        this.gamesStaticWidgets.add(this.addButton(this.gameVersionFilterButton));
    }

    private static Component versionFilterLabel(String v) {
        Component name = v.isEmpty()
                ? new TranslatableComponent("peercraft.gui.multiplayer.game_version_filter_all")
                : new TextComponent(v);
        return new TranslatableComponent("peercraft.gui.multiplayer.game_version_filter_field").append(new TextComponent(": ")).append(name);
    }

    private void recomputeFilteredGames() {
        if (this.games == null) {
            this.filteredGames = Collections.emptyList();
            return;
        }
        String search = this.gameSearchBox == null ? "" : this.gameSearchBox.getValue().trim().toLowerCase(Locale.ROOT);
        List<AccountClient.PublicGameInfo> result = new ArrayList<>();
        for (AccountClient.PublicGameInfo game : this.games) {
            boolean matchesSearch = search.isEmpty() || game.worldName().toLowerCase(Locale.ROOT).contains(search);
            boolean matchesVersion = this.selectedVersionFilter.isEmpty() || game.mcVersion().equals(this.selectedVersionFilter);
            if (matchesSearch && matchesVersion) {
                result.add(game);
            }
        }
        this.filteredGames = result;
    }

    private void loadGames() {
        this.gamesStatusMessage = new TranslatableComponent("peercraft.gui.multiplayer.loading_games");
        this.gamesStatusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.listPublicGames(new AccountClient.PublicGameListCallback() {
            @Override
            public void onResult(List<AccountClient.PublicGameInfo> result) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    games = result;
                    gamesStatusMessage = TextComponent.EMPTY;
                    tabGamesButton.setMessage(gamesTabLabel());
                    rebuildVersionFilterButton();
                    rebuildGameRows();
                });
            }

            @Override
            public void onTimeout() {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        gamesStatusMessage = new TranslatableComponent("peercraft.gui.common.account_server_timeout");
                        gamesStatusColor = PeerCraftUi.TEXT_ERROR;
                    }
                });
            }
        });
    }

    private void rebuildGameRows() {
        for (AbstractWidget w : this.gameRowWidgets) {
            peercraft$remove(w);
        }
        this.gameRowWidgets.clear();

        recomputeFilteredGames();
        if (this.games == null) {
            return;
        }

        int centerX = this.width / 2;
        int top = CONTENT_TOP + 32;
        int shown = Math.min(this.filteredGames.size(), rowsFitting(top, statusLineY() - 4));
        boolean gamesTab = this.currentTab == Tab.GAMES;
        for (int i = 0; i < shown; i++) {
            AccountClient.PublicGameInfo game = this.filteredGames.get(i);
            int rowY = top + i * ROW_HEIGHT;

            Button joinButton = Btn.builder(new TranslatableComponent("peercraft.gui.multiplayer.connect"), b -> onJoinGame(game))
                    .bounds(centerX + 20, rowY, 110, 18).build();
            joinButton.visible = gamesTab;
            this.addButton(joinButton);
            this.gameRowWidgets.add(joinButton);
        }

        if (this.filteredGames.size() > shown) {
            this.gamesStatusMessage = new TranslatableComponent("peercraft.gui.common.shown_first", shown, this.filteredGames.size());
            this.gamesStatusColor = PeerCraftUi.TEXT_MUTED;
        } else if (this.games.isEmpty()) {
            this.gamesStatusMessage = new TranslatableComponent("peercraft.gui.multiplayer.no_public_games");
            this.gamesStatusColor = PeerCraftUi.TEXT_MUTED;
        } else if (this.filteredGames.isEmpty()) {
            this.gamesStatusMessage = new TranslatableComponent("peercraft.gui.multiplayer.no_games_match_filter");
            this.gamesStatusColor = PeerCraftUi.TEXT_MUTED;
        } else {
            this.gamesStatusMessage = TextComponent.EMPTY;
        }
    }

    private void onJoinGame(AccountClient.PublicGameInfo game) {
        String label = blank(game.worldName()) ? game.code() : game.worldName();
        this.gamesStatusMessage = new TranslatableComponent("peercraft.gui.multiplayer.joining_game", label);
        this.gamesStatusColor = PeerCraftUi.TEXT_MUTED;
        P2PBridge.INSTANCE.startClientViaRendezvous(game.code(), PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(),
                new P2PBridge.ConnectListener() {
                    @Override
                    public void onStatus(String message) {
                        runOnClientThread(() -> {
                            if (stillOnThisScreen()) {
                                gamesStatusMessage = new TranslatableComponent(message);
                                gamesStatusColor = PeerCraftUi.TEXT_MUTED;
                            }
                        });
                    }

                    @Override
                    public void onConnected() {
                        runOnClientThread(() -> {
                            if (!stillOnThisScreen()) {
                                return;
                            }
                            startVanillaConnect();
                        });
                    }

                    @Override
                    public void onFailed(String reason) {
                        runOnClientThread(() -> {
                            if (stillOnThisScreen()) {
                                gamesStatusMessage = new TranslatableComponent(reason);
                                gamesStatusColor = PeerCraftUi.TEXT_ERROR;
                            }
                        });
                    }
                }, new ClientModSyncAgent(this, game.code()));
    }

    // ==================== shared plumbing ====================

    @Override
    public void tick() {
        super.tick();
        if (this.friends == null && this.games == null) {
            return;
        }
        if (++this.ticksSincePoll < POLL_INTERVAL_TICKS) {
            return;
        }
        this.ticksSincePoll = 0;
        if (this.friends != null) {
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
                    // silent — keep showing the last known list
                }
            });
        }
        if (this.games != null) {
            AccountClient.INSTANCE.listPublicGames(new AccountClient.PublicGameListCallback() {
                @Override
                public void onResult(List<AccountClient.PublicGameInfo> result) {
                    runOnClientThread(() -> {
                        if (stillOnThisScreen()) {
                            games = result;
                            tabGamesButton.setMessage(gamesTabLabel());
                            rebuildGameRows();
                        }
                    });
                }

                @Override
                public void onTimeout() {
                    // silent — keep showing the last known list
                }
            });
        }
    }

    private void peercraft$remove(AbstractWidget widget) {
        this.buttons.remove(widget);
        this.children.remove(widget);
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void runOnClientThread(Runnable action) {
        Minecraft.getInstance().execute(action);
    }

    private boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    /** Plain vanilla-style scrollbar (black track + grey thumb) down the right edge of the Find Players results, only when they overflow. */
    private void drawDiscoverScrollbar(PoseStack poseStack, int centerX) {
        int total = this.searchResults.size();
        int visible = discoverVisibleRows();
        if (visible <= 0 || total <= visible) {
            return;
        }
        int trackTop = discoverResultsTop();
        int trackHeight = visible * ROW_HEIGHT;
        int left = centerX + 202;
        int right = left + 6;
        GuiComponent.fill(poseStack, left, trackTop, right, trackTop + trackHeight, 0xFF000000);
        int maxScroll = total - visible;
        int scroll = Math.max(0, Math.min(this.discoverScroll, maxScroll));
        int thumbHeight = Math.max(16, trackHeight * visible / total);
        int thumbY = trackTop + (trackHeight - thumbHeight) * scroll / maxScroll;
        GuiComponent.fill(poseStack, left, thumbY, right, thumbY + thumbHeight, 0xFFA0A0A0);
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        super.render(poseStack, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;

        if (this.currentTab == Tab.FRIENDS) {
            int top = CONTENT_TOP + 4;
            int shown = this.friends == null ? 0 : Math.min(this.friends.size(), rowsFitting(top, friendsInputTop() + 4));
            for (int i = 0; i < shown; i++) {
                AccountClient.FriendInfo friend = this.friends.get(i);
                int rowY = top + i * ROW_HEIGHT + 5;
                int afterBadgeX = PeerCraftUi.drawNameWithBadge(poseStack, this.font, friend.displayName(), friend.licensed(), centerX - 200, rowY, PeerCraftUi.TEXT_TITLE);

                String status;
                int statusColor;
                switch (friend.status()) {
                    case AccountProtocol.STATUS_HOSTING:
                        status = new TranslatableComponent("peercraft.gui.multiplayer.status_hosting", friend.roomCode()).getString();
                        statusColor = PeerCraftUi.TEXT_ACCENT;
                        break;
                    case AccountProtocol.STATUS_ONLINE:
                        status = new TranslatableComponent("peercraft.gui.multiplayer.status_online").getString();
                        statusColor = PeerCraftUi.TEXT_SUCCESS;
                        break;
                    default:
                        status = new TranslatableComponent("peercraft.gui.multiplayer.status_offline").getString();
                        statusColor = PeerCraftUi.TEXT_MUTED;
                        break;
                }
                GuiComponent.drawString(poseStack, this.font, " — " + status, afterBadgeX, rowY, statusColor);
            }
            GuiComponent.drawCenteredString(poseStack, this.font, this.friendsStatusMessage, centerX, statusLineY(), this.friendsStatusColor);
        } else if (this.currentTab == Tab.DISCOVER) {
            int top = discoverResultsTop();
            int shown = Math.min(discoverVisibleRows(), this.searchResults.size() - this.discoverScroll);
            for (int i = 0; i < shown; i++) {
                AccountClient.SearchResult result = this.searchResults.get(this.discoverScroll + i);
                PeerCraftUi.drawNameWithBadge(poseStack, this.font, result.displayName(), result.licensed(), centerX - 200, top + i * ROW_HEIGHT + 5, PeerCraftUi.TEXT_TITLE);
            }
            drawDiscoverScrollbar(poseStack, centerX);
            GuiComponent.drawCenteredString(poseStack, this.font, this.discoverStatusMessage, centerX, statusLineY(), this.discoverStatusColor);
        } else if (this.currentTab == Tab.GAMES) {
            int top = CONTENT_TOP + 32;
            int shown = Math.min(this.filteredGames.size(), rowsFitting(top, statusLineY() - 4));
            for (int i = 0; i < shown; i++) {
                AccountClient.PublicGameInfo game = this.filteredGames.get(i);
                int rowY = top + i * ROW_HEIGHT + 5;
                String hostName = blank(game.hostDisplayName())
                        ? new TranslatableComponent("peercraft.gui.multiplayer.anonymous_host").getString()
                        : game.hostDisplayName();
                String worldName = blank(game.worldName()) ? game.code() : game.worldName();
                String versionSuffix = blank(game.mcVersion()) ? "" : " [" + game.mcVersion() + "]";
                String line = worldName + " — " + hostName + " (" + game.currentPlayerCount() + "/" + game.maxPlayers() + ")" + versionSuffix;
                GuiComponent.drawString(poseStack, this.font, line, centerX - 200, rowY, PeerCraftUi.TEXT_TITLE);
            }
            GuiComponent.drawCenteredString(poseStack, this.font, this.gamesStatusMessage, centerX, statusLineY(), this.gamesStatusColor);
        }
    }
}
