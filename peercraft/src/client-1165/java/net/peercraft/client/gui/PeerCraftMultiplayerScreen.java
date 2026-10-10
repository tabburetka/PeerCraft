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

    private int contentTop;
    private SteampunkDialog panel;
    private int footerTop;
    private int friendScroll, gameScroll;
    private String gameFilterSnapshot = "";
    private final long openedAt = System.currentTimeMillis();
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
    private String savedFriendCode = "", savedPlayerSearch = "", savedGameSearch = "";
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

    private void refreshScreen() {
        PeerCraftMultiplayerScreen next = new PeerCraftMultiplayerScreen(lastScreen, currentTab);
        next.savedFriendCode = addByCodeBox.getValue();
        next.savedPlayerSearch = searchQueryBox.getValue();
        next.savedGameSearch = gameSearchBox.getValue();
        next.selectedVersionFilter = selectedVersionFilter;
        PeerCraftUi.setScreen(minecraft, next);
        if (currentTab == Tab.DISCOVER && !next.savedPlayerSearch.trim().isEmpty()) next.onSearch();
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
        String friendCode = addByCodeBox == null ? savedFriendCode : addByCodeBox.getValue();
        String playerQuery = searchQueryBox == null ? savedPlayerSearch : searchQueryBox.getValue();
        String gameQuery = gameSearchBox == null ? savedGameSearch : gameSearchBox.getValue();
        panel = new SteampunkDialog(width, height, height - 16, title, 460);
        contentTop = panel.top + 54;
        footerTop = panel.top + panel.height - 52;
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
        addByCodeBox.setValue(friendCode);
        searchQueryBox.setValue(playerQuery);
        gameSearchBox.setValue(gameQuery);
        applyTabVisibility();
    }

    private Button feedbackGlyph, donateGlyph;
    private int footerLeft() { return panel.contentX() + 24; }
    private int footerWidth() { return panel.contentWidth() - 24; }
    private void peercraft$addFooterButtons() {
        feedbackGlyph = addButton(PeerCraftUi.squareGlyphButton(panel.contentX(), footerTop, 20, "!", "", b -> openLink(FEEDBACK_MAILTO)));
        donateGlyph = addButton(PeerCraftUi.squareGlyphButton(panel.contentX(), footerTop + 24, 20, "$", "", b -> openLink(DONATE_URL)));
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

        layoutFavoritesList(contentTop);
        List<AbstractWidget> originals = new ArrayList<>(favoritesWidgets);
        favoritesWidgets.clear();
        int actionWidth = (footerWidth() - 32) / 3;
        for (int i = 0; i < 5; i++) {
            Button original = (Button) originals.get(i);
            peercraft$remove(original);
            int w = i < 3 ? actionWidth : (footerWidth() - 60) / 4;
            int x = footerLeft() + (i < 3 ? i * (w + 4) : (i - 3) * (w + 4));
            int y = footerTop + (i < 3 ? 0 : 24);
            favoritesWidgets.add(addButton(new SteampunkDecoratedButton(original, x, y, w, 20)));
        }
        peercraft$remove(vanillaRefresh); peercraft$remove(vanillaBack);
        int bottomWidth = (footerWidth() - 60) / 4;
        customRefreshButton = addButton(Btn.builder(vanillaRefresh.getMessage(),
                        b -> refreshScreen())
                .bounds(footerLeft() + (bottomWidth + 4) * 2, footerTop + 24, bottomWidth, 20).build());
        addButton(new SteampunkDecoratedButton(vanillaBack, footerLeft() + (bottomWidth + 4) * 3, footerTop + 24, bottomWidth, 20));
        int glyphX = footerLeft() + (bottomWidth + 4) * 4;
        addButton(PeerCraftUi.squareGlyphButton(glyphX, footerTop + 24, 20, "☺", "", b -> PeerCraftUi.setScreen(minecraft, new PeerCraftAccountScreen(this))));
        addButton(PeerCraftUi.squareGlyphButton(glyphX + 24, footerTop + 24, 20, "⚙", "", b -> PeerCraftUi.setScreen(minecraft, new PeerCraftSettingsScreen(this))));
        if (!PeerCraftConfig.MODE_HOST.equals(PeerCraftConfig.mode())) addButton(PeerCraftUi.squareGlyphButton(
                footerLeft() + (actionWidth + 4) * 3, footerTop, 20, "▶", "", b -> PeerCraftUi.setScreen(minecraft, new PeerCraftJoinScreen(this))));
    }

    /** Off-screen {@code top} hides the list without letting it eat clicks (see the src/main field comment). */
    private void layoutFavoritesList(int top) {
        int bottom = top < 0 ? top + statusLineY() - 4 - contentTop : statusLineY() - 4;
        serverSelectionList.updateSize(panel.width - 24, height, top, bottom);
        serverSelectionList.setLeftPos(panel.contentX());
        serverSelectionList.setRenderBackground(false);
        serverSelectionList.setRenderTopAndBottom(false);
    }

    private void buildTabBar() {
        int barWidth = panel.width;
        int tabWidth = (barWidth - 12) / 4;
        int startX = this.width / 2 - barWidth / 2;
        int y = panel.top + 24;

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

    /** Shared top row for adding a friend; the scrollable list follows the request button. */
    private int friendsInputTop() { return contentTop; }
    private int friendsResultsTop() { return contentTop + 52; }

    /** ROW_HEIGHT rows that fit in [rowsTop, rowsBottom], using all available list space. */
    private int rowsFitting(int rowsTop, int rowsBottom) {
        return Math.max(0, (rowsBottom - rowsTop) / ROW_HEIGHT);
    }

    /** Y of the per-tab status line, just above the persistent footer row. */
    private int statusLineY() {
        return footerTop - 12;
    }

    // ---- discover (find players) result scrolling (kept in step with src/main) ------------

    private int discoverResultsTop() {
        return contentTop + 32;
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
        if (mouseX >= panel.contentX() && mouseX < panel.left + panel.width && delta != 0) {
            if (currentTab == Tab.FRIENDS && friends != null && mouseY >= friendsResultsTop() && mouseY < statusLineY()) {
                int max = Math.max(0, friends.size() - rowsFitting(friendsResultsTop(), statusLineY() - 4));
                friendScroll = Math.max(0, Math.min(max, friendScroll - (int) Math.signum(delta))); rebuildFriendRows(); return true;
            }
            if (currentTab == Tab.GAMES && mouseY >= contentTop + 32 && mouseY < statusLineY()) {
                int max = Math.max(0, filteredGames.size() - rowsFitting(contentTop + 32, statusLineY() - 4));
                gameScroll = Math.max(0, Math.min(max, gameScroll - (int) Math.signum(delta))); rebuildGameRows(); return true;
            }
        }
        if (mouseX >= panel.contentX() && mouseX < panel.left + panel.width
                && this.currentTab == Tab.DISCOVER && delta != 0 && discoverMaxScroll() > 0 && overDiscoverResults(mouseY)) {
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

        layoutFavoritesList(fav ? contentTop : -30000);
        if (getFocused() instanceof AbstractWidget && !((AbstractWidget) getFocused()).visible) setFocused(null);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP || keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            int direction = keyCode == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1;
            if (currentTab == Tab.FRIENDS && friends != null) {
                int visible = rowsFitting(friendsResultsTop(), statusLineY() - 4);
                friendScroll = Math.max(0, Math.min(Math.max(0, friends.size() - visible), friendScroll + direction * Math.max(1, visible)));
                rebuildFriendRows(); return true;
            }
            if (currentTab == Tab.GAMES) {
                int visible = rowsFitting(contentTop + 32, statusLineY() - 4);
                gameScroll = Math.max(0, Math.min(Math.max(0, filteredGames.size() - visible), gameScroll + direction * Math.max(1, visible)));
                rebuildGameRows(); return true;
            }
            if (currentTab == Tab.DISCOVER) {
                setDiscoverScroll(discoverScroll + direction * Math.max(1, discoverVisibleRows())); return true;
            }
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

        this.addByCodeBox = new SteampunkField(this.font, panel.contentX(), rowsBottom + 4, (panel.contentWidth() - 6) / 2, 20, new TranslatableComponent("peercraft.gui.multiplayer.add_by_code_field"));
        this.addByCodeBox.setMaxLength(6);
        PeerCraftUi.placeholder(this.addByCodeBox, new TranslatableComponent("peercraft.gui.multiplayer.add_by_code_field").getString());
        this.friendsStaticWidgets.add(this.addButton(this.addByCodeBox));

        this.addByCodeButton = this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.multiplayer.add_by_code_button"), b -> onAddByCode())
                .bounds(panel.contentX() + (panel.contentWidth() - 6) / 2 + 6, rowsBottom + 4, (panel.contentWidth() - 6) / 2, 20).build());
        this.friendsStaticWidgets.add(this.addByCodeButton);

        this.friendsStaticWidgets.add(this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.multiplayer.friend_requests_button"), b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftFriendRequestsScreen(this)))
                .bounds(panel.contentX(), rowsBottom + 28, panel.contentWidth(), 20).build()));

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
        int top = friendsResultsTop();
        int capacity = rowsFitting(top, statusLineY() - 4);
        friendScroll = Math.max(0, Math.min(friendScroll, friends.size() - capacity));
        int shown = Math.min(this.friends.size() - friendScroll, capacity);
        boolean fr = this.currentTab == Tab.FRIENDS;
        for (int i = 0; i < shown; i++) {
            AccountClient.FriendInfo friend = this.friends.get(i + friendScroll);
            int rowY = top + i * ROW_HEIGHT;

            boolean canConnect = friend.status() == AccountProtocol.STATUS_HOSTING;
            Button connectButton = Btn.builder(new TranslatableComponent("peercraft.gui.multiplayer.connect"), b -> onConnectToFriend(friend))
                    .bounds(panel.contentX() + panel.contentWidth() - 136, rowY, 78, 18).build();
            connectButton.active = canConnect;
            connectButton.visible = fr;
            this.addButton(connectButton);
            this.friendRowWidgets.add(connectButton);

            Button removeButton = Btn.builder(new TranslatableComponent("peercraft.gui.multiplayer.remove"), b -> confirmRemoveFriend(friend))
                    .bounds(panel.contentX() + panel.contentWidth() - 54, rowY, 54, 18).build();
            removeButton.visible = fr;
            this.addButton(removeButton);
            this.friendRowWidgets.add(removeButton);
        }

        if (this.friends.size() > shown) {
            this.friendsStatusMessage = TextComponent.EMPTY;
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
        PeerCraftUi.setScreen(this.minecraft, new PeerCraftConfirmScreen(confirmed -> {
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
        if (PeerCraftProgressNoticeScreen.beforeConnecting(this, () -> onConnectToFriend(friend))) return;
        this.friendsStatusMessage = new TranslatableComponent("peercraft.gui.multiplayer.connecting_to", friend.displayName());
        this.friendsStatusColor = PeerCraftUi.TEXT_MUTED;
        P2PBridge.INSTANCE.startClientViaRendezvous(friend.roomCode(), PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(),
                new P2PBridge.ConnectListener() {
                    private P2PBridge.ClientJoinAttempt attempt;
                    @Override public void onStarted(P2PBridge.ClientJoinAttempt started) {
                        attempt = started;
                        TransportNoticeController.trackJoin(started, PeerCraftMultiplayerScreen.this, lastScreen);
                    }
                    private void dispatch(Runnable action) {
                        runOnClientThread(() -> {
                            if (attempt == null || attempt.isCurrent()) action.run();
                        });
                    }
                    @Override
                    public void onStatus(String message) {
                        dispatch(() -> {
                            if (stillOnThisScreen()) {
                                friendsStatusMessage = new TranslatableComponent(message);
                                friendsStatusColor = PeerCraftUi.TEXT_MUTED;
                            }
                        });
                    }

                    @Override
                    public void onConnected() {
                        dispatch(() -> {
                            if (!stillOnThisScreen()) {
                                return;
                            }
                            startVanillaConnect();
                        });
                    }

                    @Override
                    public void onFailed(String reason) {
                        dispatch(() -> {
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
        TransportNoticeController.connecting();
        int port = P2PBridge.INSTANCE.getProxyPort();
        ServerData serverData = new ServerData("PeerCraft", "127.0.0.1:" + port, false);
        this.minecraft.setScreen(new ConnectScreen(this.lastScreen, this.minecraft, serverData));
    }

    // ==================== DISCOVER (FIND PLAYERS) TAB ====================

    private void buildDiscoverTab() {
        int centerX = this.width / 2;
        int top = contentTop + 4;

        this.searchQueryBox = new SteampunkField(this.font, panel.contentX(), top, panel.contentWidth() - 96, 20, new TranslatableComponent("peercraft.gui.multiplayer.search_query_field"));
        this.searchQueryBox.setMaxLength(16);
        PeerCraftUi.placeholder(this.searchQueryBox, new TranslatableComponent("peercraft.gui.multiplayer.search_query_hint").getString());
        this.discoverStaticWidgets.add(this.addButton(this.searchQueryBox));

        this.searchButton = this.addButton(Btn.builder(new TranslatableComponent("peercraft.gui.multiplayer.search_button"), b -> onSearch())
                .bounds(panel.contentX() + panel.contentWidth() - 90, top, 90, 20).build());
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
                    .bounds(panel.contentX() + panel.contentWidth() - 110, top + i * ROW_HEIGHT, 110, 18).build();
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
        int top = contentTop + 4;

        this.gameSearchBox = new SteampunkField(this.font, panel.contentX(), top, panel.contentWidth() - 126, 20, new TranslatableComponent("peercraft.gui.multiplayer.game_search_field"));
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
        if (this.games == null && !versions.contains(this.selectedVersionFilter)) {
            versions.add(this.selectedVersionFilter);
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
        int top = contentTop + 4;
        this.gameVersionFilterButton = new SteampunkButton(panel.contentX() + panel.contentWidth() - 120, top, 120, 20, versionFilterLabel(this.selectedVersionFilter), b -> {
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
        return name;
    }

    private void recomputeFilteredGames() {
        if (this.games == null) {
            this.filteredGames = Collections.emptyList();
            return;
        }
        String search = this.gameSearchBox == null ? savedGameSearch : this.gameSearchBox.getValue().trim().toLowerCase(Locale.ROOT);
        String snapshot = search + "\n" + this.selectedVersionFilter;
        if (!snapshot.equals(gameFilterSnapshot)) { gameScroll = 0; gameFilterSnapshot = snapshot; }
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
        int top = contentTop + 32;
        int capacity = rowsFitting(top, statusLineY() - 4);
        gameScroll = Math.max(0, Math.min(gameScroll, filteredGames.size() - capacity));
        int shown = Math.min(this.filteredGames.size() - gameScroll, capacity);
        boolean gamesTab = this.currentTab == Tab.GAMES;
        for (int i = 0; i < shown; i++) {
            AccountClient.PublicGameInfo game = this.filteredGames.get(i + gameScroll);
            int rowY = top + i * ROW_HEIGHT;

            Button joinButton = Btn.builder(new TranslatableComponent("peercraft.gui.multiplayer.connect"), b -> onJoinGame(game))
                    .bounds(panel.contentX() + panel.contentWidth() - 110, rowY, 110, 18).build();
            joinButton.visible = gamesTab;
            this.addButton(joinButton);
            this.gameRowWidgets.add(joinButton);
        }

        if (this.filteredGames.size() > shown) {
            this.gamesStatusMessage = TextComponent.EMPTY;
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
        if (PeerCraftProgressNoticeScreen.beforeConnecting(this, () -> onJoinGame(game))) return;
        String label = blank(game.worldName()) ? game.code() : game.worldName();
        this.gamesStatusMessage = new TranslatableComponent("peercraft.gui.multiplayer.joining_game", label);
        this.gamesStatusColor = PeerCraftUi.TEXT_MUTED;
        P2PBridge.INSTANCE.startClientViaRendezvous(game.code(), PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(),
                new P2PBridge.ConnectListener() {
                    private P2PBridge.ClientJoinAttempt attempt;
                    @Override public void onStarted(P2PBridge.ClientJoinAttempt started) {
                        attempt = started;
                        TransportNoticeController.trackJoin(started, PeerCraftMultiplayerScreen.this, lastScreen);
                    }
                    private void dispatch(Runnable action) {
                        runOnClientThread(() -> {
                            if (attempt == null || attempt.isCurrent()) action.run();
                        });
                    }
                    @Override
                    public void onStatus(String message) {
                        dispatch(() -> {
                            if (stillOnThisScreen()) {
                                gamesStatusMessage = new TranslatableComponent(message);
                                gamesStatusColor = PeerCraftUi.TEXT_MUTED;
                            }
                        });
                    }

                    @Override
                    public void onConnected() {
                        dispatch(() -> {
                            if (!stillOnThisScreen()) {
                                return;
                            }
                            startVanillaConnect();
                        });
                    }

                    @Override
                    public void onFailed(String reason) {
                        dispatch(() -> {
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
        int left = panel.left + panel.width - 7;
        int right = left + 2;
        GuiComponent.fill(poseStack, left, trackTop, right, trackTop + trackHeight, 0xFF49331F);
        int maxScroll = total - visible;
        int scroll = Math.max(0, Math.min(this.discoverScroll, maxScroll));
        int thumbHeight = Math.max(16, trackHeight * visible / total);
        int thumbY = trackTop + (trackHeight - thumbHeight) * scroll / maxScroll;
        GuiComponent.fill(poseStack, left, thumbY, right, thumbY + thumbHeight, PeerCraftUi.TEXT_ACCENT);
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        super.render(poseStack, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        if (currentTab == Tab.FAVORITES) drawFavoritesScrollbar(poseStack);

        if (this.currentTab == Tab.FRIENDS) {
            int top = friendsResultsTop();
            int shown = this.friends == null ? 0 : Math.min(this.friends.size() - friendScroll, rowsFitting(top, statusLineY() - 4));
            for (int i = 0; i < shown; i++) {
                AccountClient.FriendInfo friend = this.friends.get(i + friendScroll);
                int rowY = top + i * ROW_HEIGHT + 5;
                int afterBadgeX = PeerCraftUi.drawNameWithBadge(poseStack, this.font, font.plainSubstrByWidth(friend.displayName(), Math.max(1, panel.contentWidth() - 156)), friend.licensed(), panel.contentX(), rowY, PeerCraftUi.TEXT_TITLE);

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
                GuiComponent.drawString(poseStack, this.font, font.plainSubstrByWidth(" — " + status, Math.max(0, panel.contentX() + panel.contentWidth() - 140 - afterBadgeX)), afterBadgeX, rowY, statusColor);
            }
            drawTabScrollbar(poseStack, friendsResultsTop(), friends == null ? 0 : friends.size(), friendScroll);
            drawTabStatus(poseStack, friendsStatusMessage, friendsStatusColor, mouseX, mouseY);
        } else if (this.currentTab == Tab.DISCOVER) {
            int top = discoverResultsTop();
            int shown = Math.min(discoverVisibleRows(), this.searchResults.size() - this.discoverScroll);
            for (int i = 0; i < shown; i++) {
                AccountClient.SearchResult result = this.searchResults.get(this.discoverScroll + i);
                PeerCraftUi.drawNameWithBadge(poseStack, this.font, font.plainSubstrByWidth(result.displayName(), panel.contentWidth() - 132), result.licensed(), panel.contentX(), top + i * ROW_HEIGHT + 5, PeerCraftUi.TEXT_TITLE);
            }
            drawDiscoverScrollbar(poseStack, centerX);
            drawTabStatus(poseStack, discoverStatusMessage, discoverStatusColor, mouseX, mouseY);
        } else if (this.currentTab == Tab.GAMES) {
            int top = contentTop + 32;
            int capacity = rowsFitting(top, statusLineY() - 4);
            int shown = Math.min(this.filteredGames.size() - gameScroll, capacity);
            for (int i = 0; i < shown; i++) {
                AccountClient.PublicGameInfo game = this.filteredGames.get(i + gameScroll);
                int rowY = top + i * ROW_HEIGHT + 5;
                String hostName = blank(game.hostDisplayName())
                        ? new TranslatableComponent("peercraft.gui.multiplayer.anonymous_host").getString()
                        : game.hostDisplayName();
                String worldName = blank(game.worldName()) ? game.code() : game.worldName();
                String versionSuffix = blank(game.mcVersion()) ? "" : " [" + game.mcVersion() + "]";
                String line = worldName + " — " + hostName + " (" + game.currentPlayerCount() + "/" + game.maxPlayers() + ")" + versionSuffix;
                GuiComponent.drawString(poseStack, this.font, font.plainSubstrByWidth(line, panel.contentWidth() - 116), panel.contentX(), rowY, PeerCraftUi.TEXT_TITLE);
            }
            drawTabScrollbar(poseStack, contentTop + 32, filteredGames.size(), gameScroll);
            drawTabStatus(poseStack, gamesStatusMessage, gamesStatusColor, mouseX, mouseY);
        }
            for (Button glyph : new Button[]{feedbackGlyph, donateGlyph}) {
            if (glyph != null && glyph.visible && glyph.isMouseOver(mouseX, mouseY))
                renderTooltip(poseStack, new TranslatableComponent(glyph == feedbackGlyph
                        ? "peercraft.gui.title.feedback_button" : "peercraft.gui.title.donate_button"), mouseX, mouseY);
        }
}
    public int peercraft$listRowWidth() { return Math.max(1, Math.min(305, panel.contentWidth() - 20)); }
    public int peercraft$listScrollbarX() { return panel.left + panel.width - 10; }
    @Override public void renderBackground(PoseStack pose) {
        GuiComponent.fill(pose, 0, 0, width, height, net.peercraft.client.theme.SteampunkPalette.BACKGROUND);
        long elapsed = System.currentTimeMillis() - openedAt;
        for (int i = 0; i < 28; i++) {
            if (panel.left < 10) break;
            int x = 4 + (int) ((i * 0.754877666 % 1.0) * (panel.left - 8));
            if ((i & 1) != 0) x = width - x;
            int y = (int) ((1 - (i * 0.61803398875 + elapsed / (24000.0 + (i % 5) * 3000)) % 1.0) * height);
            GuiComponent.fill(pose, x, y, x + 1, y + 1, 0x997C592A);
        }
        SteampunkDialog.frame(pose, panel.left, panel.top, panel.width, panel.height, net.peercraft.client.theme.SteampunkPalette.PANEL, net.peercraft.client.theme.SteampunkPalette.BORDER);
    }

    private void drawTabStatus(PoseStack pose, Component message, int color, int mouseX, int mouseY) {
        String full = message.getString();
        GuiComponent.drawCenteredString(pose, font, font.plainSubstrByWidth(full, panel.contentWidth()), width / 2, statusLineY(), color);
        if (font.width(full) > panel.contentWidth() && mouseX >= panel.contentX() && mouseX < panel.contentX() + panel.contentWidth()
                && mouseY >= statusLineY() && mouseY < footerTop) renderTooltip(pose, message, mouseX, mouseY);
    }

    private void drawTabScrollbar(PoseStack pose, int top, int total, int offset) {
        int shown = rowsFitting(top, statusLineY() - 4);
        if (shown <= 0 || total <= shown) return;
        int track = shown * ROW_HEIGHT, thumb = Math.max(8, track * shown / total);
        int x = panel.left + panel.width - 7;
        int y = top + (track - thumb) * offset / (total - shown);
        GuiComponent.fill(pose, x, top, x + 2, top + track, 0xFF49331F);
        GuiComponent.fill(pose, x, y, x + 2, y + thumb, PeerCraftUi.TEXT_ACCENT);
    }
    private void drawFavoritesScrollbar(PoseStack pose) {
        int max = serverSelectionList.getMaxScroll();
        if (max <= 0) return;
        int top = contentTop, bottom = statusLineY() - 4, track = bottom - top;
        int thumb = Math.max(12, Math.min(track - 8, track * track / (track + max)));
        int y = top + (int) ((track - thumb) * serverSelectionList.getScrollAmount() / max);
        int x = peercraft$listScrollbarX();
        SteampunkDialog.frame(pose, x, top, 6, track, net.peercraft.client.theme.SteampunkPalette.CONTROL, 0xFF49331F);
        SteampunkDialog.frame(pose, x, y, 6, thumb, 0xFF604328, net.peercraft.client.theme.SteampunkPalette.BORDER);
    }

}
