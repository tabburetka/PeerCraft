package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.resources.I18n;
import net.peercraft.client.modsync.ClientModSyncAgent;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.account.AccountClient;
import net.peercraft.network.p2p.P2PBridge;
import net.peercraft.network.rendezvous.AccountProtocol;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Forge 1.12.2 backport of {@code src/main/.../PeerCraftMultiplayerScreen.java} (cf. the 1.16.5
 * twin in {@code src/client-1165}) — vanilla's {@link GuiMultiplayer} with a PeerCraft tab bar
 * (Favorites / Friends / Find Players / Games).
 *
 * <p>1.12.2 deltas beyond the shared mechanical swaps:
 * <ul>
 *   <li>{@code GuiMultiplayer}'s {@code serverListSelector} is {@code private} — instead of
 *       moving it off-screen, non-Favorites tabs simply don't call {@code super.drawScreen()}
 *       (which is what draws the list) and draw their own content; the vanilla list buttons
 *       are found by their fixed ids (1 select / 2 delete / 3 add / 4 direct / 7 edit /
 *       8 refresh / 0 back) in {@code this.buttonList} after {@code super.initGui()} and
 *       toggled per tab.</li>
 *   <li>Text fields are plain {@link GuiTextField}s wired by hand (not widgets in the button
 *       list); {@code setResponder} → re-filter in {@code keyTyped}.</li>
 *   <li>No {@code tick()} — polling runs from {@code updateScreen()}. No {@code CycleButton} —
 *       the version filter is an {@link IdButton} that cycles a list. {@code ConfirmScreen} →
 *       {@link GuiYesNo} routed through {@code confirmClicked(boolean,int)} (vanilla already
 *       overrides it for server-delete, so the remove-friend dialog uses a distinct id).</li>
 *   <li>Custom refresh keeps the current tab by intercepting vanilla button id 8.</li>
 * </ul>
 */
public class PeerCraftMultiplayerScreen extends GuiMultiplayer {

    private enum Tab { FAVORITES, FRIENDS, DISCOVER, GAMES }

    private static final int CONTENT_TOP = 58;
    private static final int MAX_ROWS_SHOWN = 6;
    private static final int ROW_HEIGHT = 20;

    private static final String FEEDBACK_MAILTO = "mailto:peercraft2@gmail.com?subject=PeerCraft%20feedback";
    private static final String DONATE_URL = "https://boosty.to/peercraft";
    private static final int FOOTER_BUTTON_WIDTH = 70;
    private static final int FOOTER_BUTTON_HEIGHT = 20;

    private static final int VANILLA_SELECT = 1, VANILLA_DELETE = 2, VANILLA_ADD = 3,
            VANILLA_DIRECT = 4, VANILLA_EDIT = 7, VANILLA_REFRESH = 8, VANILLA_BACK = 0;
    private static final int DIALOG_REMOVE_FRIEND = 424242;
    private static final int POLL_INTERVAL_TICKS = 100;

    private final GuiScreen lastScreen;
    private Tab currentTab;

    private GuiButton tabFavoritesButton, tabFriendsButton, tabDiscoverButton, tabGamesButton;
    private GuiButton vSelect, vDelete, vAdd, vDirect, vEdit, vRefresh, vBack;

    /**
     * Vanilla's {@code GuiMultiplayer.serverListSelector} (the server list) is {@code private}
     * and hit-tests clicks straight from mouse coordinates in {@code mouseClicked} /
     * {@code mouseReleased} / {@code handleMouseInput} — whether it was drawn this frame or not
     * is irrelevant. Off the Favorites tab we skip drawing it, but that alone left hidden
     * favorite rows clickable (they'd still select/join). Grabbed once by reflection so
     * {@link #applyTabVisibility()} can shove it off-screen ({@code top == bottom} ⇒
     * {@code isMouseYWithinSlotBounds} always false) on every non-Favorites tab and restore
     * vanilla bounds on Favorites — the same fix the modern {@code src/main} /
     * {@code src/client-1165} screens apply to their (accessible) list field. Null only if the
     * field name ever stops resolving, in which case the old (buggy) behaviour is kept rather
     * than crashing the screen.
     */
    private GuiSlot serverListRef;

    // ---- friends tab ----
    private final List<GuiButton> friendsStaticWidgets = new ArrayList<GuiButton>();
    private final List<GuiButton> friendRowWidgets = new ArrayList<GuiButton>();
    private List<AccountClient.FriendInfo> friends;
    private GuiTextField addByCodeBox;
    private GuiButton addByCodeButton;
    private String friendsStatusMessage = "";
    private int friendsStatusColor = PeerCraftUi.TEXT_MUTED;
    private int ticksSincePoll;
    private AccountClient.FriendInfo pendingRemoveFriend;

    // ---- discover tab ----
    private final List<GuiButton> discoverStaticWidgets = new ArrayList<GuiButton>();
    private final List<GuiButton> discoverResultWidgets = new ArrayList<GuiButton>();
    private List<AccountClient.SearchResult> searchResults = Collections.emptyList();
    private GuiTextField searchQueryBox;
    private GuiButton searchButton;
    private String discoverStatusMessage = "";
    private int discoverStatusColor = PeerCraftUi.TEXT_MUTED;
    // First visible search-result row — the mouse wheel (handleMouseInput) walks this offset
    // when the result list overflows, with a scrollbar down the right edge showing the spot.
    private int discoverScroll;

    // ---- games tab ----
    private final List<GuiButton> gamesStaticWidgets = new ArrayList<GuiButton>();
    private final List<GuiButton> gameRowWidgets = new ArrayList<GuiButton>();
    private List<AccountClient.PublicGameInfo> games;
    private List<AccountClient.PublicGameInfo> filteredGames = Collections.emptyList();
    private GuiTextField gameSearchBox;
    private IdButton gameVersionFilterButton;
    private List<String> versionFilterValues = new ArrayList<String>();
    private String selectedVersionFilter = "";
    private String gamesStatusMessage = "";
    private int gamesStatusColor = PeerCraftUi.TEXT_MUTED;

    public PeerCraftMultiplayerScreen(GuiScreen lastScreen) {
        this(lastScreen, Tab.FAVORITES);
    }

    private PeerCraftMultiplayerScreen(GuiScreen lastScreen, Tab initialTab) {
        super(lastScreen);
        this.lastScreen = lastScreen;
        this.currentTab = initialTab;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        this.friendsStaticWidgets.clear();
        this.friendRowWidgets.clear();
        this.discoverStaticWidgets.clear();
        this.discoverResultWidgets.clear();
        this.gamesStaticWidgets.clear();
        this.gameRowWidgets.clear();

        super.initGui(); // builds the vanilla server-list buttons into this.buttonList

        this.vSelect = findVanilla(VANILLA_SELECT);
        this.vDelete = findVanilla(VANILLA_DELETE);
        this.vAdd = findVanilla(VANILLA_ADD);
        this.vDirect = findVanilla(VANILLA_DIRECT);
        this.vEdit = findVanilla(VANILLA_EDIT);
        this.vRefresh = findVanilla(VANILLA_REFRESH);
        this.vBack = findVanilla(VANILLA_BACK);
        this.serverListRef = findServerList();

        addFooterButtons();
        addFavoritesGlyphButtons();
        buildTabBar();
        buildFriendsTab();
        buildDiscoverTab();
        buildGamesTab();
        applyTabVisibility();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    private GuiButton findVanilla(int id) {
        for (GuiButton b : this.buttonList) {
            if (b.id == id) {
                return b;
            }
        }
        return null;
    }

    /**
     * The server list is {@code GuiMultiplayer.serverListSelector} — {@code private}, no getter.
     * The dev runtime (MCP stable_39) names it {@code serverListSelector}; the reobf jar
     * shipped to players (SRG) names it {@code field_146803_h}. Try both. Failure is non-fatal
     * (see {@link #serverListRef}).
     */
    private GuiSlot findServerList() {
        for (String name : new String[]{"serverListSelector", "field_146803_h"}) {
            try {
                Field f = GuiMultiplayer.class.getDeclaredField(name);
                f.setAccessible(true);
                return (GuiSlot) f.get(this);
            } catch (Throwable ignored) {
                // try the next name
            }
        }
        return null;
    }

    private void addFooterButtons() {
        // Feedback/Donate stack in the bottom-left corner. Vanilla's two footer button rows
        // (y = height-52 / height-28) reach left to width/2-154; when the window is wide
        // enough to leave a clear gutter there, sit in it as before, otherwise lift the stack
        // above both rows so it never lands on "Join Server" / "Edit".
        boolean gutterFits = this.width / 2 - 154 >= FOOTER_BUTTON_WIDTH + 6;
        int donateY = gutterFits ? this.height - 4 - FOOTER_BUTTON_HEIGHT
                                 : this.height - 52 - 6 - FOOTER_BUTTON_HEIGHT;
        int feedbackY = donateY - FOOTER_BUTTON_HEIGHT - 4;
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.title.feedback_button"), () -> openLink(FEEDBACK_MAILTO))
                .bounds(2, feedbackY, FOOTER_BUTTON_WIDTH, FOOTER_BUTTON_HEIGHT).build());
        this.buttonList.add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.title.donate_button"), () -> openLink(DONATE_URL))
                .bounds(2, donateY, FOOTER_BUTTON_WIDTH, FOOTER_BUTTON_HEIGHT).build());
    }

    private void openLink(String url) {
        // No ConfirmLinkScreen dance here (the src/main version has one); 1.12.2 mods just
        // hand the URL to the OS. LWJGL 2's Sys.openURL is the portable path.
        try {
            org.lwjgl.Sys.openURL(url);
        } catch (Throwable t) {
            try {
                java.awt.Desktop.getDesktop().browse(new URI(url));
            } catch (Throwable ignored) {
                // give up silently — a dead Feedback/Donate button is not worth a crash
            }
        }
    }

    private void addFavoritesGlyphButtons() {
        if (this.vBack != null) {
            int accountGlyphX = this.vBack.x + this.vBack.width + 6;
            this.buttonList.add(PeerCraftUi.squareGlyphButton(
                    accountGlyphX, this.vBack.y, this.vBack.height,
                    "☺", PeerCraftLang.tr("peercraft.gui.multiplayer.account_tooltip"),
                    () -> PeerCraftUi.setScreen(this.mc, new PeerCraftAccountScreen(this))));
            this.buttonList.add(PeerCraftUi.squareGlyphButton(
                    accountGlyphX + this.vBack.height + 6, this.vBack.y, this.vBack.height,
                    "⚙", PeerCraftLang.tr("peercraft.gui.settings.glyph_tooltip"),
                    () -> PeerCraftUi.setScreen(this.mc, new PeerCraftSettingsScreen(this))));
        }
        if (this.vAdd != null && !PeerCraftConfig.MODE_HOST.equals(PeerCraftConfig.mode())) {
            this.buttonList.add(PeerCraftUi.squareGlyphButton(
                    this.vAdd.x + this.vAdd.width + 6, this.vAdd.y, this.vAdd.height,
                    "▶", PeerCraftLang.tr("peercraft.gui.multiplayer.join_by_code_tooltip"),
                    () -> PeerCraftUi.setScreen(this.mc, new PeerCraftJoinScreen(this))));
        }
    }

    private void buildTabBar() {
        int barWidth = Math.min(this.width - 20, 460);
        int tabWidth = (barWidth - 12) / 4;
        int startX = this.width / 2 - barWidth / 2;
        int y = 30;
        this.tabFavoritesButton = add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.multiplayer.tab_favorites"), () -> switchTab(Tab.FAVORITES))
                .bounds(startX, y, tabWidth, 20).build());
        this.tabFriendsButton = add(IdButton.builder(friendsTabLabel(), () -> switchTab(Tab.FRIENDS))
                .bounds(startX + tabWidth + 4, y, tabWidth, 20).build());
        this.tabDiscoverButton = add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.multiplayer.tab_discover"), () -> switchTab(Tab.DISCOVER))
                .bounds(startX + 2 * (tabWidth + 4), y, tabWidth, 20).build());
        this.tabGamesButton = add(IdButton.builder(gamesTabLabel(), () -> switchTab(Tab.GAMES))
                .bounds(startX + 3 * (tabWidth + 4), y, tabWidth, 20).build());
    }

    private <T extends GuiButton> T add(T button) {
        this.buttonList.add(button);
        return button;
    }

    private String friendsTabLabel() {
        return PeerCraftLang.tr("peercraft.gui.multiplayer.tab_friends", this.friends == null ? 0 : this.friends.size());
    }

    private String gamesTabLabel() {
        return PeerCraftLang.tr("peercraft.gui.multiplayer.tab_games", this.games == null ? 0 : this.games.size());
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

    private void applyTabVisibility() {
        boolean fav = this.currentTab == Tab.FAVORITES;
        boolean fr = this.currentTab == Tab.FRIENDS;
        boolean disc = this.currentTab == Tab.DISCOVER;
        boolean games = this.currentTab == Tab.GAMES;

        // Vanilla bottom-row buttons stay VISIBLE on every tab — only greyed out (disabled) off
        // the Favorites tab, so the footer doesn't change shape between tabs. Refresh and Back
        // stay usable everywhere (Refresh re-opens this screen keeping the tab; Back = leave).
        for (GuiButton b : new GuiButton[]{vSelect, vDelete, vAdd, vDirect, vEdit, vRefresh, vBack}) {
            if (b != null) {
                b.visible = true;
            }
        }
        for (GuiButton b : new GuiButton[]{vSelect, vDelete, vAdd, vDirect, vEdit}) {
            if (b != null) {
                b.enabled = fav;
            }
        }
        if (vRefresh != null) vRefresh.enabled = true;
        if (vBack != null) vBack.enabled = true;

        // Keep the (undrawn) vanilla server list from swallowing clicks on the other tabs: push
        // it fully off-screen unless Favorites is showing, restore vanilla bounds when it is.
        // setDimensions(width, height, top, bottom) is public on GuiSlot; Favorites bounds
        // (32 .. height-64) match what GuiMultiplayer.initGui() sets. See serverListRef.
        if (this.serverListRef != null) {
            if (fav) {
                this.serverListRef.setDimensions(this.width, this.height, 32, this.height - 64);
            } else {
                this.serverListRef.setDimensions(this.width, this.height, this.height + 1000, this.height + 1000);
            }
        }

        setVisible(this.friendsStaticWidgets, fr);
        setVisible(this.friendRowWidgets, fr);
        setVisible(this.discoverStaticWidgets, disc);
        setVisible(this.discoverResultWidgets, disc);
        setVisible(this.gamesStaticWidgets, games);
        setVisible(this.gameRowWidgets, games);
        if (this.addByCodeBox != null) this.addByCodeBox.setVisible(fr);
        if (this.searchQueryBox != null) this.searchQueryBox.setVisible(disc);
        if (this.gameSearchBox != null) this.gameSearchBox.setVisible(games);

        this.tabFavoritesButton.enabled = !fav;
        this.tabFriendsButton.enabled = !fr;
        this.tabDiscoverButton.enabled = !disc;
        this.tabGamesButton.enabled = !games;
    }

    private static void setVisible(List<GuiButton> widgets, boolean visible) {
        for (GuiButton w : widgets) {
            w.visible = visible;
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
            return;
        }
        if (button.id == VANILLA_REFRESH) {
            this.mc.displayGuiScreen(new PeerCraftMultiplayerScreen(this.lastScreen, this.currentTab));
            return;
        }
        super.actionPerformed(button);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        boolean enter = keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER;
        if (this.currentTab == Tab.FRIENDS) {
            if (enter && this.addByCodeBox.isFocused() && this.addByCodeButton.enabled) {
                onAddByCode();
                return;
            }
            if (this.addByCodeBox.textboxKeyTyped(typedChar, keyCode)) {
                return;
            }
        } else if (this.currentTab == Tab.DISCOVER) {
            if (enter && this.searchQueryBox.isFocused() && this.searchButton.enabled) {
                onSearch();
                return;
            }
            if (this.searchQueryBox.textboxKeyTyped(typedChar, keyCode)) {
                return;
            }
        } else if (this.currentTab == Tab.GAMES) {
            if (this.gameSearchBox.textboxKeyTyped(typedChar, keyCode)) {
                rebuildGameRows();
                return;
            }
        }
        if (this.currentTab == Tab.FAVORITES) {
            super.keyTyped(typedChar, keyCode);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (this.currentTab == Tab.FRIENDS) {
            this.addByCodeBox.mouseClicked(mouseX, mouseY, mouseButton);
        } else if (this.currentTab == Tab.DISCOVER) {
            this.searchQueryBox.mouseClicked(mouseX, mouseY, mouseButton);
        } else if (this.currentTab == Tab.GAMES) {
            this.gameSearchBox.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    // 1.12.2 has no mouseScrolled callback — the wheel arrives here as a raw LWJGL event delta.
    // On the Find Players tab, consume it to walk the result-list offset; every other tab is
    // left to GuiMultiplayer's own handler (which only forwards scroll to the off-tab, undrawn
    // server list — harmless).
    @Override
    public void handleMouseInput() throws IOException {
        if (this.currentTab == Tab.DISCOVER) {
            int wheel = Mouse.getEventDWheel();
            if (wheel != 0 && discoverMaxScroll() > 0) {
                setDiscoverScroll(this.discoverScroll + (wheel > 0 ? -1 : 1));
            }
        }
        super.handleMouseInput();
    }

    @Override
    public void updateScreen() {
        if (this.currentTab == Tab.FAVORITES) {
            super.updateScreen();
        }
        if (this.addByCodeBox != null) this.addByCodeBox.updateCursorCounter();
        if (this.searchQueryBox != null) this.searchQueryBox.updateCursorCounter();
        if (this.gameSearchBox != null) this.gameSearchBox.updateCursorCounter();

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
                            tabFriendsButton.displayString = friendsTabLabel();
                            rebuildFriendRows();
                        }
                    });
                }

                @Override
                public void onTimeout() {
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
                            tabGamesButton.displayString = gamesTabLabel();
                            rebuildGameRows();
                        }
                    });
                }

                @Override
                public void onTimeout() {
                }
            });
        }
    }

    // ==================== FRIENDS TAB ====================

    private void buildFriendsTab() {
        int centerX = this.width / 2;
        int rowsBottom = friendsInputTop();

        this.addByCodeBox = new GuiTextField(20, this.fontRenderer, centerX - 200, rowsBottom + 8, 120, 20);
        this.addByCodeBox.setMaxStringLength(6);

        this.addByCodeButton = add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.multiplayer.add_by_code_button"), this::onAddByCode)
                .bounds(centerX - 70, rowsBottom + 8, 150, 20).build());
        this.friendsStaticWidgets.add(this.addByCodeButton);

        this.friendsStaticWidgets.add(add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.multiplayer.friend_requests_button"),
                () -> PeerCraftUi.setScreen(this.mc, new PeerCraftFriendRequestsScreen(this)))
                .bounds(centerX - 100, rowsBottom + 34, 200, 20).build()));

        rebuildFriendRows();
        if (this.friends == null) {
            loadFriends();
        }
    }

    private void loadFriends() {
        this.friendsStatusMessage = PeerCraftLang.tr("peercraft.gui.multiplayer.loading_friends");
        this.friendsStatusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.listFriends(new AccountClient.FriendListCallback() {
            @Override
            public void onResult(List<AccountClient.FriendInfo> result) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    friends = result;
                    friendsStatusMessage = "";
                    tabFriendsButton.displayString = friendsTabLabel();
                    rebuildFriendRows();
                });
            }

            @Override
            public void onTimeout() {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        friendsStatusMessage = PeerCraftLang.tr("peercraft.gui.common.account_server_timeout");
                        friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                    }
                });
            }
        });
    }

    private void rebuildFriendRows() {
        for (GuiButton w : this.friendRowWidgets) {
            this.buttonList.remove(w);
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

            IdButton connectButton = IdButton.builder(PeerCraftLang.tr("peercraft.gui.multiplayer.connect"), () -> onConnectToFriend(friend))
                    .bounds(centerX + 20, rowY, 110, 18).build();
            connectButton.enabled = friend.status() == AccountProtocol.STATUS_HOSTING;
            connectButton.visible = fr;
            this.buttonList.add(connectButton);
            this.friendRowWidgets.add(connectButton);

            IdButton removeButton = IdButton.builder(PeerCraftLang.tr("peercraft.gui.multiplayer.remove"), () -> confirmRemoveFriend(friend))
                    .bounds(centerX + 135, rowY, 60, 18).build();
            removeButton.visible = fr;
            this.buttonList.add(removeButton);
            this.friendRowWidgets.add(removeButton);
        }

        if (this.friends.size() > shown) {
            this.friendsStatusMessage = PeerCraftLang.tr("peercraft.gui.common.shown_first", shown, this.friends.size());
            this.friendsStatusColor = PeerCraftUi.TEXT_MUTED;
        } else if (this.friends.isEmpty()) {
            this.friendsStatusMessage = PeerCraftLang.tr("peercraft.gui.multiplayer.no_friends_yet", PeerCraftLang.tr("peercraft.gui.multiplayer.tab_discover"));
            this.friendsStatusColor = PeerCraftUi.TEXT_MUTED;
        } else {
            this.friendsStatusMessage = "";
        }
    }

    private void onAddByCode() {
        String code = this.addByCodeBox.getText().trim().toUpperCase(Locale.ROOT);
        if (code.length() != 6) {
            this.friendsStatusMessage = PeerCraftLang.tr("peercraft.gui.login_code.code_length_error");
            this.friendsStatusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        this.addByCodeButton.enabled = false;
        this.friendsStatusMessage = PeerCraftLang.tr("peercraft.gui.multiplayer.searching_player");
        this.friendsStatusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.lookupFriendCode(code, new AccountClient.FriendCodeLookupCallback() {
            @Override
            public void onResult(boolean found, UUID accountId, boolean licensed, String displayName) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    if (!found) {
                        friendsStatusMessage = PeerCraftLang.tr("peercraft.gui.multiplayer.player_not_found");
                        friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                        addByCodeButton.enabled = true;
                        return;
                    }
                    sendFriendRequest(accountId, displayName, ok -> {
                        if (ok) {
                            friendsStatusMessage = PeerCraftLang.tr("peercraft.gui.multiplayer.request_sent", displayName);
                            friendsStatusColor = PeerCraftUi.TEXT_SUCCESS;
                        }
                    });
                    addByCodeButton.enabled = true;
                });
            }

            @Override
            public void onTimeout() {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    friendsStatusMessage = PeerCraftLang.tr("peercraft.gui.common.account_server_timeout");
                    friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                    addByCodeButton.enabled = true;
                });
            }
        });
    }

    private void confirmRemoveFriend(AccountClient.FriendInfo friend) {
        this.pendingRemoveFriend = friend;
        this.mc.displayGuiScreen(new GuiYesNo(this,
                PeerCraftLang.tr("peercraft.gui.multiplayer.remove_friend_confirm_title"),
                PeerCraftLang.tr("peercraft.gui.multiplayer.remove_friend_confirm_message", friend.displayName()),
                DIALOG_REMOVE_FRIEND));
    }

    @Override
    public void confirmClicked(boolean result, int id) {
        if (id == DIALOG_REMOVE_FRIEND) {
            AccountClient.FriendInfo friend = this.pendingRemoveFriend;
            this.pendingRemoveFriend = null;
            this.mc.displayGuiScreen(this);
            if (result && friend != null) {
                onRemoveFriend(friend);
            }
            return;
        }
        super.confirmClicked(result, id);
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
                    tabFriendsButton.displayString = friendsTabLabel();
                    rebuildFriendRows();
                });
            }

            @Override
            public void onFailed(String reason) {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        friendsStatusMessage = reason;
                        friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                    }
                });
            }
        });
    }

    private void onConnectToFriend(AccountClient.FriendInfo friend) {
        this.friendsStatusMessage = PeerCraftLang.tr("peercraft.gui.multiplayer.connecting_to", friend.displayName());
        this.friendsStatusColor = PeerCraftUi.TEXT_MUTED;
        P2PBridge.INSTANCE.startClientViaRendezvous(friend.roomCode(), PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(),
                new P2PBridge.ConnectListener() {
                    @Override
                    public void onStatus(String message) {
                        runOnClientThread(() -> {
                            if (stillOnThisScreen()) {
                                friendsStatusMessage = PeerCraftLang.tr(message);
                                friendsStatusColor = PeerCraftUi.TEXT_MUTED;
                            }
                        });
                    }

                    @Override
                    public void onConnected() {
                        runOnClientThread(() -> {
                            if (stillOnThisScreen()) {
                                startVanillaConnect();
                            }
                        });
                    }

                    @Override
                    public void onFailed(String reason) {
                        runOnClientThread(() -> {
                            if (stillOnThisScreen()) {
                                friendsStatusMessage = PeerCraftLang.tr(reason);
                                friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                            }
                        });
                    }
                }, new ClientModSyncAgent(this, friend.roomCode()));
    }

    private void startVanillaConnect() {
        int port = P2PBridge.INSTANCE.getProxyPort();
        this.mc.displayGuiScreen(new GuiConnecting(this.lastScreen, this.mc, "127.0.0.1", port));
    }

    // ==================== DISCOVER TAB ====================

    private void buildDiscoverTab() {
        int centerX = this.width / 2;
        int top = CONTENT_TOP + 4;
        this.searchQueryBox = new GuiTextField(21, this.fontRenderer, centerX - 100, top, 200, 20);
        this.searchQueryBox.setMaxStringLength(16);

        this.searchButton = add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.multiplayer.search_button"), this::onSearch)
                .bounds(centerX + 105, top, 90, 20).build());
        this.discoverStaticWidgets.add(this.searchButton);
        rebuildSearchResults();
    }

    private void onSearch() {
        // Player search is an account query — with no session AccountClient.searchAccounts()
        // just returns an empty list, which the "Nobody found" line makes indistinguishable
        // from a real no-match. Tell the player they need to log in instead.
        if (AccountClient.INSTANCE.getCurrentSession() == null) {
            this.discoverStatusMessage = PeerCraftLang.tr("peercraft.gui.multiplayer.search_requires_login");
            this.discoverStatusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
        String query = this.searchQueryBox.getText().trim();
        this.searchButton.enabled = false;
        this.discoverStatusMessage = PeerCraftLang.tr("peercraft.gui.multiplayer.searching");
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
                    discoverStatusMessage = found.isEmpty() ? PeerCraftLang.tr("peercraft.gui.multiplayer.nobody_found") : "";
                    discoverStatusColor = PeerCraftUi.TEXT_MUTED;
                    searchButton.enabled = true;
                    rebuildSearchResults();
                });
            }

            @Override
            public void onTimeout() {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    discoverStatusMessage = PeerCraftLang.tr("peercraft.gui.common.account_server_timeout");
                    discoverStatusColor = PeerCraftUi.TEXT_ERROR;
                    searchButton.enabled = true;
                });
            }
        });
    }

    private void rebuildSearchResults() {
        for (GuiButton w : this.discoverResultWidgets) {
            this.buttonList.remove(w);
        }
        this.discoverResultWidgets.clear();
        int centerX = this.width / 2;
        int top = discoverResultsTop();
        boolean disc = this.currentTab == Tab.DISCOVER;
        this.discoverScroll = Math.max(0, Math.min(this.discoverScroll, discoverMaxScroll()));
        int shown = Math.min(discoverVisibleRows(), this.searchResults.size() - this.discoverScroll);
        for (int i = 0; i < shown; i++) {
            AccountClient.SearchResult result = this.searchResults.get(this.discoverScroll + i);
            IdButton addButton = IdButton.builder(PeerCraftLang.tr("peercraft.gui.multiplayer.add_friend"), () -> onAddFriend(result))
                    .bounds(centerX + 30, top + i * ROW_HEIGHT, 170, 18).build();
            addButton.visible = disc;
            this.buttonList.add(addButton);
            this.discoverResultWidgets.add(addButton);
        }
    }

    private void onAddFriend(AccountClient.SearchResult result) {
        sendFriendRequest(result.accountId(), result.displayName(), ok -> {
            if (ok) {
                discoverStatusMessage = PeerCraftLang.tr("peercraft.gui.multiplayer.request_sent", result.displayName());
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
                        friendsStatusMessage = reason;
                        friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                        discoverStatusMessage = reason;
                        discoverStatusColor = PeerCraftUi.TEXT_ERROR;
                        onDone.handle(false);
                    }
                });
            }
        });
    }

    // ==================== GAMES TAB ====================

    private void buildGamesTab() {
        int centerX = this.width / 2;
        int top = CONTENT_TOP + 4;
        this.gameSearchBox = new GuiTextField(22, this.fontRenderer, centerX - 200, top, 190, 20);
        this.gameSearchBox.setMaxStringLength(32);
        rebuildVersionFilterButton();
        rebuildGameRows();
        if (this.games == null) {
            loadGames();
        }
    }

    private void rebuildVersionFilterButton() {
        List<String> versions = new ArrayList<String>();
        versions.add("");
        if (this.games != null) {
            List<String> seen = new ArrayList<String>();
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
            this.buttonList.remove(this.gameVersionFilterButton);
            this.gamesStaticWidgets.remove(this.gameVersionFilterButton);
        }
        int centerX = this.width / 2;
        int top = CONTENT_TOP + 4;
        this.gameVersionFilterButton = CycleTextButton.create(centerX + 10, top, 110, 20,
                this.versionFilterValues, this.selectedVersionFilter,
                this::versionFilterLabel,
                value -> {
                    this.selectedVersionFilter = value;
                    rebuildGameRows();
                });
        this.gameVersionFilterButton.visible = this.currentTab == Tab.GAMES;
        this.buttonList.add(this.gameVersionFilterButton);
        this.gamesStaticWidgets.add(this.gameVersionFilterButton);
    }

    private String versionFilterLabel(String v) {
        String name = v.isEmpty() ? PeerCraftLang.tr("peercraft.gui.multiplayer.game_version_filter_all") : v;
        return PeerCraftLang.tr("peercraft.gui.multiplayer.game_version_filter_field") + ": " + name;
    }

    private void recomputeFilteredGames() {
        if (this.games == null) {
            this.filteredGames = Collections.emptyList();
            return;
        }
        String search = this.gameSearchBox == null ? "" : this.gameSearchBox.getText().trim().toLowerCase(Locale.ROOT);
        List<AccountClient.PublicGameInfo> result = new ArrayList<AccountClient.PublicGameInfo>();
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
        this.gamesStatusMessage = PeerCraftLang.tr("peercraft.gui.multiplayer.loading_games");
        this.gamesStatusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.listPublicGames(new AccountClient.PublicGameListCallback() {
            @Override
            public void onResult(List<AccountClient.PublicGameInfo> result) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    games = result;
                    gamesStatusMessage = "";
                    tabGamesButton.displayString = gamesTabLabel();
                    rebuildVersionFilterButton();
                    rebuildGameRows();
                });
            }

            @Override
            public void onTimeout() {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        gamesStatusMessage = PeerCraftLang.tr("peercraft.gui.common.account_server_timeout");
                        gamesStatusColor = PeerCraftUi.TEXT_ERROR;
                    }
                });
            }
        });
    }

    private void rebuildGameRows() {
        for (GuiButton w : this.gameRowWidgets) {
            this.buttonList.remove(w);
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
            IdButton joinButton = IdButton.builder(PeerCraftLang.tr("peercraft.gui.multiplayer.connect"), () -> onJoinGame(game))
                    .bounds(centerX + 20, top + i * ROW_HEIGHT, 110, 18).build();
            joinButton.visible = gamesTab;
            this.buttonList.add(joinButton);
            this.gameRowWidgets.add(joinButton);
        }

        if (this.filteredGames.size() > shown) {
            this.gamesStatusMessage = PeerCraftLang.tr("peercraft.gui.common.shown_first", shown, this.filteredGames.size());
            this.gamesStatusColor = PeerCraftUi.TEXT_MUTED;
        } else if (this.games.isEmpty()) {
            this.gamesStatusMessage = PeerCraftLang.tr("peercraft.gui.multiplayer.no_public_games");
            this.gamesStatusColor = PeerCraftUi.TEXT_MUTED;
        } else if (this.filteredGames.isEmpty()) {
            this.gamesStatusMessage = PeerCraftLang.tr("peercraft.gui.multiplayer.no_games_match_filter");
            this.gamesStatusColor = PeerCraftUi.TEXT_MUTED;
        } else {
            this.gamesStatusMessage = "";
        }
    }

    private void onJoinGame(AccountClient.PublicGameInfo game) {
        String label = blank(game.worldName()) ? game.code() : game.worldName();
        this.gamesStatusMessage = PeerCraftLang.tr("peercraft.gui.multiplayer.joining_game", label);
        this.gamesStatusColor = PeerCraftUi.TEXT_MUTED;
        P2PBridge.INSTANCE.startClientViaRendezvous(game.code(), PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(),
                new P2PBridge.ConnectListener() {
                    @Override
                    public void onStatus(String message) {
                        runOnClientThread(() -> {
                            if (stillOnThisScreen()) {
                                gamesStatusMessage = PeerCraftLang.tr(message);
                                gamesStatusColor = PeerCraftUi.TEXT_MUTED;
                            }
                        });
                    }

                    @Override
                    public void onConnected() {
                        runOnClientThread(() -> {
                            if (stillOnThisScreen()) {
                                startVanillaConnect();
                            }
                        });
                    }

                    @Override
                    public void onFailed(String reason) {
                        runOnClientThread(() -> {
                            if (stillOnThisScreen()) {
                                gamesStatusMessage = PeerCraftLang.tr(reason);
                                gamesStatusColor = PeerCraftUi.TEXT_ERROR;
                            }
                        });
                    }
                }, new ClientModSyncAgent(this, game.code()));
    }

    // ==================== shared plumbing ====================

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void runOnClientThread(Runnable action) {
        Minecraft.getMinecraft().addScheduledTask(action);
    }

    private boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    /** Plain vanilla-style scrollbar (black track + grey thumb) down the right edge of the Find Players results, only when they overflow. */
    private void drawDiscoverScrollbar(int centerX) {
        int total = this.searchResults.size();
        int visible = discoverVisibleRows();
        if (visible <= 0 || total <= visible) {
            return;
        }
        int trackTop = discoverResultsTop();
        int trackHeight = visible * ROW_HEIGHT;
        int left = centerX + 202;
        int right = left + 6;
        drawRect(left, trackTop, right, trackTop + trackHeight, 0xFF000000);
        int maxScroll = total - visible;
        int scroll = Math.max(0, Math.min(this.discoverScroll, maxScroll));
        int thumbHeight = Math.max(16, trackHeight * visible / total);
        int thumbY = trackTop + (trackHeight - thumbHeight) * scroll / maxScroll;
        drawRect(left, thumbY, right, thumbY + thumbHeight, 0xFFA0A0A0);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int centerX = this.width / 2;
        if (this.currentTab == Tab.FAVORITES) {
            super.drawScreen(mouseX, mouseY, partialTicks);
        } else {
            this.drawDefaultBackground();
            for (int i = 0; i < this.buttonList.size(); i++) {
                this.buttonList.get(i).drawButton(this.mc, mouseX, mouseY, partialTicks);
            }
        }

        // tab bar sits above the content on every tab; drawn by the loop above / super.

        if (this.currentTab == Tab.FRIENDS) {
            this.addByCodeBox.drawTextBox();
            int top = CONTENT_TOP + 4;
            int shown = this.friends == null ? 0 : Math.min(this.friends.size(), rowsFitting(top, friendsInputTop() + 4));
            for (int i = 0; i < shown; i++) {
                AccountClient.FriendInfo friend = this.friends.get(i);
                int rowY = top + i * ROW_HEIGHT + 5;
                int afterBadgeX = PeerCraftUi.drawNameWithBadge(this.fontRenderer, friend.displayName(), friend.licensed(), centerX - 200, rowY, PeerCraftUi.TEXT_TITLE);
                String status;
                int statusColor;
                switch (friend.status()) {
                    case AccountProtocol.STATUS_HOSTING:
                        status = PeerCraftLang.tr("peercraft.gui.multiplayer.status_hosting", friend.roomCode());
                        statusColor = PeerCraftUi.TEXT_ACCENT;
                        break;
                    case AccountProtocol.STATUS_ONLINE:
                        status = PeerCraftLang.tr("peercraft.gui.multiplayer.status_online");
                        statusColor = PeerCraftUi.TEXT_SUCCESS;
                        break;
                    default:
                        status = PeerCraftLang.tr("peercraft.gui.multiplayer.status_offline");
                        statusColor = PeerCraftUi.TEXT_MUTED;
                        break;
                }
                this.fontRenderer.drawString(" — " + status, afterBadgeX, rowY, statusColor);
            }
            if (!this.friendsStatusMessage.isEmpty()) {
                this.drawCenteredString(this.fontRenderer, this.friendsStatusMessage, centerX, statusLineY(), this.friendsStatusColor);
            }
        } else if (this.currentTab == Tab.DISCOVER) {
            this.searchQueryBox.drawTextBox();
            int top = discoverResultsTop();
            int shown = Math.min(discoverVisibleRows(), this.searchResults.size() - this.discoverScroll);
            for (int i = 0; i < shown; i++) {
                AccountClient.SearchResult result = this.searchResults.get(this.discoverScroll + i);
                PeerCraftUi.drawNameWithBadge(this.fontRenderer, result.displayName(), result.licensed(), centerX - 200, top + i * ROW_HEIGHT + 5, PeerCraftUi.TEXT_TITLE);
            }
            drawDiscoverScrollbar(centerX);
            if (!this.discoverStatusMessage.isEmpty()) {
                this.drawCenteredString(this.fontRenderer, this.discoverStatusMessage, centerX, statusLineY(), this.discoverStatusColor);
            }
        } else if (this.currentTab == Tab.GAMES) {
            this.gameSearchBox.drawTextBox();
            int top = CONTENT_TOP + 32;
            int shown = Math.min(this.filteredGames.size(), rowsFitting(top, statusLineY() - 4));
            for (int i = 0; i < shown; i++) {
                AccountClient.PublicGameInfo game = this.filteredGames.get(i);
                int rowY = top + i * ROW_HEIGHT + 5;
                String hostName = blank(game.hostDisplayName())
                        ? PeerCraftLang.tr("peercraft.gui.multiplayer.anonymous_host")
                        : game.hostDisplayName();
                String worldName = blank(game.worldName()) ? game.code() : game.worldName();
                String versionSuffix = blank(game.mcVersion()) ? "" : " [" + game.mcVersion() + "]";
                String line = worldName + " — " + hostName + " (" + game.currentPlayerCount() + "/" + game.maxPlayers() + ")" + versionSuffix;
                this.fontRenderer.drawString(line, centerX - 200, rowY, PeerCraftUi.TEXT_TITLE);
            }
            if (!this.gamesStatusMessage.isEmpty()) {
                this.drawCenteredString(this.fontRenderer, this.gamesStatusMessage, centerX, statusLineY(), this.gamesStatusColor);
            }
        }
    }
}
