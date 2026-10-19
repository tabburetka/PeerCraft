package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.theme.SteampunkPalette;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.p2p.P2PBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Measured session information, with unavailable diagnostics explicitly identified. */
public final class PeerCraftSessionScreen extends Screen {
    private enum Page { OVERVIEW, NETWORK, WORLD, FILES, PLAYERS, HOSTS }
    private record Player(String name, int ping) { }
    private final Screen parent;
    private Page page = Page.OVERVIEW;
    private int scroll;
    private int contentHeight;
    private final List<Button> contentButtons = new ArrayList<>();
    private final List<Integer> buttonOffsets = new ArrayList<>();
    private static final int AMBER = SteampunkPalette.ACCENT;

    public PeerCraftSessionScreen(Screen parent) {
        super(Component.translatable("peercraft.session.title"));
        this.parent = parent;
    }
    private int panelWidth() { return Math.min(448, width - 16); }
    private int panelHeight() { return Math.min(368, height - 16); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - panelHeight()) / 2; }
    private int inner() { return panelWidth() - 28; }
    private int bodyTop() { return top() + 54; }
    private int bodyBottom() { return top() + panelHeight() - 36; }
    private String tr(String key, Object... args) { return Component.translatable("peercraft.session." + key, args).getString(); }
    private void open(Page next) { page = next; scroll = 0; rebuildWidgets(); }

    @Override protected void init() {
        contentButtons.clear(); buttonOffsets.clear();
        if (page == Page.OVERVIEW) {
            card(57, "network", networkSummary(), Page.NETWORK);
            card(101, "world", worldSummary(), Page.WORLD);
            card(145, "files", tr("unavailable"), Page.FILES);
            action(195, "players", () -> open(Page.PLAYERS));
            action(221, "hosts", () -> open(Page.HOSTS));
            contentHeight = 247;
        } else {
            List<String> lines = detailLines();
            contentHeight = lines.size() * 13 + 12;
            if (page == Page.HOSTS && PeerCraftConfig.handoff()
                    && P2PBridge.INSTANCE.isHostingViaRendezvous()) {
                action(contentHeight + 4, "handoff", () -> PeerCraftUi.setScreen(minecraft, new HandoffPlayerPickerScreen(this)));
                contentButtons.getLast().active = !P2PBridge.INSTANCE.handoffInProgress();
                contentHeight += 32;
            }
        }
        addRenderableWidget(SteampunkSettingsTheme.action(left() + 14, top() + panelHeight() - 31, inner(), 20,
                Component.translatable("gui.back"), b -> onClose(), false));
        updateScroll();
    }
    private void card(int offset, String key, String summary, Page next) {
        Button button = SteampunkSettingsTheme.action(left() + 14, bodyTop() + offset, inner(), 38,
                Component.empty(), b -> open(next), false);
        // Supply a meaningful label to the narrator even though the card draws two text lines.
        button.setMessage(Component.literal(tr(key) + ": " + summary));
        contentButtons.add(addRenderableWidget(button)); buttonOffsets.add(offset);
    }
    private void action(int offset, String key, Runnable action) {
        contentButtons.add(addRenderableWidget(SteampunkSettingsTheme.action(left() + 14, bodyTop() + offset, inner(), 20,
                Component.translatable("peercraft.session." + key), b -> action.run(), false)));
        buttonOffsets.add(offset);
    }
    private void updateScroll() {
        int viewport = Math.max(1, bodyBottom() - bodyTop());
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - viewport)));
        for (int i = 0; i < contentButtons.size(); i++) {
            Button b = contentButtons.get(i);
            b.setY(bodyTop() + buttonOffsets.get(i) - scroll);
            b.visible = b.getY() >= bodyTop() && b.getY() + b.getHeight() <= bodyBottom();
        }
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x >= left() && x <= left() + panelWidth() && y >= bodyTop() && y <= bodyBottom() && vertical != 0) {
            scroll -= (int) Math.signum(vertical) * 26; updateScroll(); return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    //? if <1.21.9 {
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 266 || key == 267) {
            scroll += key == 266 ? -78 : 78; updateScroll(); return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
    //?} else {
    /*    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == 266 || event.key() == 267) {
            scroll += event.key() == 266 ? -78 : 78; updateScroll(); return true;
        }
        return super.keyPressed(event);
    }
*/
    //?}
    @Override public void tick() {
        if (page != Page.OVERVIEW && page != Page.HOSTS) {
            contentHeight = detailLines().size() * 13 + 12;
            updateScroll();
        }
    }
    @Override public void onClose() {
        if (page != Page.OVERVIEW) open(Page.OVERVIEW);
        else PeerCraftUi.setScreen(minecraft, parent);
    }
    // Keep the integrated server running while measuring it; pausing would distort tick data.
    @Override public boolean isPauseScreen() { return false; }

    private List<Player> players() {
        if (minecraft.getConnection() == null) return List.of();
        return minecraft.getConnection().getOnlinePlayers().stream()
                //? if <1.21.9
                .map(p -> new Player(p.getProfile().getName(), p.getLatency()))
                //? if >=1.21.9
                /*.map(p -> new Player(p.getProfile().name(), p.getLatency()))*/
                .sorted(java.util.Comparator.comparing(Player::name)).toList();
    }
    private double tickMillis() {
        var server = minecraft.getSingleplayerServer();
        return server == null ? -1 : server.getAverageTickTimeNanos() / 1_000_000.0;
    }
    private String worldSummary() {
        double tick = tickMillis();
        return tick < 0 ? tr("world.remote") : tr("world.value", String.format(Locale.ROOT, "%.1f", tick));
    }
    private String networkSummary() {
        var players = players();
        int max = players.stream().mapToInt(Player::ping).filter(p -> p >= 0).max().orElse(-1);
        return max < 0 ? tr("unavailable") : tr("network.value", max);
    }
    private String status() {
        if (tickMillis() > 50) return tr("status.world");
        if (players().stream().anyMatch(p -> p.ping >= 150)) return tr("status.network");
        return tr("status.measuring");
    }
    private List<String> detailLines() {
        List<String> raw = new ArrayList<>();
        switch (page) {
            case NETWORK -> {
                raw.add(networkSummary()); raw.add("");
                raw.add(tr("network.source")); raw.add(tr("network.unknown")); raw.add("");
                raw.add(tr("network.advice"));
            }
            case WORLD -> {
                raw.add(worldSummary()); raw.add(""); raw.add(tr("world.explain"));
                raw.add(tr(tickMillis() > 50 ? "world.advice" : "world.no_advice"));
                if (tickMillis() < 0) raw.add(tr("world.remote_detail"));
            }
            case FILES -> {
                raw.add(tr("files.unknown")); raw.add("");
                raw.add(tr("files.explain")); raw.add(tr("files.advice"));
            }
            case PLAYERS -> {
                raw.add(tr("players.explain")); raw.add("");
                for (Player player : players()) raw.add(player.name + "    " + (player.ping < 0 ? "—" : tr("ping", player.ping)));
                if (players().isEmpty()) raw.add(tr("players.empty"));
            }
            case HOSTS -> {
                raw.add(tr("hosts.explain")); raw.add(""); raw.add(tr("hosts.unavailable"));
                raw.add(tr("hosts.world")); raw.add(""); raw.add(tr("hosts.manual"));
            }
            default -> { }
        }
        List<String> wrapped = new ArrayList<>();
        for (String line : raw) wrapped.addAll(PeerCraftUi.wrap(font, line, inner() - 16));
        return wrapped;
    }

    //? if <26.1 {
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (minecraft.level == null) {
            SteampunkSettingsTheme.screenBackground(g, width, height, left(), top(), panelWidth(), panelHeight(), 1000);
        } else {
            super.renderBackground(g, mouseX, mouseY, delta);
            SteampunkSettingsTheme.frame(g, left(), top(), panelWidth(), panelHeight(), SteampunkPalette.PANEL, SteampunkPalette.BORDER);
        }
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        //? if <1.21.6
        renderBackground(g, mouseX, mouseY, delta);
        super.render(g, mouseX, mouseY, delta);
        String heading = page == Page.OVERVIEW ? title.getString() : tr(page.name().toLowerCase(Locale.ROOT));
        g.drawString(font, font.plainSubstrByWidth(heading, inner()), left() + 16, top() + 13, SteampunkPalette.ACCENT);
        String subtitle = page == Page.OVERVIEW ? tr("subtitle") : title.getString();
        g.drawString(font, font.plainSubstrByWidth(subtitle, inner()), left() + 16, top() + 30, SteampunkPalette.MUTED);
        g.fill(left() + 14, top() + 45, left() + panelWidth() - 14, top() + 46, SteampunkPalette.BORDER);
        g.enableScissor(left() + 14, bodyTop(), left() + panelWidth() - 14, bodyBottom());
        int x = left() + 24, y = bodyTop() - scroll;
        if (page == Page.OVERVIEW) {
            int color = tickMillis() > 50 || players().stream().anyMatch(p -> p.ping >= 150) ? AMBER : SteampunkPalette.TEXT;
            g.fill(left() + 14, y, left() + 17, y + 42, color);
            g.drawString(font, status(), x, y + 5, color);
            var hint = PeerCraftUi.wrap(font, tr(tickMillis() > 50 ? "world.advice" : "overview.hint"), inner() - 20);
            for (int i = 0; i < Math.min(2, hint.size()); i++) g.drawString(font, hint.get(i), x, y + 20 + i * 11, SteampunkPalette.MUTED);
            drawCard(g, 57, "network", networkSummary());
            drawCard(g, 101, "world", worldSummary());
            drawCard(g, 145, "files", tr("unavailable"));
        } else {
            List<String> lines = detailLines();
            for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), left() + 18, y + 4 + i * 13,
                    i == 0 ? SteampunkPalette.ACCENT : SteampunkPalette.TEXT);
        }
        g.disableScissor();
        int viewport = bodyBottom() - bodyTop();
        if (contentHeight > viewport) {
            int track = Math.max(1, viewport), thumb = Math.max(12, track * viewport / contentHeight);
            int thumbY = bodyTop() + (track - thumb) * scroll / Math.max(1, contentHeight - viewport);
            g.fill(left() + panelWidth() - 9, bodyTop(), left() + panelWidth() - 7, bodyBottom(), SteampunkPalette.CONTROL);
            g.fill(left() + panelWidth() - 9, thumbY, left() + panelWidth() - 7, thumbY + thumb, SteampunkPalette.ACCENT);
        }
    }
    private void drawCard(GuiGraphics g, int offset, String key, String value) {
        int y = bodyTop() + offset - scroll;
        if (y < bodyTop() || y + 38 > bodyBottom())
            SteampunkSettingsTheme.frame(g, left() + 14, y, inner(), 38, SteampunkPalette.CONTROL, SteampunkPalette.BORDER);
        // Cover the normal one-line button label with a two-line card, preserving hover/focus border.
        g.fill(left() + 16, y + 2, left() + panelWidth() - 16, y + 36, SteampunkPalette.CONTROL);
        g.drawString(font, tr(key), left() + 24, y + 7, SteampunkPalette.TEXT);
        g.drawString(font, font.plainSubstrByWidth(value, inner() - 36), left() + 24, y + 22, SteampunkPalette.MUTED);
        g.drawString(font, ">", left() + panelWidth() - 30, y + 14, SteampunkPalette.ACCENT);
    }
    //?} else {
    /*    @Override public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (minecraft.level == null) {
            SteampunkSettingsTheme.screenBackground(g, width, height, left(), top(), panelWidth(), panelHeight(), 1000);
        } else {
            super.extractBackground(g, mouseX, mouseY, delta);
            SteampunkSettingsTheme.frame(g, left(), top(), panelWidth(), panelHeight(), SteampunkPalette.PANEL, SteampunkPalette.BORDER);
        }
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        String heading = page == Page.OVERVIEW ? title.getString() : tr(page.name().toLowerCase(Locale.ROOT));
        g.text(font, font.plainSubstrByWidth(heading, inner()), left() + 16, top() + 13, SteampunkPalette.ACCENT);
        String subtitle = page == Page.OVERVIEW ? tr("subtitle") : title.getString();
        g.text(font, font.plainSubstrByWidth(subtitle, inner()), left() + 16, top() + 30, SteampunkPalette.MUTED);
        g.fill(left() + 14, top() + 45, left() + panelWidth() - 14, top() + 46, SteampunkPalette.BORDER);
        g.enableScissor(left() + 14, bodyTop(), left() + panelWidth() - 14, bodyBottom());
        int x = left() + 24, y = bodyTop() - scroll;
        if (page == Page.OVERVIEW) {
            int color = tickMillis() > 50 || players().stream().anyMatch(p -> p.ping >= 150) ? AMBER : SteampunkPalette.TEXT;
            g.fill(left() + 14, y, left() + 17, y + 42, color);
            g.text(font, status(), x, y + 5, color);
            var hint = PeerCraftUi.wrap(font, tr(tickMillis() > 50 ? "world.advice" : "overview.hint"), inner() - 20);
            for (int i = 0; i < Math.min(2, hint.size()); i++) g.text(font, hint.get(i), x, y + 20 + i * 11, SteampunkPalette.MUTED);
            drawCard(g, 57, "network", networkSummary());
            drawCard(g, 101, "world", worldSummary());
            drawCard(g, 145, "files", tr("unavailable"));
        } else {
            List<String> lines = detailLines();
            for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), left() + 18, y + 4 + i * 13,
                    i == 0 ? SteampunkPalette.ACCENT : SteampunkPalette.TEXT);
        }
        g.disableScissor();
        int viewport = bodyBottom() - bodyTop();
        if (contentHeight > viewport) {
            int track = Math.max(1, viewport), thumb = Math.max(12, track * viewport / contentHeight);
            int thumbY = bodyTop() + (track - thumb) * scroll / Math.max(1, contentHeight - viewport);
            g.fill(left() + panelWidth() - 9, bodyTop(), left() + panelWidth() - 7, bodyBottom(), SteampunkPalette.CONTROL);
            g.fill(left() + panelWidth() - 9, thumbY, left() + panelWidth() - 7, thumbY + thumb, SteampunkPalette.ACCENT);
        }
    }
    private void drawCard(GuiGraphicsExtractor g, int offset, String key, String value) {
        int y = bodyTop() + offset - scroll;
        if (y < bodyTop() || y + 38 > bodyBottom())
            SteampunkSettingsTheme.frame(g, left() + 14, y, inner(), 38, SteampunkPalette.CONTROL, SteampunkPalette.BORDER);
        // Cover the normal one-line button label with a two-line card, preserving hover/focus border.
        g.fill(left() + 16, y + 2, left() + panelWidth() - 16, y + 36, SteampunkPalette.CONTROL);
        g.text(font, tr(key), left() + 24, y + 7, SteampunkPalette.TEXT);
        g.text(font, font.plainSubstrByWidth(value, inner() - 36), left() + 24, y + 22, SteampunkPalette.MUTED);
        g.text(font, ">", left() + panelWidth() - 30, y + 14, SteampunkPalette.ACCENT);
    }
*/
    //?}
}
