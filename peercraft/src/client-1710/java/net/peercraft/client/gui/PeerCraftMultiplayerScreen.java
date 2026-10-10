package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.gui.GuiTextField;
import net.peercraft.client.modsync.ClientModSyncAgent;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.client.theme.SteampunkPalette;
import net.peercraft.network.account.AccountClient;
import net.peercraft.network.p2p.P2PBridge;
import net.peercraft.network.rendezvous.AccountProtocol;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Forge 1.7.10 backport of {@code src/main/.../PeerCraftMultiplayerScreen.java} (twin of the
 * {@code src/client-1122} backport) — vanilla's {@link GuiMultiplayer} with a PeerCraft tab bar
 * (Favorites / Friends / Find Players / Games).
 *
 * <p>Structurally identical to the 1.12.2 twin — {@code GuiMultiplayer}'s button ids and
 * private {@code serverListSelector} are the same on 1.7.10. Deltas beyond the shared
 * mechanical swaps:
 * <ul>
 *   <li>{@code GuiMultiplayer}'s {@code serverListSelector} is {@code private} — instead of
 *       moving it off-screen, non-Favorites tabs simply don't call {@code super.drawScreen()}
 *       (which is what draws the list) and draw their own content; the vanilla list buttons
 *       are found by their fixed ids (1 select / 2 delete / 3 add / 4 direct / 7 edit /
 *       8 refresh / 0 back — unchanged from 1.12.2, verified against the 1.7.10
 *       {@code GuiMultiplayer.createButtons()}) in {@code this.buttonList} after
 *       {@code super.initGui()} and toggled per tab.</li>
 *   <li>Text fields are plain {@link GuiTextField}s wired by hand (not widgets in the button
 *       list); {@code setResponder} → re-filter in {@code keyTyped}.</li>
 *   <li>No {@code tick()} — polling runs from {@code updateScreen()}. No {@code CycleButton} —
 *       the version filter is an {@link IdButton} that cycles a list. {@code ConfirmScreen} →
 *       the shared themed PeerCraft confirmation with a once-only callback.</li>
 *   <li>Custom refresh keeps the current tab by intercepting vanilla button id 8.</li>
 *   <li>1.7.10-only: {@code buttonList} is a raw {@code List} and {@code GuiButton#drawButton}
 *       takes no {@code partialTicks} — see the manual draw loop in {@link #drawScreen}.</li>
 * </ul>
 */
public class PeerCraftMultiplayerScreen extends GuiMultiplayer {

    private enum Tab { FAVORITES, FRIENDS, DISCOVER, GAMES }

    private int CONTENT_TOP = 58;
    private SteampunkDialog panel;
    private boolean drawingVanillaFavorites;
    private static final int ROW_HEIGHT = 20;

    private static final String FEEDBACK_MAILTO = "mailto:peercraft2@gmail.com?subject=PeerCraft%20feedback";
    private static final String DONATE_URL = "https://boosty.to/peercraft";
    private static final int FOOTER_BUTTON_WIDTH = 70;
    private static final int FOOTER_BUTTON_HEIGHT = 20;

    private static final int VANILLA_SELECT = 1, VANILLA_DELETE = 2, VANILLA_ADD = 3,
            VANILLA_DIRECT = 4, VANILLA_EDIT = 7, VANILLA_REFRESH = 8, VANILLA_BACK = 0;
    private static final int POLL_INTERVAL_TICKS = 100;

    private final GuiScreen lastScreen;
    private Tab currentTab;
    private String initialFriendCode = "", initialPlayerSearch = "", initialGameSearch = "";

    private GuiButton tabFavoritesButton, tabFriendsButton, tabDiscoverButton, tabGamesButton;
    private GuiButton vSelect, vDelete, vAdd, vDirect, vEdit, vRefresh, vBack;

    /**
     * Vanilla's {@code GuiMultiplayer.field_146803_h} (the server list) is {@code private} and
     * hit-tests clicks straight from mouse coordinates in {@code mouseClicked} /
     * {@code mouseMovedOrUp} — whether it was drawn this frame or not is irrelevant. Off the
     * Favorites tab we skip drawing it, but that alone left hidden favorite rows clickable
     * (they'd still select/join). Grabbed once by reflection so {@link #applyTabVisibility()}
     * can shove it off-screen ({@code top == bottom} ⇒ {@code isMouseYWithinSlotBounds} always
     * false) on every non-Favorites tab and restore vanilla bounds on Favorites — the same fix
     * the modern {@code src/main} / {@code src/client-1165} screens apply to their (accessible)
     * list field. Null only if the field name ever stops resolving, in which case the old
     * (buggy) behaviour is kept rather than crashing the screen.
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
    private int friendsScroll, gamesScroll;
    private String gameFilterSnapshot = "";

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
        String savedCode = this.addByCodeBox == null ? initialFriendCode : this.addByCodeBox.getText();
        String savedSearch = this.searchQueryBox == null ? initialPlayerSearch : this.searchQueryBox.getText();
        String savedGameSearch = this.gameSearchBox == null ? initialGameSearch : this.gameSearchBox.getText();
        this.panel = new SteampunkDialog(this.width, this.height, this.height - 16,
                net.minecraft.client.resources.I18n.format("multiplayer.title", new Object[0]), 460);
        this.CONTENT_TOP = panel.top + panel.headerHeight + 32;
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

        layoutFooterButtons();
        addFooterButtons();
        addFavoritesGlyphButtons();
        buildTabBar();
        buildFriendsTab();
        buildDiscoverTab();
        buildGamesTab();
        this.addByCodeBox.setText(savedCode);
        this.searchQueryBox.setText(savedSearch);
        this.gameSearchBox.setText(savedGameSearch);
        rebuildGameRows();
        applyTabVisibility();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    private GuiButton findVanilla(int id) {
        // this.buttonList is a raw List on 1.7.10 — element type is Object, so cast explicitly.
        for (Object o : this.buttonList) {
            GuiButton b = (GuiButton) o;
            if (b.id == id) {
                return b;
            }
        }
        return null;
    }

    /**
     * The server list is {@code GuiMultiplayer.field_146803_h} — {@code private}, no getter. The
     * name is identical in the dev (stable_12, no readable mapping) and reobf (SRG) runtimes, so
     * a plain reflective lookup works in both. Failure is non-fatal (see {@link #serverListRef}).
     */
    public boolean ownsServerList(Object list) { return this.serverListRef == list; }
    public int favoritesListWidth() { return Math.max(32, contentWidth() - 12); }
    public int favoritesScrollbarX() { return contentLeft() + contentWidth() - 6; }
    public int favoritesLeft() { return contentLeft(); }
    public int favoritesRight() { return contentLeft() + contentWidth(); }

    private GuiSlot findServerList() {
        try {
            Field f = GuiMultiplayer.class.getDeclaredField("field_146803_h");
            f.setAccessible(true);
            return (GuiSlot) f.get(this);
        } catch (Throwable t) {
            return null;
        }
    }

    private GuiButton feedbackGlyph, donateGlyph;

    /** Reserve space for support/account/settings glyphs inside the panel on both rows. */
    private void layoutFooterButtons() {
        int gap = 6;
        int glyph = 20;
        GuiButton[][] rows = {{vSelect, vDirect, vAdd}, {vEdit, vDelete, vRefresh, vBack}};
        for (int row = 0; row < rows.length; row++) {
            int reservedGlyphs = row == 0 ? 2 : 3;
            int width = Math.max(20, (contentWidth() - reservedGlyphs * glyph
                    - (rows[row].length + reservedGlyphs - 1) * gap) / rows[row].length);
            int x = contentLeft() + glyph + gap;
            for (GuiButton button : rows[row]) {
                if (button != null) {
                    button.xPosition = x;
                    button.width = width;
                }
                x += width + gap;
            }
        }
    }

    private void addFooterButtons() {
        if (vSelect == null || vEdit == null) return;
        feedbackGlyph = PeerCraftUi.squareGlyphButton(vSelect.xPosition - vSelect.height - 6, vSelect.yPosition, vSelect.height,
                "!", "", () -> openLink(FEEDBACK_MAILTO));
        donateGlyph = PeerCraftUi.squareGlyphButton(vEdit.xPosition - vEdit.height - 6, vEdit.yPosition, vEdit.height,
                "$", "", () -> openLink(DONATE_URL));
        buttonList.add(feedbackGlyph);
        buttonList.add(donateGlyph);
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
            int accountGlyphX = this.vBack.xPosition + this.vBack.width + 6;
            this.buttonList.add(PeerCraftUi.squareGlyphButton(
                    accountGlyphX, this.vBack.yPosition, this.vBack.height,
                    "☺", PeerCraftLang.tr("peercraft.gui.multiplayer.account_tooltip"),
                    () -> PeerCraftUi.setScreen(this.mc, new PeerCraftAccountScreen(this))));
            this.buttonList.add(PeerCraftUi.squareGlyphButton(
                    accountGlyphX + this.vBack.height + 6, this.vBack.yPosition, this.vBack.height,
                    "*", PeerCraftLang.tr("peercraft.gui.settings.glyph_tooltip"),
                    () -> PeerCraftUi.setScreen(this.mc, new PeerCraftSettingsScreen(this))));
        }
        if (this.vAdd != null && !PeerCraftConfig.MODE_HOST.equals(PeerCraftConfig.mode())) {
            this.buttonList.add(PeerCraftUi.squareGlyphButton(
                    this.vAdd.xPosition + this.vAdd.width + 6, this.vAdd.yPosition, this.vAdd.height,
                    "▶", PeerCraftLang.tr("peercraft.gui.multiplayer.join_by_code_tooltip"),
                    () -> PeerCraftUi.setScreen(this.mc, new PeerCraftJoinScreen(this))));
        }
    }

    private void buildTabBar() {
        int barWidth = panel.contentWidth();
        int tabWidth = (barWidth - 12) / 4;
        int startX = this.width / 2 - barWidth / 2;
        int y = CONTENT_TOP - 28;
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

    private int contentLeft() { return this.width / 2 - contentWidth() / 2; }
    private int contentWidth() { return panel == null ? Math.max(40, Math.min(400, this.width - 48)) : panel.contentWidth() - 12; }
    private int actionWidth() { return Math.min(150, contentWidth() / 3); }
    private int friendsInputTop() { return CONTENT_TOP + 4; }
    private int friendsRowsTop() { return friendsInputTop() + 52; }
    private int rowsFitting(int rowsTop, int rowsBottom) {
        return Math.max(0, (rowsBottom - rowsTop) / ROW_HEIGHT);
    }
    private int friendsMaxScroll() {
        return Math.max(0, (friends == null ? 0 : friends.size()) - rowsFitting(friendsRowsTop(), statusLineY() - 4));
    }
    private int gamesMaxScroll() {
        return Math.max(0, filteredGames.size() - rowsFitting(discoverResultsTop(), statusLineY() - 4));
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
        if (fav && this.serverListRef instanceof net.minecraft.client.gui.ServerSelectionList) {
            // Restore the native selection-dependent state, rather than enabling Join/Edit/Delete unconditionally.
            this.func_146790_a(((net.minecraft.client.gui.ServerSelectionList) this.serverListRef).func_148193_k());
        }
        if (vRefresh != null) vRefresh.enabled = true;
        if (vBack != null) vBack.enabled = true;

        // Keep the (undrawn) vanilla server list from swallowing clicks on the other tabs: push
        // it fully off-screen unless Favorites is showing, restore vanilla bounds when it is.
        // func_148122_a(width, height, top, bottom) is public on GuiSlot; Favorites bounds
        // (32 .. height-64) match what GuiMultiplayer.initGui() sets. See serverListRef.
        if (this.serverListRef != null) {
            if (fav) {
                this.serverListRef.func_148122_a(this.width, this.height, 32, this.height - 64);
            } else {
                this.serverListRef.func_148122_a(this.width, this.height, this.height + 1000, this.height + 1000);
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
    protected void actionPerformed(GuiButton button) {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
            return;
        }
        if (button.id == VANILLA_REFRESH) {
            PeerCraftMultiplayerScreen refreshed = new PeerCraftMultiplayerScreen(this.lastScreen, this.currentTab);
            refreshed.initialFriendCode = this.addByCodeBox.getText();
            refreshed.initialPlayerSearch = this.searchQueryBox.getText();
            refreshed.initialGameSearch = this.gameSearchBox.getText();
            refreshed.selectedVersionFilter = this.selectedVersionFilter;
            this.mc.displayGuiScreen(refreshed);
            if (this.currentTab == Tab.DISCOVER && !refreshed.initialPlayerSearch.trim().isEmpty()) refreshed.onSearch();
            return;
        }
        super.actionPerformed(button);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_PRIOR || keyCode == Keyboard.KEY_NEXT) {
            int direction = keyCode == Keyboard.KEY_PRIOR ? -1 : 1;
            if (currentTab == Tab.FRIENDS) {
                friendsScroll = Math.max(0, Math.min(friendsMaxScroll(), friendsScroll + direction * Math.max(1, rowsFitting(friendsRowsTop(), statusLineY() - 4))));
                rebuildFriendRows(); return;
            }
            if (currentTab == Tab.GAMES) {
                gamesScroll = Math.max(0, Math.min(gamesMaxScroll(), gamesScroll + direction * Math.max(1, discoverVisibleRows())));
                rebuildGameRows(); return;
            }
            if (currentTab == Tab.DISCOVER) {
                setDiscoverScroll(discoverScroll + direction * Math.max(1, discoverVisibleRows())); return;
            }
        }
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
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (this.currentTab == Tab.FRIENDS) {
            this.addByCodeBox.mouseClicked(mouseX, mouseY, mouseButton);
        } else if (this.currentTab == Tab.DISCOVER) {
            this.searchQueryBox.mouseClicked(mouseX, mouseY, mouseButton);
        } else if (this.currentTab == Tab.GAMES) {
            this.gameSearchBox.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    // 1.7.10 has no mouseScrolled callback — the wheel arrives here as a raw LWJGL event delta.
    // On the Find Players tab, consume it to walk the result-list offset; every other tab is
    // left to the vanilla handler (the off-tab server list is undrawn, so its scroll is a no-op).
    @Override
    public void handleMouseInput() {
        int wheel = Mouse.getEventDWheel();
        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int rowsTop = currentTab == Tab.FRIENDS ? friendsRowsTop() : discoverResultsTop();
        if (wheel != 0 && mouseX >= contentLeft() && mouseX <= contentLeft() + contentWidth()
                && mouseY >= rowsTop && mouseY < statusLineY() - 4) {
            int step = wheel > 0 ? -3 : 3;
            if (currentTab == Tab.DISCOVER) setDiscoverScroll(discoverScroll + step);
            else if (currentTab == Tab.FRIENDS) {
                friendsScroll = Math.max(0, Math.min(friendsScroll + step, friendsMaxScroll()));
                rebuildFriendRows();
            } else if (currentTab == Tab.GAMES) {
                gamesScroll = Math.max(0, Math.min(gamesScroll + step, gamesMaxScroll()));
                rebuildGameRows();
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

        this.addByCodeBox = new SteampunkField(this.fontRendererObj, contentLeft(), rowsBottom, contentWidth() - actionWidth() - 6, 20);
        this.addByCodeBox.setMaxStringLength(6);

        this.addByCodeButton = add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.multiplayer.add_by_code_button"), this::onAddByCode)
                .primary().bounds(contentLeft() + contentWidth() - actionWidth(), rowsBottom, actionWidth(), 20).build());
        this.friendsStaticWidgets.add(this.addByCodeButton);

        this.friendsStaticWidgets.add(add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.multiplayer.friend_requests_button"),
                () -> PeerCraftUi.setScreen(this.mc, new PeerCraftFriendRequestsScreen(this)))
                .bounds(contentLeft(), rowsBottom + 26, contentWidth(), 20).build()));

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
        int top = friendsRowsTop();
        friendsScroll = Math.max(0, Math.min(friendsScroll, friendsMaxScroll()));
        int shown = Math.min(this.friends.size() - friendsScroll, rowsFitting(top, statusLineY() - 4));
        boolean fr = this.currentTab == Tab.FRIENDS;
        for (int i = 0; i < shown; i++) {
            AccountClient.FriendInfo friend = this.friends.get(friendsScroll + i);
            int rowY = top + i * ROW_HEIGHT;

            IdButton connectButton = IdButton.builder(PeerCraftLang.tr("peercraft.gui.multiplayer.connect"), () -> onConnectToFriend(friend))
                    .bounds(contentLeft() + contentWidth() - actionWidth() - 66, rowY, actionWidth(), 18).build();
            connectButton.enabled = friend.status() == AccountProtocol.STATUS_HOSTING;
            connectButton.visible = fr;
            this.buttonList.add(connectButton);
            this.friendRowWidgets.add(connectButton);

            IdButton removeButton = IdButton.builder(PeerCraftLang.tr("peercraft.gui.multiplayer.remove"), () -> confirmRemoveFriend(friend))
                    .bounds(contentLeft() + contentWidth() - 60, rowY, 60, 18).build();
            removeButton.visible = fr;
            this.buttonList.add(removeButton);
            this.friendRowWidgets.add(removeButton);
        }

        if (this.friends.isEmpty()) {
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
        this.mc.displayGuiScreen(new PeerCraftConfirmScreen(result -> {
            this.mc.displayGuiScreen(this);
            if (result) onRemoveFriend(friend);
        }, PeerCraftLang.tr("peercraft.gui.multiplayer.remove_friend_confirm_title"),
                PeerCraftLang.tr("peercraft.gui.multiplayer.remove_friend_confirm_message", friend.displayName())));
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
        if (PeerCraftProgressNoticeScreen.beforeConnecting(this, () -> onConnectToFriend(friend))) return;
        this.friendsStatusMessage = PeerCraftLang.tr("peercraft.gui.multiplayer.connecting_to", friend.displayName());
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
                                friendsStatusMessage = PeerCraftLang.tr(message);
                                friendsStatusColor = PeerCraftUi.TEXT_MUTED;
                            }
                        });
                    }

                    @Override
                    public void onConnected() {
                        dispatch(() -> {
                            if (stillOnThisScreen()) {
                                startVanillaConnect();
                            }
                        });
                    }

                    @Override
                    public void onFailed(String reason) {
                        dispatch(() -> {
                            if (stillOnThisScreen()) {
                                friendsStatusMessage = PeerCraftLang.tr(reason);
                                friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                            }
                        });
                    }
                }, new ClientModSyncAgent(this, friend.roomCode()));
    }

    private void startVanillaConnect() {
        TransportNoticeController.connecting();
        int port = P2PBridge.INSTANCE.getProxyPort();
        PeerCraftUi.connectLocal(this.lastScreen, port);
    }

    // ==================== DISCOVER TAB ====================

    private void buildDiscoverTab() {
        int centerX = this.width / 2;
        int top = CONTENT_TOP + 4;
        this.searchQueryBox = new SteampunkField(this.fontRendererObj, contentLeft(), top, contentWidth() - actionWidth() - 6, 20);
        this.searchQueryBox.setMaxStringLength(16);

        this.searchButton = add(IdButton.builder(PeerCraftLang.tr("peercraft.gui.multiplayer.search_button"), this::onSearch)
                .primary().bounds(contentLeft() + contentWidth() - actionWidth(), top, actionWidth(), 20).build());
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
                    .primary().bounds(contentLeft() + contentWidth() - actionWidth(), top + i * ROW_HEIGHT, actionWidth(), 18).build();
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
        this.gameSearchBox = new SteampunkField(this.fontRendererObj, contentLeft(), top, contentWidth() - actionWidth() - 6, 20);
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
        if (this.games != null && !versions.contains(this.selectedVersionFilter)) {
            this.selectedVersionFilter = "";
        }
        if (this.games == null && !this.selectedVersionFilter.isEmpty()) versions.add(this.selectedVersionFilter);
        this.versionFilterValues = versions;

        if (this.gameVersionFilterButton != null) {
            this.buttonList.remove(this.gameVersionFilterButton);
            this.gamesStaticWidgets.remove(this.gameVersionFilterButton);
        }
        int centerX = this.width / 2;
        int top = CONTENT_TOP + 4;
        this.gameVersionFilterButton = CycleTextButton.create(contentLeft() + contentWidth() - actionWidth(), top, actionWidth(), 20,
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
        String search = this.gameSearchBox == null ? initialGameSearch : this.gameSearchBox.getText().trim().toLowerCase(Locale.ROOT);
        String snapshot = search + "\n" + this.selectedVersionFilter;
        if (!snapshot.equals(gameFilterSnapshot)) { gamesScroll = 0; gameFilterSnapshot = snapshot; }
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
        gamesScroll = Math.max(0, Math.min(gamesScroll, gamesMaxScroll()));
        int shown = Math.min(this.filteredGames.size() - gamesScroll, rowsFitting(top, statusLineY() - 4));
        boolean gamesTab = this.currentTab == Tab.GAMES;
        for (int i = 0; i < shown; i++) {
            AccountClient.PublicGameInfo game = this.filteredGames.get(gamesScroll + i);
            IdButton joinButton = IdButton.builder(PeerCraftLang.tr("peercraft.gui.multiplayer.connect"), () -> onJoinGame(game))
                    .primary().bounds(contentLeft() + contentWidth() - actionWidth(), top + i * ROW_HEIGHT, actionWidth(), 18).build();
            joinButton.visible = gamesTab;
            this.buttonList.add(joinButton);
            this.gameRowWidgets.add(joinButton);
        }

        if (this.games.isEmpty()) {
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
        if (PeerCraftProgressNoticeScreen.beforeConnecting(this, () -> onJoinGame(game))) return;
        String label = blank(game.worldName()) ? game.code() : game.worldName();
        this.gamesStatusMessage = PeerCraftLang.tr("peercraft.gui.multiplayer.joining_game", label);
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
                                gamesStatusMessage = PeerCraftLang.tr(message);
                                gamesStatusColor = PeerCraftUi.TEXT_MUTED;
                            }
                        });
                    }

                    @Override
                    public void onConnected() {
                        dispatch(() -> {
                            if (stillOnThisScreen()) {
                                startVanillaConnect();
                            }
                        });
                    }

                    @Override
                    public void onFailed(String reason) {
                        dispatch(() -> {
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
        Minecraft.getMinecraft().func_152344_a(action);
    }

    private boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    private void drawScrollbar(int top, int total, int scroll) {
        int visible = rowsFitting(top, statusLineY() - 4);
        if (visible <= 0 || total <= visible) return;
        int trackHeight = visible * ROW_HEIGHT;
        int left = contentLeft() + contentWidth() + 3;
        drawRect(left, top, left + 3, top + trackHeight, 0xFF2C241B);
        int thumbHeight = Math.max(12, trackHeight * visible / total);
        int thumbY = top + (trackHeight - thumbHeight) * scroll / (total - visible);
        drawRect(left, thumbY, left + 3, thumbY + thumbHeight, 0xFFBB8B4B);
    }

    @Override
    public void drawCenteredString(net.minecraft.client.gui.FontRenderer font, String text, int x, int y, int color) {
        // GuiMultiplayer supplies a fixed title at y=20; our panel already owns the heading.
        if (drawingVanillaFavorites && y == 20 && x == this.width / 2) return;
        super.drawCenteredString(font, text, x, y, color);
    }

    @Override
    public void drawDefaultBackground() {
        if (panel != null) panel.background(this.fontRendererObj, this.width, this.height,
                Minecraft.getSystemTime(), false);
        else super.drawDefaultBackground();
    }

    /** Paint the native controls in place: vanilla retains their callbacks and enabled state. */
    private void drawNativeFooter(int mouseX, int mouseY) {
        for (GuiButton button : new GuiButton[]{vSelect, vDelete, vAdd, vDirect, vEdit, vRefresh, vBack}) {
            if (button == null || !button.visible) continue;
            boolean hover = mouseX >= button.xPosition && mouseX < button.xPosition + button.width
                    && mouseY >= button.yPosition && mouseY < button.yPosition + button.height;
            boolean primary = button == vSelect && button.enabled;
            int fill = !button.enabled ? SteampunkPalette.DISABLED
                    : primary ? (hover ? SteampunkPalette.PRIMARY_HOVER : SteampunkPalette.PRIMARY)
                    : hover ? SteampunkPalette.CONTROL_HOVER : SteampunkPalette.CONTROL;
            SteampunkDialog.frame(button.xPosition, button.yPosition, button.width, button.height, fill,
                    hover && button.enabled ? SteampunkPalette.BORDER_HOVER : SteampunkPalette.BORDER);
            String label = this.fontRendererObj.trimStringToWidth(button.displayString, Math.max(0, button.width - 12));
            if (primary) {
                this.fontRendererObj.drawString(label, button.xPosition + (button.width - this.fontRendererObj.getStringWidth(label)) / 2,
                        button.yPosition + (button.height - 8) / 2, SteampunkPalette.CONTROL);
            } else {
            drawCenteredString(this.fontRendererObj, label, button.xPosition + button.width / 2,
                    button.yPosition + (button.height - 8) / 2,
                    !button.enabled ? SteampunkPalette.MUTED : primary ? SteampunkPalette.CONTROL : SteampunkPalette.TEXT);
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int centerX = this.width / 2;
        if (this.currentTab == Tab.FAVORITES) {
            drawingVanillaFavorites = true;
            try { super.drawScreen(mouseX, mouseY, partialTicks); }
            finally { drawingVanillaFavorites = false; }
        } else {
            this.drawDefaultBackground();
            // 1.7.10 GuiButton#drawButton is (Minecraft, int, int) — the trailing float
            // partialTicks arg only arrived in 1.10. buttonList is a raw List here, hence the cast.
            for (int i = 0; i < this.buttonList.size(); i++) {
                ((GuiButton) this.buttonList.get(i)).drawButton(this.mc, mouseX, mouseY);
            }
        }

        drawNativeFooter(mouseX, mouseY);

        // tab bar sits above the content on every tab; drawn by the loop above / super.

        if (this.currentTab == Tab.FRIENDS) {
            this.addByCodeBox.drawTextBox();
            int top = friendsRowsTop();
            int shown = this.friends == null ? 0 : Math.min(this.friends.size() - friendsScroll, rowsFitting(top, statusLineY() - 4));
            for (int i = 0; i < shown; i++) {
                AccountClient.FriendInfo friend = this.friends.get(friendsScroll + i);
                int rowY = top + i * ROW_HEIGHT + 1;
                int afterBadgeX = PeerCraftUi.drawNameWithBadge(this.fontRendererObj, this.fontRendererObj.trimStringToWidth(friend.displayName(), Math.max(16, contentWidth() - actionWidth() - 88)), friend.licensed(), contentLeft(), rowY, PeerCraftUi.TEXT_TITLE);
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
                this.fontRendererObj.drawString(this.fontRendererObj.trimStringToWidth(status, Math.max(16, contentWidth() - actionWidth() - 74)), contentLeft(), rowY + 9, statusColor);
            }
            drawScrollbar(top, friends == null ? 0 : friends.size(), friendsScroll);
            if (!this.friendsStatusMessage.isEmpty()) {
                this.drawCenteredString(this.fontRendererObj, this.friendsStatusMessage, centerX, statusLineY(), this.friendsStatusColor);
            }
        } else if (this.currentTab == Tab.DISCOVER) {
            this.searchQueryBox.drawTextBox();
            int top = discoverResultsTop();
            int shown = Math.min(discoverVisibleRows(), this.searchResults.size() - this.discoverScroll);
            for (int i = 0; i < shown; i++) {
                AccountClient.SearchResult result = this.searchResults.get(this.discoverScroll + i);
                PeerCraftUi.drawNameWithBadge(this.fontRendererObj, this.fontRendererObj.trimStringToWidth(result.displayName(), Math.max(16, contentWidth() - actionWidth() - 22)), result.licensed(), contentLeft(), top + i * ROW_HEIGHT + 5, PeerCraftUi.TEXT_TITLE);
            }
            drawScrollbar(top, searchResults.size(), discoverScroll);
            if (!this.discoverStatusMessage.isEmpty()) {
                this.drawCenteredString(this.fontRendererObj, this.discoverStatusMessage, centerX, statusLineY(), this.discoverStatusColor);
            }
        } else if (this.currentTab == Tab.GAMES) {
            this.gameSearchBox.drawTextBox();
            int top = CONTENT_TOP + 32;
            gamesScroll = Math.max(0, Math.min(gamesScroll, gamesMaxScroll()));
            int shown = Math.min(this.filteredGames.size() - gamesScroll, rowsFitting(top, statusLineY() - 4));
            for (int i = 0; i < shown; i++) {
                AccountClient.PublicGameInfo game = this.filteredGames.get(gamesScroll + i);
                int rowY = top + i * ROW_HEIGHT + 5;
                String hostName = blank(game.hostDisplayName())
                        ? PeerCraftLang.tr("peercraft.gui.multiplayer.anonymous_host")
                        : game.hostDisplayName();
                String worldName = blank(game.worldName()) ? game.code() : game.worldName();
                String versionSuffix = blank(game.mcVersion()) ? "" : " [" + game.mcVersion() + "]";
                String line = worldName + " — " + hostName + " (" + game.currentPlayerCount() + "/" + game.maxPlayers() + ")" + versionSuffix;
                this.fontRendererObj.drawString(this.fontRendererObj.trimStringToWidth(line, contentWidth() - actionWidth() - 8), contentLeft(), rowY, PeerCraftUi.TEXT_TITLE);
            }
            drawScrollbar(top, filteredGames.size(), gamesScroll);
            if (!this.gamesStatusMessage.isEmpty()) {
                this.drawCenteredString(this.fontRendererObj, this.gamesStatusMessage, centerX, statusLineY(), this.gamesStatusColor);
            }
        }
            for (GuiButton glyph : new GuiButton[]{feedbackGlyph, donateGlyph}) {
            if (glyph != null && glyph.visible && mouseX >= glyph.xPosition && mouseX < glyph.xPosition + glyph.width
                    && mouseY >= glyph.yPosition && mouseY < glyph.yPosition + glyph.height) {
                func_146283_a(java.util.Collections.singletonList(PeerCraftLang.tr(glyph == feedbackGlyph
                        ? "peercraft.gui.title.feedback_button" : "peercraft.gui.title.donate_button")), mouseX, mouseY);
            }
        }
}
}
