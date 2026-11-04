package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.network.chat.TextComponent;
import net.peercraft.client.theme.SteampunkPalette;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.p2p.P2PBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Measured session information, with unavailable diagnostics explicitly identified. */
public final class PeerCraftSessionScreen extends Screen {
    private enum Page { OVERVIEW, NETWORK, WORLD, FILES, PLAYERS, HOSTS }
    private static final class Player {
        final String name; final int ping;
        Player(String name, int ping) { this.name = name; this.ping = ping; }
        String name() { return name; } int ping() { return ping; }
    }
    private final Screen parent;
    private Page page = Page.OVERVIEW;
    private int scroll;
    private int contentHeight;
    private final List<Button> contentButtons = new ArrayList<>();
    private final List<Integer> buttonOffsets = new ArrayList<>();
    private static final int AMBER = SteampunkPalette.ACCENT;

    public PeerCraftSessionScreen(Screen parent) {
        super(new TranslatableComponent("peercraft.session.title"));
        this.parent = parent;
    }
    private int panelWidth() { return Math.min(448, width - 16); }
    private int panelHeight() { return Math.min(368, height - 16); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - panelHeight()) / 2; }
    private int inner() { return panelWidth() - 28; }
    private int bodyTop() { return top() + 54; }
    private int bodyBottom() { return top() + panelHeight() - 36; }
    private String tr(String key, Object... args) { return new TranslatableComponent((key.startsWith("peercraft.") ? key : "peercraft.session." + key), args).getString(); }
    private void open(Page next) { page = next; scroll = 0; buttons.clear(); children.clear(); init(); }

    @Override protected void init() {
        contentButtons.clear(); buttonOffsets.clear();
        if (page == Page.OVERVIEW) {
            card(57, "network", networkSummary(), Page.NETWORK);
            card(101, "world", worldSummary(), Page.WORLD);
            card(145, "files", filesSummary(), Page.FILES);
            action(195, "players", () -> open(Page.PLAYERS));
            action(221, "hosts", () -> open(Page.HOSTS));
            contentHeight = 247;
            if (showHostControls()) {
                action(48, "handoff", () -> PeerCraftUi.setScreen(minecraft, parent instanceof HandoffPlayerPickerScreen ? parent : new HandoffPlayerPickerScreen(this)));
                contentButtons.get(contentButtons.size() - 1).active = canHandoff();
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
                action(contentHeight + 4, "handoff", () -> PeerCraftUi.setScreen(minecraft, parent instanceof HandoffPlayerPickerScreen ? parent : new HandoffPlayerPickerScreen(this)));
                contentButtons.get(contentButtons.size() - 1).active = canHandoff();
                contentHeight += 32;
            }
        }
        addButton(new SteampunkButton(left() + 14, top() + panelHeight() - 31, inner(), 20,
                new TranslatableComponent("gui.back"), b -> onClose()));
        updateScroll();
    }
    private boolean showHostControls() { return minecraft.getSingleplayerServer() != null; }
    private boolean canHandoff() { return PeerCraftConfig.handoff() && P2PBridge.INSTANCE.isHostingViaRendezvous() && !P2PBridge.INSTANCE.handoffInProgress(); }
    private int overviewOffset(int offset) { return page == Page.OVERVIEW && showHostControls() && offset >= 57 ? offset + 26 : offset; }
    private String overviewHint() {
        if (showHostControls() && !canHandoff()) return tr(!PeerCraftConfig.handoff() ? "metric.handoff_disabled" : P2PBridge.INSTANCE.handoffInProgress() ? "metric.handoff_busy" : "metric.handoff_needs_session");
        return tr(tickMillis() > 50 ? "world.advice" : "overview.hint");
    }
    private void card(int offset, String key, String summary, Page next) {
        offset = overviewOffset(offset);
        Button button = new SteampunkButton(left() + 14, bodyTop() + offset, inner(), 38,
                new TextComponent(""), b -> open(next));
        // Supply a meaningful label to the narrator even though the card draws two text lines.
        button.setMessage(new TextComponent(tr(key) + ": " + summary));
        contentButtons.add(addButton(button)); buttonOffsets.add(offset);
    }
    private void action(int offset, String key, Runnable action) {
        offset = overviewOffset(offset);
        contentButtons.add(addButton(new SteampunkButton(left() + 14, bodyTop() + offset, inner(), 20,
                new TranslatableComponent("peercraft.session." + key), b -> action.run())));
        buttonOffsets.add(offset);
    }
    private void updateScroll() {
        int viewport = Math.max(1, bodyBottom() - bodyTop());
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - viewport)));
        for (int i = 0; i < contentButtons.size(); i++) {
            Button b = contentButtons.get(i);
            b.y = bodyTop() + buttonOffsets.get(i) - scroll;
            b.visible = b.y >= bodyTop() && b.y + b.getHeight() <= bodyBottom();
        }
    }
    @Override public boolean mouseScrolled(double x, double y, double vertical) {
        if (x >= left() && x <= left() + panelWidth() && y >= bodyTop() && y <= bodyBottom() && vertical != 0) {
            scroll -= (int) Math.signum(vertical) * 26; updateScroll(); return true;
        }
        return super.mouseScrolled(x, y, vertical);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 266 || key == 267) {
            scroll += key == 266 ? -78 : 78; updateScroll(); return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
        private long nextRefresh;
    @Override public void tick() {
        if (page == Page.OVERVIEW || System.nanoTime() < nextRefresh) return;
        nextRefresh = System.nanoTime() + 1_000_000_000L;
        int needed = detailLines().size() * 13 + 12;
        if (page == Page.FILES || page == Page.HOSTS) needed += 28;
        if (page == Page.HOSTS && PeerCraftConfig.handoff() && P2PBridge.INSTANCE.isHostingViaRendezvous()) needed += 32;
        if (needed != contentHeight) { buttons.clear(); children.clear(); init(); }
        updateScroll();
    }
    @Override public void onClose() {
        if (page != Page.OVERVIEW) open(Page.OVERVIEW);
        else PeerCraftUi.setScreen(minecraft, parent);
    }
    // Keep the integrated server running while measuring it; pausing would distort tick data.
    @Override public boolean isPauseScreen() { return false; }

    private List<Player> players() {
        if (minecraft.getConnection() == null) return java.util.Collections.emptyList();
        return minecraft.getConnection().getOnlinePlayers().stream()
                .map(p -> new Player(p.getProfile().getName(), p.getLatency()))
                .sorted(java.util.Comparator.comparing(Player::name)).collect(java.util.stream.Collectors.toList());
    }
    private double tickMillis() {
        net.minecraft.client.server.IntegratedServer server = minecraft.getSingleplayerServer();
        return server == null ? -1 : server.getAverageTickTime();
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
        for (String line : raw) wrapped.addAll(PeerCraftUi.wrap(font, line, inner() - 16));
        return wrapped;
    }

    @Override public void renderBackground(PoseStack g) {
        if (minecraft.level != null) super.renderBackground(g);
        else GuiComponent.fill(g, 0, 0, width, height, SteampunkPalette.BACKGROUND);
        SteampunkDialog.frame(g, left(), top(), panelWidth(), panelHeight(), SteampunkPalette.PANEL, SteampunkPalette.BORDER);
    }
    @Override public void render(PoseStack g, int mouseX, int mouseY, float delta) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, delta);
        String heading = page == Page.OVERVIEW ? title.getString() : tr(page.name().toLowerCase(Locale.ROOT));
        font.drawShadow(g, font.plainSubstrByWidth(heading, inner()), left() + 16, top() + 13, SteampunkPalette.ACCENT);
        String subtitle = page == Page.OVERVIEW ? tr("subtitle") : title.getString();
        font.drawShadow(g, font.plainSubstrByWidth(subtitle, inner()), left() + 16, top() + 30, SteampunkPalette.MUTED);
        GuiComponent.fill(g, left() + 14, top() + 45, left() + panelWidth() - 14, top() + 46, SteampunkPalette.BORDER);
        clipBody();
        int x = left() + 24, y = bodyTop() - scroll;
        if (page == Page.OVERVIEW) {
            int color = tickMillis() > 50 || players().stream().anyMatch(p -> p.ping >= 150) ? AMBER : SteampunkPalette.TEXT;
            GuiComponent.fill(g, left() + 14, y, left() + 17, y + 42, color);
            font.drawShadow(g, status(), x, y + 5, color);
            List<String> hint = PeerCraftUi.wrap(font, overviewHint(), inner() - 20);
            for (int i = 0; i < Math.min(2, hint.size()); i++) font.drawShadow(g, hint.get(i), x, y + 20 + i * 11, SteampunkPalette.MUTED);
            drawCard(g, 57, "network", networkSummary());
            drawCard(g, 101, "world", worldSummary());
            drawCard(g, 145, "files", filesSummary());
        } else {
            List<String> lines = detailLines();
            for (int i = 0; i < lines.size(); i++) font.drawShadow(g, lines.get(i), left() + 18, y + 4 + i * 13,
                    i == 0 ? SteampunkPalette.ACCENT : SteampunkPalette.TEXT);
        }
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        int viewport = bodyBottom() - bodyTop();
        if (contentHeight > viewport) {
            int track = Math.max(1, viewport), thumb = Math.max(12, track * viewport / contentHeight);
            int thumbY = bodyTop() + (track - thumb) * scroll / Math.max(1, contentHeight - viewport);
            GuiComponent.fill(g, left() + panelWidth() - 9, bodyTop(), left() + panelWidth() - 7, bodyBottom(), SteampunkPalette.CONTROL);
            GuiComponent.fill(g, left() + panelWidth() - 9, thumbY, left() + panelWidth() - 7, thumbY + thumb, SteampunkPalette.ACCENT);
        }
    }
    private void drawCard(PoseStack g, int offset, String key, String value) {
        int y = bodyTop() + overviewOffset(offset) - scroll;
        if (y < bodyTop() || y + 38 > bodyBottom())
            SteampunkDialog.frame(g, left() + 14, y, inner(), 38, SteampunkPalette.CONTROL, SteampunkPalette.BORDER);
        // Cover the normal one-line button label with a two-line card, preserving hover/focus border.
        GuiComponent.fill(g, left() + 16, y + 2, left() + panelWidth() - 16, y + 36, SteampunkPalette.CONTROL);
        font.drawShadow(g, tr(key), left() + 24, y + 7, SteampunkPalette.TEXT);
        font.drawShadow(g, font.plainSubstrByWidth(value, inner() - 36), left() + 24, y + 22, SteampunkPalette.MUTED);
        font.drawShadow(g, ">", left() + panelWidth() - 30, y + 14, SteampunkPalette.ACCENT);
    }
    private void clipBody() {
        double scale = minecraft.getWindow().getGuiScale();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glScissor((int)((left() + 14) * scale), (int)(minecraft.getWindow().getHeight() - bodyBottom() * scale),
                Math.max(0, (int)(inner() * scale)), Math.max(0, (int)((bodyBottom() - bodyTop()) * scale)));
    }

}
