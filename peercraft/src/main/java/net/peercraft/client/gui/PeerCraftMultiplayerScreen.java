package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.peercraft.client.modsync.ClientModSyncAgent;
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

    private enum Tab { FAVORITES, FRIENDS, DISCOVER, GAMES }

    private static final int CONTENT_TOP = 58;
    private static final int MAX_ROWS_SHOWN = 6;
    private static final int ROW_HEIGHT = 20;

    /** Opens the user's mail client with the recipient/subject pre-filled; no in-game form, no mail credentials shipped in the mod. */
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

    // ---- favorites tab: vanilla widgets, captured by call order during super.init() ----
    private boolean peercraft$capturingFavorites;
    private final List<AbstractWidget> favoritesWidgets = new ArrayList<>();
    private Button customRefreshButton;
    // Anchors for the widgets PeerCraft adds around the vanilla footer. On 1.21.9+ the vanilla
    // JoinMultiplayerScreen.repositionElements() only re-arranges its own HeaderAndFooterLayout
    // on a resize/GUI-scale change and never rebuilds — so these custom widgets have to be
    // re-placed by hand against the vanilla buttons' fresh positions. See
    // peercraft$repositionCustomWidgets().
    private Button vanillaRefreshButton;
    private Button vanillaBackButton;
    private Button accountGlyphButton;
    private Button settingsGlyphButton;
    private Button joinByCodeGlyphButton;
    private Button feedbackButton;
    private Button donateButton;
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
    // Index of the first search result row currently shown. The result list can be longer than
    // the MAX_ROWS_SHOWN rows that fit — the mouse wheel (see mouseScrolled) walks this offset
    // and a scrollbar drawn down the right edge of the results area shows where it sits.
    private int discoverScroll;

    // ---- games (public game browser, Phase 7) tab ----
    private final List<AbstractWidget> gamesStaticWidgets = new ArrayList<>();
    private final List<AbstractWidget> gameRowWidgets = new ArrayList<>();
    private List<AccountClient.PublicGameInfo> games;
    // Client-side filter over `games` (search box + version box below) — the browser is
    // capped at a small number of rooms server-side (RoomRegistry.MAX_LISTED_ROOMS), so
    // filtering what's already been fetched is simpler and more responsive than a round trip
    // per keystroke, and matches how the existing Discover tab's search already works.
    private List<AccountClient.PublicGameInfo> filteredGames = List.of();
    private EditBox gameSearchBox;
    // A CycleButton can't have its value list changed after creation, so this is torn down and
    // rebuilt (rebuildVersionFilterButton()) every time `games` refreshes with a new set of
    // versions. "" is the sentinel for "All" (no filter).
    private CycleButton<String> gameVersionFilterButton;
    private String selectedVersionFilter = "";
    private Component gamesStatusMessage = Component.empty();
    private int gamesStatusColor = PeerCraftUi.TEXT_MUTED;

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

    /**
     * "Feedback"/"Donate" — moved here (stacked in the bottom-left corner) from the title screen,
     * since every PeerCraft path already funnels through this screen. Not tab-gated, same as
     * Back/Account above: shown regardless of the currently selected tab.
     */
    private void peercraft$addFooterButtons() {
        this.feedbackButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.title.feedback_button"),
                        ConfirmLinkScreen.confirmLink(this, FEEDBACK_MAILTO))
                .bounds(2, 0, FOOTER_BUTTON_WIDTH, FOOTER_BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("peercraft.gui.title.feedback_tooltip")))
                .build());

        this.donateButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.title.donate_button"),
                        ConfirmLinkScreen.confirmLink(this, DONATE_URL))
                .bounds(2, 0, FOOTER_BUTTON_WIDTH, FOOTER_BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("peercraft.gui.title.donate_tooltip")))
                .build());

        peercraft$layoutFooterButtons();
    }

    /**
     * Feedback/Donate stack in the bottom-left corner. Vanilla's two footer button rows
     * (y = height-52 / height-28) reach left to width/2-154; when the window is wide enough to
     * leave a clear gutter there, sit in it as before, otherwise lift the stack above both rows
     * so it never lands on "Join Server" / "Edit". Split out of {@link #peercraft$addFooterButtons()}
     * so it can also be re-run on a resize — see {@link #peercraft$repositionCustomWidgets()}.
     */
    private void peercraft$layoutFooterButtons() {
        if (this.feedbackButton == null || this.donateButton == null) {
            return;
        }
        boolean gutterFits = this.width / 2 - 154 >= FOOTER_BUTTON_WIDTH + 6;
        int donateY = gutterFits ? this.height - 4 - FOOTER_BUTTON_HEIGHT
                                 : this.height - 52 - 6 - FOOTER_BUTTON_HEIGHT;
        int feedbackY = donateY - FOOTER_BUTTON_HEIGHT - 4;
        this.feedbackButton.setX(2);
        this.feedbackButton.setY(feedbackY);
        this.donateButton.setX(2);
        this.donateButton.setY(donateY);
    }

    /**
     * The list itself plus select/direct/add/edit/delete belong only to Favorites. Refresh
     * reopens {@code new JoinMultiplayerScreen(lastScreen)} from a private vanilla method we
     * can't override — replaced with our own that reopens this screen instead. Back
     * ({@code onClose()}) is left alone since it's public/overridable and already correct.
     */
    private void peercraft$stripFavoritesToOwnTab() {
        //? if <1.21.9 {
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
        //?} else {
        /*
        // 1.21.9 rebuilt this screen on the Layout system (HeaderAndFooterLayout) and now
        // also runs a title StringWidget through the same addRenderableWidget capture —
        // positional indices no longer line up with the old list. Match by button label
        // instead (same trick TitleScreenMixin uses for the Multiplayer button), so this
        // keeps working across future reshuffles of vanilla's widget order too.
        //
        // Compare via getString(), not Component equality: Button now extends
        // AbstractWidget.WithInactiveMessage, whose getMessage() returns a differently-styled
        // (greyed out) Component while the button is disabled — select/edit/delete all start
        // disabled here (nothing selected in the list yet), so a raw Component.equals() against
        // the plain translated label silently never matched them.
        String selectLabel = Component.translatable("selectServer.select").getString();
        String directLabel = Component.translatable("selectServer.direct").getString();
        String addLabel = Component.translatable("selectServer.add").getString();
        String editLabel = Component.translatable("selectServer.edit").getString();
        String deleteLabel = Component.translatable("selectServer.delete").getString();
        String refreshLabel = Component.translatable("selectServer.refresh").getString();
        String backLabel = Component.translatable("gui.back").getString();
        AbstractWidget list = null;
        Button select = null, direct = null, add = null, edit = null, delete = null, refresh = null, back = null;
        for (AbstractWidget w : this.favoritesWidgets) {
            if (w instanceof net.minecraft.client.gui.components.AbstractSelectionList) {
                list = w;
            } else if (w instanceof Button button) {
                String msg = button.getMessage().getString();
                if (selectLabel.equals(msg)) select = button;
                else if (directLabel.equals(msg)) direct = button;
                else if (addLabel.equals(msg)) add = button;
                else if (editLabel.equals(msg)) edit = button;
                else if (deleteLabel.equals(msg)) delete = button;
                else if (refreshLabel.equals(msg)) refresh = button;
                else if (backLabel.equals(msg)) back = button;
            }
        }
        this.favoritesSelectButton = select;
        this.favoritesDirectButton = direct;
        this.favoritesAddButton = add;
        this.favoritesEditButton = edit;
        this.favoritesDeleteButton = delete;
        Button vanillaRefresh = refresh;
        Button vanillaBack = back;
        */
        //?}

        this.favoritesList = list;
        list.setRectangle(this.width, this.height - 64 - CONTENT_TOP, 0, CONTENT_TOP);

        this.vanillaRefreshButton = vanillaRefresh;
        this.vanillaBackButton = vanillaBack;
        vanillaRefresh.visible = false;
        //? if <1.21.9 {
        this.favoritesWidgets.remove(7); // back — always visible, not tab-gated
        this.favoritesWidgets.remove(6); // vanilla refresh — permanently hidden, replaced below
        //?} else {
        /*this.favoritesWidgets.remove(vanillaBack); // back — always visible, not tab-gated
        this.favoritesWidgets.remove(vanillaRefresh); // vanilla refresh — permanently hidden, replaced below*/
        //?}

        this.customRefreshButton = this.addRenderableWidget(Button.builder(vanillaRefresh.getMessage(),
                        b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftMultiplayerScreen(this.lastScreen, this.currentTab)))
                .bounds(vanillaRefresh.getX(), vanillaRefresh.getY(), vanillaRefresh.getWidth(), vanillaRefresh.getHeight())
                .build());

        // Account button — used to be a full-width entry on the title screen; every PeerCraft
        // path already funnels through this screen (see TitleScreenMixin), so a small icon
        // tucked next to Back reaches it without competing for space with vanilla's own menu.
        // Not tab-gated, same as Back itself. Plain vanilla-styled glyph rather than a custom
        // face render, to stay visually consistent with the rest of the button row.
        this.accountGlyphButton = this.addRenderableWidget(PeerCraftUi.squareGlyphButton(
                vanillaBack.getX() + vanillaBack.getWidth() + 6, vanillaBack.getY(), vanillaBack.getHeight(),
                "☺", Component.translatable("peercraft.gui.multiplayer.account_tooltip").getString(), b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftAccountScreen(this))));

        // PeerCraft Settings — gear glyph right after the account button. All PeerCraft flags
        // (mod sync host/client modes, ports, rendezvous, …) are edited here and persisted to
        // config/peercraft/settings.json.
        this.settingsGlyphButton = this.addRenderableWidget(PeerCraftUi.squareGlyphButton(
                this.accountGlyphButton.getX() + this.accountGlyphButton.getWidth() + 6, vanillaBack.getY(), vanillaBack.getHeight(),
                "⚙", Component.translatable("peercraft.gui.settings.glyph_tooltip").getString(), b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftSettingsScreen(this))));

        // "Join by code" — same relocation, next to Add Server. Skipped in host mode
        // like the old title-screen button was: a host never needs to join someone else's room.
        if (!PeerCraftConfig.MODE_HOST.equals(PeerCraftConfig.mode())) {
            this.joinByCodeGlyphButton = this.addRenderableWidget(PeerCraftUi.squareGlyphButton(
                    this.favoritesAddButton.getX() + this.favoritesAddButton.getWidth() + 6, this.favoritesAddButton.getY(), this.favoritesAddButton.getHeight(),
                    "▶", Component.translatable("peercraft.gui.multiplayer.join_by_code_tooltip").getString(), b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftJoinScreen(this))));
        }
    }

    private void buildTabBar() {
        int barWidth = Math.min(this.width - 20, 460);
        int tabWidth = (barWidth - 12) / 4;
        int startX = this.width / 2 - barWidth / 2;
        int y = 30;

        this.tabFavoritesButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.multiplayer.tab_favorites"), b -> switchTab(Tab.FAVORITES))
                .bounds(startX, y, tabWidth, 20).build());
        this.tabFriendsButton = this.addRenderableWidget(Button.builder(friendsTabLabel(), b -> switchTab(Tab.FRIENDS))
                .bounds(startX + tabWidth + 4, y, tabWidth, 20).build());
        this.tabDiscoverButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.multiplayer.tab_discover"), b -> switchTab(Tab.DISCOVER))
                .bounds(startX + 2 * (tabWidth + 4), y, tabWidth, 20).build());
        this.tabGamesButton = this.addRenderableWidget(Button.builder(gamesTabLabel(), b -> switchTab(Tab.GAMES))
                .bounds(startX + 3 * (tabWidth + 4), y, tabWidth, 20).build());
    }

    private Component friendsTabLabel() {
        int count = this.friends == null ? 0 : this.friends.size();
        return Component.translatable("peercraft.gui.multiplayer.tab_friends", count);
    }

    private Component gamesTabLabel() {
        int count = this.games == null ? 0 : this.games.size();
        return Component.translatable("peercraft.gui.multiplayer.tab_games", count);
    }

    private void switchTab(Tab tab) {
        this.currentTab = tab;
        applyTabVisibility();
    }

    // ---- adaptive vertical layout --------------------------------------------------------
    // Tab content lives between the tab bar and a strip at the bottom kept for the persistent
    // Back / Refresh / account row. It is all derived from this.height so the Friends input
    // row and the Friends/Discover/Games result lists never ride onto that strip at a small
    // window size / high GUI scale — a fixed y=182 layout did (see the bug report).

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

    // ---- discover (find players) result scrolling ------------------------------------------

    /** Y of the first search-result row (names in render(), "Add friend" buttons in rebuildSearchResults()). */
    private int discoverResultsTop() {
        return CONTENT_TOP + 32;
    }

    /** How many result rows are on screen at once — same adaptive cap the Friends/Games lists use. */
    private int discoverVisibleRows() {
        return rowsFitting(discoverResultsTop(), statusLineY() - 4);
    }

    /** Largest valid {@link #discoverScroll}: 0 when every result already fits. */
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

    // mouseScrolled kept the (mouseX, mouseY, scrollX, scrollY) shape across every target here
    // (1.21.1 -> 26.2) — unlike keyPressed, it was not swept into the 1.21.9 input-event rewrite.
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.currentTab == Tab.DISCOVER && scrollY != 0 && discoverMaxScroll() > 0 && overDiscoverResults(mouseY)) {
            setDiscoverScroll(this.discoverScroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // Screen.init(int,int) only runs our full init() the first time a screen instance is
    // shown; on every later re-show of the SAME instance (e.g. "Back" from a screen that was
    // opened with this one as lastScreen), or on a window resize, it calls repositionElements()
    // instead. Vanilla's own override just re-lays-out the favorites list/layout — it knows
    // nothing about our tabs, so it silently undoes applyTabVisibility()'s off-screen trick for
    // the list, making it reappear behind whatever tab was actually selected. Reapplying our own
    // visibility state here keeps it in sync whenever vanilla decides to reposition instead of
    // fully reinit.
    //
    // Guarded on favoritesList being set: vanilla's own JoinMultiplayerScreen#init() calls
    // repositionElements() itself as its last step — which reenters here from inside our own
    // init()'s super.init() call, before peercraft$stripFavoritesToOwnTab() has populated any of
    // these fields yet. Skip in that case; init() runs applyTabVisibility() itself once it's
    // actually ready.
    @Override
    protected void repositionElements() {
        super.repositionElements();
        if (this.favoritesList != null) {
            peercraft$repositionCustomWidgets();
            applyTabVisibility();
        }
    }

    /**
     * Re-anchors every widget PeerCraft adds to this screen against the vanilla footer's current
     * position. On 1.21.9+ {@code super.repositionElements()} only calls {@code layout.arrangeElements()}
     * — it slides the vanilla footer row to the new width/height without rebuilding — so the custom
     * Refresh button, the account / join-by-code glyphs and the Feedback/Donate stack, all created
     * once in {@code init()} at fixed bounds, would otherwise float in mid-screen after a resize or
     * GUI-scale change (the reported bug). Below 1.21.9 {@code super.repositionElements()} is a full
     * {@code init()} rebuild, so this just re-sets already-correct positions and is a no-op.
     */
    private void peercraft$repositionCustomWidgets() {
        if (this.customRefreshButton != null && this.vanillaRefreshButton != null) {
            this.customRefreshButton.setX(this.vanillaRefreshButton.getX());
            this.customRefreshButton.setY(this.vanillaRefreshButton.getY());
        }
        if (this.accountGlyphButton != null && this.vanillaBackButton != null) {
            this.accountGlyphButton.setX(this.vanillaBackButton.getX() + this.vanillaBackButton.getWidth() + 6);
            this.accountGlyphButton.setY(this.vanillaBackButton.getY());
        }
        if (this.settingsGlyphButton != null && this.accountGlyphButton != null) {
            this.settingsGlyphButton.setX(this.accountGlyphButton.getX() + this.accountGlyphButton.getWidth() + 6);
            this.settingsGlyphButton.setY(this.accountGlyphButton.getY());
        }
        if (this.joinByCodeGlyphButton != null && this.favoritesAddButton != null) {
            this.joinByCodeGlyphButton.setX(this.favoritesAddButton.getX() + this.favoritesAddButton.getWidth() + 6);
            this.joinByCodeGlyphButton.setY(this.favoritesAddButton.getY());
        }
        peercraft$layoutFooterButtons();
    }

    private void applyTabVisibility() {
        boolean fav = this.currentTab == Tab.FAVORITES;
        boolean fr = this.currentTab == Tab.FRIENDS;
        boolean disc = this.currentTab == Tab.DISCOVER;
        boolean games = this.currentTab == Tab.GAMES;

        // Join Server/Direct Connection/Add Server/Edit/Delete stay visible on every tab —
        // just disabled (greyed) off Favorites, the same "nothing selected" look vanilla uses
        // there, so the footer doesn't change shape when switching tabs. The list itself is
        // still moved off-screen below regardless of this loop — see its field comment.
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
        for (AbstractWidget w : this.gamesStaticWidgets) w.visible = games;
        for (AbstractWidget w : this.gameRowWidgets) w.visible = games;
        this.tabFavoritesButton.active = !fav;
        this.tabFriendsButton.active = !fr;
        this.tabDiscoverButton.active = !disc;
        this.tabGamesButton.active = !games;

        // visible = true above doesn't stop the list from eating clicks — see the field comment.
        int listY = fav ? CONTENT_TOP : -30000;
        this.favoritesList.setRectangle(this.width, this.height - 64 - CONTENT_TOP, 0, listY);
    }

    // Screen.keyPressed switched from (int,int,int) to a KeyEvent record parameter in 1.21.9.
    //? if <1.21.9 {
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
    //?} else {
    /*
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (super.keyPressed(event)) {
            return true;
        }
        int keyCode = event.key();
    */
    //?}
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

        this.addByCodeBox = new EditBox(this.font, centerX - 200, rowsBottom + 8, 120, 20, Component.translatable("peercraft.gui.multiplayer.add_by_code_field"));
        this.addByCodeBox.setMaxLength(6);
        this.addByCodeBox.setHint(Component.translatable("peercraft.gui.multiplayer.add_by_code_field"));
        this.friendsStaticWidgets.add(this.addRenderableWidget(this.addByCodeBox));

        this.addByCodeButton = this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.multiplayer.add_by_code_button"), b -> onAddByCode())
                .bounds(centerX - 70, rowsBottom + 8, 150, 20).build());
        this.friendsStaticWidgets.add(this.addByCodeButton);

        this.friendsStaticWidgets.add(this.addRenderableWidget(Button.builder(Component.translatable("peercraft.gui.multiplayer.friend_requests_button"), b -> PeerCraftUi.setScreen(this.minecraft, new PeerCraftFriendRequestsScreen(this)))
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
        int shown = Math.min(this.friends.size(), rowsFitting(top, friendsInputTop() + 4));
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

        if (this.friends.size() > shown) {
            this.friendsStatusMessage = Component.translatable("peercraft.gui.common.shown_first", shown, this.friends.size());
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
        PeerCraftUi.setScreen(this.minecraft, new ConfirmScreen(confirmed -> {
            PeerCraftUi.setScreen(this.minecraft, this);
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
        String friendRoomCode = friend.roomCode();
        P2PBridge.INSTANCE.startClientViaRendezvous(friendRoomCode, PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(),
                new P2PBridge.ConnectListener() {
                    @Override
                    public void onStatus(String message) {
                        runOnClientThread(() -> {
                            if (stillOnThisScreen()) {
                                friendsStatusMessage = Component.translatable(message);
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
                                friendsStatusMessage = Component.translatable(reason);
                                friendsStatusColor = PeerCraftUi.TEXT_ERROR;
                            }
                        });
                    }
                }, new ClientModSyncAgent(this, friendRoomCode));
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
        // Player search is an account query — with no session AccountClient.searchAccounts()
        // just returns an empty list, which the "Nobody found" line makes indistinguishable
        // from a real no-match. Tell the player they need to log in instead.
        if (AccountClient.INSTANCE.getCurrentSession() == null) {
            this.discoverStatusMessage = Component.translatable("peercraft.gui.multiplayer.search_requires_login");
            this.discoverStatusColor = PeerCraftUi.TEXT_ERROR;
            return;
        }
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
                    discoverScroll = 0; // a fresh result set starts at the top
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
        int top = discoverResultsTop();
        boolean disc = this.currentTab == Tab.DISCOVER;
        this.discoverScroll = Math.max(0, Math.min(this.discoverScroll, discoverMaxScroll()));
        int shown = Math.min(discoverVisibleRows(), this.searchResults.size() - this.discoverScroll);
        for (int i = 0; i < shown; i++) {
            AccountClient.SearchResult result = this.searchResults.get(this.discoverScroll + i);
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

    // ==================== GAMES (public game browser, Phase 7) TAB ====================
    // No login/friendship required to browse or join — the whole point of this tab, unlike
    // Friends/Discover above which both need an account. See AccountClient.listPublicGames.

    private void buildGamesTab() {
        int centerX = this.width / 2;
        int top = CONTENT_TOP + 4;

        this.gameSearchBox = new EditBox(this.font, centerX - 200, top, 190, 20, Component.translatable("peercraft.gui.multiplayer.game_search_field"));
        this.gameSearchBox.setMaxLength(32);
        this.gameSearchBox.setHint(Component.translatable("peercraft.gui.multiplayer.game_search_hint"));
        this.gameSearchBox.setResponder(value -> rebuildGameRows());
        this.gamesStaticWidgets.add(this.addRenderableWidget(this.gameSearchBox));

        rebuildVersionFilterButton();

        rebuildGameRows();
        if (this.games == null) {
            loadGames();
        }
    }

    /** (Re)creates the version-filter CycleButton with the versions currently present in `games` — a CycleButton has no API to change its value list after creation, so this must be called every time `games` is refreshed with a possibly different set of versions (Phase 7.2). Keeps the current selection if it's still offered, otherwise resets to "All". */
    private void rebuildVersionFilterButton() {
        List<String> versions = new ArrayList<>();
        versions.add(""); // "All" sentinel, always first
        if (this.games != null) {
            this.games.stream()
                    .map(AccountClient.PublicGameInfo::mcVersion)
                    .filter(v -> !v.isBlank())
                    .distinct()
                    .sorted()
                    .forEach(versions::add);
        }
        if (!versions.contains(this.selectedVersionFilter)) {
            this.selectedVersionFilter = "";
        }

        if (this.gameVersionFilterButton != null) {
            this.removeWidget(this.gameVersionFilterButton);
            this.gamesStaticWidgets.remove(this.gameVersionFilterButton);
        }

        int centerX = this.width / 2;
        int top = CONTENT_TOP + 4;
        java.util.function.Function<String, Component> valueName = v ->
                v.isEmpty() ? Component.translatable("peercraft.gui.multiplayer.game_version_filter_all") : Component.literal(v);
        // CycleButton.builder(Function) lost its no-initial-value overload in 1.21.11 —
        // withInitialValue() is gone, the initial value is now a required constructor arg (same
        // quirk as ShareToLanScreenMixin's maxPlayersButton).
        //? if <1.21.11 {
        CycleButton.Builder<String> versionBuilder = CycleButton.<String>builder(valueName)
                .withInitialValue(this.selectedVersionFilter);
        //?} else {
        /*CycleButton.Builder<String> versionBuilder = CycleButton.<String>builder(valueName, this.selectedVersionFilter);*/
        //?}
        this.gameVersionFilterButton = versionBuilder
                .withValues(versions)
                .create(centerX + 10, top, 110, 20, Component.translatable("peercraft.gui.multiplayer.game_version_filter_field"),
                        (button, value) -> {
                            this.selectedVersionFilter = value;
                            rebuildGameRows();
                        });
        // A freshly created widget defaults to visible=true regardless of the tab that's
        // actually showing — this rebuild can be triggered by loadGames()'s async callback
        // landing after the player has already switched away from the Games tab, so unlike the
        // other games-tab widgets (only ever built once, in sync with applyTabVisibility()),
        // this one must set its own visibility rather than wait for the next tab switch.
        this.gameVersionFilterButton.visible = this.currentTab == Tab.GAMES;
        this.gamesStaticWidgets.add(this.addRenderableWidget(this.gameVersionFilterButton));
    }

    /** Applies gameSearchBox (world name substring, case-insensitive)/selectedVersionFilter (exact match) over the raw fetched list. */
    private void recomputeFilteredGames() {
        if (this.games == null) {
            this.filteredGames = List.of();
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
        this.gamesStatusMessage = Component.translatable("peercraft.gui.multiplayer.loading_games");
        this.gamesStatusColor = PeerCraftUi.TEXT_MUTED;
        AccountClient.INSTANCE.listPublicGames(new AccountClient.PublicGameListCallback() {
            @Override
            public void onResult(List<AccountClient.PublicGameInfo> result) {
                runOnClientThread(() -> {
                    if (!stillOnThisScreen()) {
                        return;
                    }
                    games = result;
                    gamesStatusMessage = Component.empty();
                    tabGamesButton.setMessage(gamesTabLabel());
                    rebuildVersionFilterButton();
                    rebuildGameRows();
                });
            }

            @Override
            public void onTimeout() {
                runOnClientThread(() -> {
                    if (stillOnThisScreen()) {
                        gamesStatusMessage = Component.translatable("peercraft.gui.common.account_server_timeout");
                        gamesStatusColor = PeerCraftUi.TEXT_ERROR;
                    }
                });
            }
        });
    }

    private void rebuildGameRows() {
        for (AbstractWidget w : this.gameRowWidgets) {
            this.removeWidget(w);
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

            Button joinButton = Button.builder(Component.translatable("peercraft.gui.multiplayer.connect"), b -> onJoinGame(game))
                    .bounds(centerX + 20, rowY, 110, 18).build();
            joinButton.visible = gamesTab;
            this.addRenderableWidget(joinButton);
            this.gameRowWidgets.add(joinButton);
        }

        if (this.filteredGames.size() > shown) {
            this.gamesStatusMessage = Component.translatable("peercraft.gui.common.shown_first", shown, this.filteredGames.size());
            this.gamesStatusColor = PeerCraftUi.TEXT_MUTED;
        } else if (this.games.isEmpty()) {
            this.gamesStatusMessage = Component.translatable("peercraft.gui.multiplayer.no_public_games");
            this.gamesStatusColor = PeerCraftUi.TEXT_MUTED;
        } else if (this.filteredGames.isEmpty()) {
            this.gamesStatusMessage = Component.translatable("peercraft.gui.multiplayer.no_games_match_filter");
            this.gamesStatusColor = PeerCraftUi.TEXT_MUTED;
        } else {
            this.gamesStatusMessage = Component.empty();
        }
    }

    /** Same join path as onConnectToFriend — the only difference is the code comes from a public listing entry instead of a friend's presence status. */
    private void onJoinGame(AccountClient.PublicGameInfo game) {
        String label = game.worldName().isBlank() ? game.code() : game.worldName();
        this.gamesStatusMessage = Component.translatable("peercraft.gui.multiplayer.joining_game", label);
        this.gamesStatusColor = PeerCraftUi.TEXT_MUTED;
        String gameRoomCode = game.code();
        P2PBridge.INSTANCE.startClientViaRendezvous(gameRoomCode, PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(),
                new P2PBridge.ConnectListener() {
                    @Override
                    public void onStatus(String message) {
                        runOnClientThread(() -> {
                            if (stillOnThisScreen()) {
                                gamesStatusMessage = Component.translatable(message);
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
                                gamesStatusMessage = Component.translatable(reason);
                                gamesStatusColor = PeerCraftUi.TEXT_ERROR;
                            }
                        });
                    }
                }, new ClientModSyncAgent(this, gameRoomCode));
    }

    // ==================== shared plumbing ====================

    @Override
    public void tick() {
        super.tick();
        if (this.friends == null && this.games == null) {
            return; // initial loads still in flight
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
                    // silent — keep showing the last known list rather than clearing it
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
                    // silent — keep showing the last known list rather than clearing it
                }
            });
        }
    }

    private void runOnClientThread(Runnable action) {
        Minecraft.getInstance().execute(action);
    }

    private boolean stillOnThisScreen() {
        return PeerCraftUi.isCurrentScreen(this);
    }

    // A plain vanilla-style scrollbar (black track + grey thumb, same as GuiSlot) down the right
    // edge of the Find Players result column, shown only when the results overflow the visible
    // rows. GuiGraphics#fill(x1,y1,x2,y2,argb) survived the 26.1 rename to GuiGraphicsExtractor
    // unchanged, so only the parameter type differs between the two copies below.
    //? if <26.1 {
    private void peercraft$drawDiscoverScrollbar(GuiGraphics graphics, int centerX) {
        int total = this.searchResults.size();
        int visible = discoverVisibleRows();
        if (visible <= 0 || total <= visible) {
            return;
        }
        int trackTop = discoverResultsTop();
        int trackHeight = visible * ROW_HEIGHT;
        int left = centerX + 202;
        int right = left + 6;
        graphics.fill(left, trackTop, right, trackTop + trackHeight, 0xFF000000);
        int maxScroll = total - visible;
        int scroll = Math.max(0, Math.min(this.discoverScroll, maxScroll));
        int thumbHeight = Math.max(16, trackHeight * visible / total);
        int thumbY = trackTop + (trackHeight - thumbHeight) * scroll / maxScroll;
        graphics.fill(left, thumbY, right, thumbY + thumbHeight, 0xFFA0A0A0);
    }
    //?} else {
    /*private void peercraft$drawDiscoverScrollbar(GuiGraphicsExtractor graphics, int centerX) {
        int total = this.searchResults.size();
        int visible = discoverVisibleRows();
        if (visible <= 0 || total <= visible) {
            return;
        }
        int trackTop = discoverResultsTop();
        int trackHeight = visible * ROW_HEIGHT;
        int left = centerX + 202;
        int right = left + 6;
        graphics.fill(left, trackTop, right, trackTop + trackHeight, 0xFF000000);
        int maxScroll = total - visible;
        int scroll = Math.max(0, Math.min(this.discoverScroll, maxScroll));
        int thumbHeight = Math.max(16, trackHeight * visible / total);
        int thumbY = trackTop + (trackHeight - thumbHeight) * scroll / maxScroll;
        graphics.fill(left, thumbY, right, thumbY + thumbHeight, 0xFFA0A0A0);
    }*/
    //?}

    // 26.1 renamed GuiGraphics -> GuiGraphicsExtractor and replaced Screen#render with
    // #extractRenderState; drawString/drawCenteredString became text/centeredText.
    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;

        if (this.currentTab == Tab.FRIENDS) {
            int top = CONTENT_TOP + 4;
            int shown = this.friends == null ? 0 : Math.min(this.friends.size(), rowsFitting(top, friendsInputTop() + 4));
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
            graphics.drawCenteredString(this.font, this.friendsStatusMessage, centerX, statusLineY(), this.friendsStatusColor);
        } else if (this.currentTab == Tab.DISCOVER) {
            int top = discoverResultsTop();
            int shown = Math.min(discoverVisibleRows(), this.searchResults.size() - this.discoverScroll);
            for (int i = 0; i < shown; i++) {
                AccountClient.SearchResult result = this.searchResults.get(this.discoverScroll + i);
                PeerCraftUi.drawNameWithBadge(graphics, this.font, result.displayName(), result.licensed(), centerX - 200, top + i * ROW_HEIGHT + 5, PeerCraftUi.TEXT_TITLE);
            }
            peercraft$drawDiscoverScrollbar(graphics, centerX);
            graphics.drawCenteredString(this.font, this.discoverStatusMessage, centerX, statusLineY(), this.discoverStatusColor);
        } else if (this.currentTab == Tab.GAMES) {
            int top = CONTENT_TOP + 32;
            int shown = Math.min(this.filteredGames.size(), rowsFitting(top, statusLineY() - 4));
            for (int i = 0; i < shown; i++) {
                AccountClient.PublicGameInfo game = this.filteredGames.get(i);
                int rowY = top + i * ROW_HEIGHT + 5;
                String hostName = game.hostDisplayName().isBlank()
                        ? Component.translatable("peercraft.gui.multiplayer.anonymous_host").getString()
                        : game.hostDisplayName();
                String worldName = game.worldName().isBlank() ? game.code() : game.worldName();
                String versionSuffix = game.mcVersion().isBlank() ? "" : " [" + game.mcVersion() + "]";
                String line = worldName + " — " + hostName + " (" + game.currentPlayerCount() + "/" + game.maxPlayers() + ")" + versionSuffix;
                graphics.drawString(this.font, line, centerX - 200, rowY, PeerCraftUi.TEXT_TITLE, false);
            }
            graphics.drawCenteredString(this.font, this.gamesStatusMessage, centerX, statusLineY(), this.gamesStatusColor);
        }
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;

        if (this.currentTab == Tab.FRIENDS) {
            int top = CONTENT_TOP + 4;
            int shown = this.friends == null ? 0 : Math.min(this.friends.size(), rowsFitting(top, friendsInputTop() + 4));
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
                graphics.text(this.font, " — " + status, afterBadgeX, rowY, statusColor, false);
            }
            graphics.centeredText(this.font, this.friendsStatusMessage, centerX, statusLineY(), this.friendsStatusColor);
        } else if (this.currentTab == Tab.DISCOVER) {
            int top = discoverResultsTop();
            int shown = Math.min(discoverVisibleRows(), this.searchResults.size() - this.discoverScroll);
            for (int i = 0; i < shown; i++) {
                AccountClient.SearchResult result = this.searchResults.get(this.discoverScroll + i);
                PeerCraftUi.drawNameWithBadge(graphics, this.font, result.displayName(), result.licensed(), centerX - 200, top + i * ROW_HEIGHT + 5, PeerCraftUi.TEXT_TITLE);
            }
            peercraft$drawDiscoverScrollbar(graphics, centerX);
            graphics.centeredText(this.font, this.discoverStatusMessage, centerX, statusLineY(), this.discoverStatusColor);
        } else if (this.currentTab == Tab.GAMES) {
            int top = CONTENT_TOP + 32;
            int shown = Math.min(this.filteredGames.size(), rowsFitting(top, statusLineY() - 4));
            for (int i = 0; i < shown; i++) {
                AccountClient.PublicGameInfo game = this.filteredGames.get(i);
                int rowY = top + i * ROW_HEIGHT + 5;
                String hostName = game.hostDisplayName().isBlank()
                        ? Component.translatable("peercraft.gui.multiplayer.anonymous_host").getString()
                        : game.hostDisplayName();
                String worldName = game.worldName().isBlank() ? game.code() : game.worldName();
                String versionSuffix = game.mcVersion().isBlank() ? "" : " [" + game.mcVersion() + "]";
                String line = worldName + " — " + hostName + " (" + game.currentPlayerCount() + "/" + game.maxPlayers() + ")" + versionSuffix;
                graphics.text(this.font, line, centerX - 200, rowY, PeerCraftUi.TEXT_TITLE, false);
            }
            graphics.centeredText(this.font, this.gamesStatusMessage, centerX, statusLineY(), this.gamesStatusColor);
        }
    }*/
    //?}
}
