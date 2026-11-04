package net.peercraft.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.peercraft.client.theme.SteampunkPalette;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.p2p.P2PBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Measured session information, with unavailable diagnostics explicitly identified. */
public final class PeerCraftSessionScreen extends GuiScreen {
    private enum Page { OVERVIEW, NETWORK, WORLD, FILES, PLAYERS, HOSTS }
    private static final class Player {
        final String name; final int ping;
        Player(String name, int ping) { this.name = name; this.ping = ping; }
        String name() { return name; } int ping() { return ping; }
    }
    private final GuiScreen parent;
    private final String title = PeerCraftLang.tr("peercraft.session.title");
    private Page page = Page.OVERVIEW;
    private int scroll;
    private int contentHeight;
    private final List<GuiButton> contentButtons = new ArrayList<>();
    private final List<Integer> buttonOffsets = new ArrayList<>();
    private static final int AMBER = SteampunkPalette.ACCENT;

    public PeerCraftSessionScreen(GuiScreen parent) {
        this.parent = parent;
    }
    private int panelWidth() { return Math.min(448, width - 16); }
    private int panelHeight() { return Math.min(368, height - 16); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - panelHeight()) / 2; }
    private int inner() { return panelWidth() - 28; }
    private int bodyTop() { return top() + 54; }
    private int bodyBottom() { return top() + panelHeight() - 36; }
    private String tr(String key, Object... args) { return PeerCraftLang.tr((key.startsWith("peercraft.") ? key : "peercraft.session." + key), args); }
    private void open(Page next) { page = next; scroll = 0; buttonList.clear(); initGui(); }

    @Override public void initGui() {
        buttonList.clear(); contentButtons.clear(); buttonOffsets.clear();
        if (page == Page.OVERVIEW) {
            card(57, "network", networkSummary(), Page.NETWORK);
            card(101, "world", worldSummary(), Page.WORLD);
            card(145, "files", filesSummary(), Page.FILES);
            action(195, "players", () -> open(Page.PLAYERS));
            action(221, "hosts", () -> open(Page.HOSTS));
            contentHeight = 247;
            if (showHostControls()) {
                action(48, "handoff", () -> PeerCraftUi.setScreen(mc, parent instanceof HandoffPlayerPickerScreen ? parent : new HandoffPlayerPickerScreen(this)));
                contentButtons.get(contentButtons.size() - 1).enabled = canHandoff();
                contentHeight = 273;
            }
        } else {
            List<String> lines = detailLines();
            contentHeight = lines.size() * 13 + 12;
            if (page == Page.HOSTS) {
                action(contentHeight + 4, "metric.test_button", () -> P2PBridge.INSTANCE.sessionMonitor().requestComparison());
                contentHeight += 28;
            }
            if (page == Page.FILES) {
                action(contentHeight + 4, "metric.limit_button", () -> {
                    int current = net.peercraft.network.diagnostics.TransferTraffic.limitKiB();
                    net.peercraft.network.diagnostics.TransferTraffic.setLimitKiB(current == 0 ? 256 : current == 256 ? 1024 : 0);
                    open(Page.FILES);
                });
                contentHeight += 28;
            }
            if (page == Page.HOSTS && PeerCraftConfig.handoff()
                    && P2PBridge.INSTANCE.isHostingViaRendezvous()) {
                action(contentHeight + 4, "handoff", () -> PeerCraftUi.setScreen(mc, parent instanceof HandoffPlayerPickerScreen ? parent : new HandoffPlayerPickerScreen(this)));
                contentButtons.get(contentButtons.size() - 1).enabled = canHandoff();
                contentHeight += 32;
            }
        }
        addButton(IdButton.builder(PeerCraftLang.tr("peercraft.gui.common.back"), () -> onClose())
                .bounds(left() + 14, top() + panelHeight() - 31, inner(), 20).build());
        updateScroll();
    }
    private boolean showHostControls() { return mc.getIntegratedServer() != null; }
    private boolean canHandoff() { return PeerCraftConfig.handoff() && P2PBridge.INSTANCE.isHostingViaRendezvous() && !P2PBridge.INSTANCE.handoffInProgress(); }
    private int overviewOffset(int offset) { return page == Page.OVERVIEW && showHostControls() && offset >= 57 ? offset + 26 : offset; }
    private String overviewHint() {
        if (showHostControls() && !canHandoff()) return tr(!PeerCraftConfig.handoff() ? "metric.handoff_disabled" : P2PBridge.INSTANCE.handoffInProgress() ? "metric.handoff_busy" : "metric.handoff_needs_session");
        return tr(tickMillis() > 50 ? "world.advice" : "overview.hint");
    }
    private void card(int offset, String key, String summary, Page next) {
        offset = overviewOffset(offset);
        GuiButton button = IdButton.builder(tr(key) + ": " + summary, () -> open(next))
                .bounds(left() + 14, bodyTop() + offset, inner(), 38).build();
        contentButtons.add(addButton(button)); buttonOffsets.add(offset);
    }
    private void action(int offset, String key, Runnable action) {
        offset = overviewOffset(offset);
        contentButtons.add(addButton(IdButton.builder(tr(key), action)
                .bounds(left() + 14, bodyTop() + offset, inner(), 20).build()));
        buttonOffsets.add(offset);
    }
    private void updateScroll() {
        int viewport = Math.max(1, bodyBottom() - bodyTop());
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - viewport)));
        for (int i = 0; i < contentButtons.size(); i++) {
            GuiButton b = contentButtons.get(i);
            b.y = bodyTop() + buttonOffsets.get(i) - scroll;
            b.visible = b.y >= bodyTop() && b.y + b.height <= bodyBottom();
        }
    }
    @Override public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel != 0) { scroll -= Integer.signum(wheel) * 26; updateScroll(); }
    }
    @Override protected void keyTyped(char character, int key) throws java.io.IOException {
        if (key == 1) { onClose(); return; }
        if (key == 201 || key == 209) { scroll += key == 201 ? -78 : 78; updateScroll(); return; }
        super.keyTyped(character, key);
    }
    private long nextRefresh;
    @Override public void updateScreen() {
        if (page == Page.OVERVIEW || System.nanoTime() < nextRefresh) return;
        nextRefresh = System.nanoTime() + 1_000_000_000L;
        int needed = detailLines().size() * 13 + 12;
        if (page == Page.FILES || page == Page.HOSTS) needed += 28;
        if (page == Page.HOSTS && PeerCraftConfig.handoff() && P2PBridge.INSTANCE.isHostingViaRendezvous()) needed += 32;
        if (needed != contentHeight) { initGui(); }
        updateScroll();
    }
    public void onClose() {
        if (page != Page.OVERVIEW) open(Page.OVERVIEW);
        else PeerCraftUi.setScreen(mc, parent);
    }
    // Keep the integrated server running while measuring it; pausing would distort tick data.
    @Override public boolean doesGuiPauseGame() { return false; }

    private List<Player> players() {
        List<Player> result = new ArrayList<>();
        if (mc.getConnection() != null) for (net.minecraft.client.network.NetworkPlayerInfo info : mc.getConnection().getPlayerInfoMap())
            result.add(new Player(info.getGameProfile().getName(), info.getResponseTime()));
        java.util.Collections.sort(result, java.util.Comparator.comparing(Player::name));
        return result;
    }
    private double tickMillis() {
        net.minecraft.server.MinecraftServer server = mc.getIntegratedServer();
        if (server == null) return -1;
        long sum = 0; int count = 0;
        for (long tick : server.tickTimeArray) if (tick > 0) { sum += tick; count++; }
        return count == 0 ? -1 : sum / (double) count / 1_000_000.0;
    }
    private String worldSummary() {
        double tick = net.peercraft.client.theme.SessionPresentation.worldTick(P2PBridge.INSTANCE.sessionMonitor(), tickMillis());
        return tick < 0 ? tr("world.remote") : tr("world.value", String.format(Locale.ROOT, "%.1f", tick));
    }
    private String networkSummary() {
        List<Player> players = players();
        int max = players.stream().mapToInt(Player::ping).filter(p -> p >= 0).max().orElse(-1);
        return max < 0 ? tr("unavailable") : tr("network.value", max);
    }
    private String status() {
        return tr(net.peercraft.client.theme.SessionPresentation.diagnosis(P2PBridge.INSTANCE.sessionMonitor(),
                net.peercraft.client.theme.SessionPresentation.worldTick(P2PBridge.INSTANCE.sessionMonitor(), tickMillis())));
    }
    private String filesSummary() { return tr("metric.files_count", net.peercraft.client.theme.SessionPresentation.files(P2PBridge.INSTANCE.sessionMonitor())); }
    private List<String> detailLines() {
        List<String> raw = new ArrayList<>();
        if (page == Page.PLAYERS) {
            raw.add(tr("players.explain")); raw.add("");
            for (Player player : players()) raw.add(player.name + "    " + (player.ping < 0 ? "—" : tr("ping", player.ping)));
            if (players().isEmpty()) raw.add(tr("players.empty"));
        } else raw.addAll(net.peercraft.client.theme.SessionPresentation.lines(page.name(), P2PBridge.INSTANCE.sessionMonitor(), tickMillis(), this::tr));
        List<String> wrapped = new ArrayList<>();
        for (String line : raw) wrapped.addAll(PeerCraftUi.wrap(fontRenderer, line, inner() - 16));
        return wrapped;
    }

    private void panelBackground() {
        if (mc.world != null) super.drawDefaultBackground();
        else net.minecraft.client.gui.Gui.drawRect(0, 0, width, height, SteampunkPalette.BACKGROUND);
        SteampunkDialog.frame(left(), top(), panelWidth(), panelHeight(), SteampunkPalette.PANEL, SteampunkPalette.BORDER);
    }
    @Override public void drawScreen(int mouseX, int mouseY, float delta) {
        panelBackground();
        super.drawScreen(mouseX, mouseY, delta);
        String heading = page == Page.OVERVIEW ? title : tr(page.name().toLowerCase(Locale.ROOT));
        fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(heading, inner()), left() + 16, top() + 13, SteampunkPalette.ACCENT);
        String subtitle = page == Page.OVERVIEW ? tr("subtitle") : title;
        fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(subtitle, inner()), left() + 16, top() + 30, SteampunkPalette.MUTED);
        net.minecraft.client.gui.Gui.drawRect(left() + 14, top() + 45, left() + panelWidth() - 14, top() + 46, SteampunkPalette.BORDER);
        clipBody();
        int x = left() + 24, y = bodyTop() - scroll;
        if (page == Page.OVERVIEW) {
            int color = tickMillis() > 50 || players().stream().anyMatch(p -> p.ping >= 150) ? AMBER : SteampunkPalette.TEXT;
            net.minecraft.client.gui.Gui.drawRect(left() + 14, y, left() + 17, y + 42, color);
            fontRenderer.drawStringWithShadow(status(), x, y + 5, color);
            List<String> hint = PeerCraftUi.wrap(fontRenderer, overviewHint(), inner() - 20);
            for (int i = 0; i < Math.min(2, hint.size()); i++) fontRenderer.drawStringWithShadow(hint.get(i), x, y + 20 + i * 11, SteampunkPalette.MUTED);
            drawCard(57, "network", networkSummary());
            drawCard(101, "world", worldSummary());
            drawCard(145, "files", tr("unavailable"));
        } else {
            List<String> lines = detailLines();
            for (int i = 0; i < lines.size(); i++) fontRenderer.drawStringWithShadow(lines.get(i), left() + 18, y + 4 + i * 13,
                    i == 0 ? SteampunkPalette.ACCENT : SteampunkPalette.TEXT);
        }
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        int viewport = bodyBottom() - bodyTop();
        if (contentHeight > viewport) {
            int track = Math.max(1, viewport), thumb = Math.max(12, track * viewport / contentHeight);
            int thumbY = bodyTop() + (track - thumb) * scroll / Math.max(1, contentHeight - viewport);
            net.minecraft.client.gui.Gui.drawRect(left() + panelWidth() - 9, bodyTop(), left() + panelWidth() - 7, bodyBottom(), SteampunkPalette.CONTROL);
            net.minecraft.client.gui.Gui.drawRect(left() + panelWidth() - 9, thumbY, left() + panelWidth() - 7, thumbY + thumb, SteampunkPalette.ACCENT);
        }
    }
    private void drawCard(int offset, String key, String value) {
        int y = bodyTop() + overviewOffset(offset) - scroll;
        if (y < bodyTop() || y + 38 > bodyBottom())
            SteampunkDialog.frame(left() + 14, y, inner(), 38, SteampunkPalette.CONTROL, SteampunkPalette.BORDER);
        // Cover the normal one-line button label with a two-line card, preserving hover/focus border.
        net.minecraft.client.gui.Gui.drawRect(left() + 16, y + 2, left() + panelWidth() - 16, y + 36, SteampunkPalette.CONTROL);
        fontRenderer.drawStringWithShadow(tr(key), left() + 24, y + 7, SteampunkPalette.TEXT);
        fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(value, inner() - 36), left() + 24, y + 22, SteampunkPalette.MUTED);
        fontRenderer.drawStringWithShadow(">", left() + panelWidth() - 30, y + 14, SteampunkPalette.ACCENT);
    }
    private void clipBody() {
        double scale = new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glScissor((int)((left() + 14) * scale), (int)(mc.displayHeight - bodyBottom() * scale),
                Math.max(0, (int)(inner() * scale)), Math.max(0, (int)((bodyBottom() - bodyTop()) * scale)));
    }

    @Override protected void actionPerformed(GuiButton button) {
        if (button instanceof IdButton) ((IdButton)button).onPress.run();
    }

}
