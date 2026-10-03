package net.peercraft.client.gui;

//? if <26.1
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.peercraft.client.PeerCraftHostOptions;
import net.peercraft.network.handoff.HandoffCoordinator;
import net.peercraft.network.handoff.HandoffProtocol;
import net.peercraft.network.modsync.ModSyncFilter;
import net.peercraft.network.p2p.P2PBridge;
import net.peercraft.platform.Services;
import net.peercraft.platform.services.PlatformMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Host-side "hand off hosting to another player" screen. Lists the joiners connected right now
 * (from {@link P2PBridge#connectedJoiners()}); a joiner must be signed in to a PeerCraft
 * account to be eligible (so the rendezvous presence lookup can find their new room). Choosing
 * one builds the {@link HandoffProtocol.Offer} from {@link PeerCraftHostOptions} + the host's
 * required-mod set and calls {@link P2PBridge#beginHandoff}.
 */
public class HandoffPlayerPickerScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    private final Screen lastScreen;
    private List<P2PBridge.HandoffCandidate> candidates = java.util.Collections.emptyList();
    private int scroll;

    private int panelHeight() {
        return Math.max(1, Math.min(this.height - 24, Math.max(240, Math.min(440, 174 + candidates.size() * 24))));
    }
    private int panelTop() { return (this.height - panelHeight()) / 2; }

    private int panelWidth() { return Math.max(1, Math.min(360, width - 24)); }
    private int contentWidth() { return Math.max(1, panelWidth() - 24); }
    private int contentLeft() { return (width - contentWidth()) / 2; }
    private int rowsTop() { return panelTop() + 46 + introLines().size() * 11; }
    private int backTop() { return panelTop() + panelHeight() - 32; }
    private int visibleRows() { return Math.max(0, (backTop() - 8 - rowsTop()) / 24); }
    private int maxScroll() { return Math.max(0, candidates.size() - visibleRows()); }

    public HandoffPlayerPickerScreen(Screen lastScreen) {
        super(Component.translatable("peercraft.handoff.picker.title"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        clearWidgets();
        candidates = P2PBridge.INSTANCE.connectedJoiners();
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        IntegratedServer server = this.minecraft.getSingleplayerServer();

        int y = rowsTop();
        for (int i = 0; i < Math.min(visibleRows(), candidates.size() - scroll); i++) {
            P2PBridge.HandoffCandidate c = candidates.get(scroll + i);
            String name = displayName(server, c, scroll + i);
            boolean eligible = c.signedIn() && !c.declinedSuccessor();
            Component label = !c.signedIn()
                    ? Component.translatable("peercraft.handoff.picker.row_not_signed_in", name)
                    : c.declinedSuccessor()
                            ? Component.translatable("peercraft.handoff.picker.row_declined_successor", name)
                            : Component.translatable("peercraft.handoff.picker.hand_off", name);
            Button b = SteampunkSettingsTheme.action(contentLeft(), y, contentWidth(), 20,
                    label, btn -> confirmAndChoose(c, name), true);
            b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(label));
            b.active = eligible;
            this.addRenderableWidget(b);
            y += 24;
        }

        this.addRenderableWidget(SteampunkSettingsTheme.action(contentLeft(), backTop(), contentWidth(), 20,
                Component.translatable("peercraft.handoff.picker.cancel"),
                btn -> PeerCraftUi.setScreen(this.minecraft, lastScreen), false));
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0 && x >= contentLeft() && x < contentLeft() + contentWidth()
                && y >= rowsTop() && y < backTop() - 8 && maxScroll() > 0) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(vertical) * 3));
            init(); return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
    private boolean pageScroll(int key) {
        if ((key != 266 && key != 267) || maxScroll() == 0) return false;
        scroll = Math.max(0, Math.min(maxScroll(), scroll + (key == 266 ? -1 : 1) * Math.max(1, visibleRows())));
        init();
        return true;
    }
    //? if <1.21.9 {
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        return pageScroll(key) || super.keyPressed(key, scan, modifiers);
    }
    //?} else {
    /*@Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        return pageScroll(event.key()) || super.keyPressed(event);
    }*/
    //?}

    @Override public void onClose() { PeerCraftUi.setScreen(minecraft, lastScreen); }

    /** Gates the actual offer behind a Yes/No confirmation when handoffConfirmBeforeOffer() is set (the default) — a handoff can't be cleanly undone once the successor accepts. */
    private void confirmAndChoose(P2PBridge.HandoffCandidate c, String name) {
        if (!net.peercraft.config.PeerCraftConfig.handoffConfirmBeforeOffer()) {
            choose(c, name);
            return;
        }
        Screen self = this;
        PeerCraftUi.setScreen(this.minecraft, new PeerCraftConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        choose(c, name);
                    } else {
                        PeerCraftUi.setScreen(HandoffPlayerPickerScreen.this.minecraft, self);
                    }
                },
                Component.translatable("peercraft.handoff.picker.confirm.title"),
                Component.translatable("peercraft.handoff.picker.confirm.body", name),
                Component.translatable("peercraft.handoff.picker.confirm.yes"),
                Component.translatable("peercraft.handoff.picker.confirm.no")));
    }

    /** Posts a chat line for a handoff event, gated on the chatNotify setting. */
    private static void notifyChat(String key, Object... args) {
        if (!net.peercraft.config.PeerCraftConfig.handoffChatNotify()) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> {
            net.minecraft.client.player.LocalPlayer player = mc.player;
            if (player != null) {
                Component message = Component.translatable(key, args);
                //? if <26.1
                player.displayClientMessage(message, false);
                //? if >=26.1
                /*player.sendSystemMessage(message);*/
            }
        });
    }

    private void choose(P2PBridge.HandoffCandidate c, String name) {
        net.minecraft.server.MinecraftServer server = this.minecraft.getSingleplayerServer();

        String worldId = "";
        long estSize = 0L;
        if (server != null) {
            try {
                java.nio.file.Path worldDir = net.peercraft.client.handoff.WorldArchiver.worldDir(server);
                worldId = net.peercraft.client.handoff.PeercraftWorldMeta.ensureHosting(worldDir).worldId();
                estSize = net.peercraft.client.handoff.WorldArchiver.estimateSize(worldDir);
            } catch (RuntimeException e) {
                LOGGER.warn("[Handoff] Не удалось получить worldId/размер: {}", e.toString());
            }
        }

        HandoffProtocol.Offer offer = new HandoffProtocol.Offer(
                HandoffProtocol.PROTO_VERSION,
                ThreadLocalRandom.current().nextLong(),
                worldLabel(),
                estSize, // rough on-disk size — the exact archive size isn't known until WorldArchiver zips it
                PeerCraftHostOptions.maxPlayers,
                flags(),
                requiredMods(),
                worldId);

        HandoffStatusScreen status = new HandoffStatusScreen(lastScreen, name);

        final net.minecraft.server.MinecraftServer srv = server;
        HandoffCoordinator.Callbacks callbacks = new HandoffCoordinator.Callbacks() {
            @Override public void onAccepted() {
                notifyChat("peercraft.handoff.chat.accepted", name);
                status.onStatus("peercraft.handoff.status.transferring");
            }
            @Override public void onDeclined(String reasonKey) {
                notifyChat("peercraft.handoff.chat.declined", name);
                status.onAborted(reasonKey);
            }
            @Override public void onSuccessorReady() {
                // The world now lives on the successor. Stamp our local copy so opening it in
                // singleplayer later warns that it's a stale post-handoff leftover.
                if (srv != null) {
                    try {
                        net.peercraft.client.handoff.PeercraftWorldMeta.markHandedOff(
                                net.peercraft.client.handoff.WorldArchiver.worldDir(srv), name);
                    } catch (RuntimeException e) {
                        LOGGER.warn("[Handoff] Не удалось отметить мир как переданный: {}", e.toString());
                    }
                }
                notifyChat("peercraft.handoff.chat.done", name);
                status.onDone("peercraft.handoff.status.done");
            }
            @Override public void onAborted(String reasonKey) {
                notifyChat("peercraft.handoff.chat.aborted", name);
                status.onAborted(reasonKey);
            }
            @Override public void onStatus(String messageKey) { status.onStatus(messageKey); }
        };

        boolean session = net.peercraft.client.handoff.SafeHandoffSession.INSTANCE.begin(server, c.peer(), name, offer, callbacks);
        if (!session) {
            status.onAborted("peercraft.handoff.abort.unknown");
        }
        PeerCraftUi.setScreen(this.minecraft, status);
    }

    private static String worldLabel() {
        String w = PeerCraftHostOptions.worldName;
        return (w != null && !w.isBlank()) ? w : "world";
    }

    private static int flags() {
        int f = 0;
        if (PeerCraftHostOptions.allowUnlicensedPlayers) f |= HandoffProtocol.OFFER_FLAG_ALLOW_UNLICENSED;
        if (PeerCraftHostOptions.friendsOnly) f |= HandoffProtocol.OFFER_FLAG_FRIENDS_ONLY;
        if (PeerCraftHostOptions.publicRoom) f |= HandoffProtocol.OFFER_FLAG_PUBLIC_ROOM;
        return f;
    }

    /** The mods the successor must have to run this world: everything installed that isn't client-only or an excluded loader/MC id. */
    private static List<HandoffProtocol.ModRef> requiredMods() {
        List<HandoffProtocol.ModRef> out = new ArrayList<>();
        for (PlatformMod m : Services.PLATFORM.getInstalledMods()) {
            if ("client".equalsIgnoreCase(m.environment())) {
                continue;
            }
            if (ModSyncFilter.isExcluded(m.id(), m.environment(), m.nested(),
                    m.jarPath() != null ? m.jarPath().getFileName().toString() : "")) {
                continue;
            }
            out.add(new HandoffProtocol.ModRef(m.id(), m.version() == null ? "" : m.version()));
        }
        return out;
    }

    private static String displayName(IntegratedServer server, P2PBridge.HandoffCandidate c, int index) {
        // Unlicensed joiners get their PeerCraft accountId as their profile UUID (see
        // ServerLoginPacketListenerImplMixin), so we can resolve a name directly. Licensed
        // players fall back to a numbered label (their profile UUID is their Mojang UUID).
        if (server != null && c.accountId() != null) {
            try {
                ServerPlayer p = server.getPlayerList().getPlayer(c.accountId());
                if (p != null) {
                    return profileName(p);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return Component.translatable("peercraft.handoff.picker.player_fallback", index + 1).getString();
    }

    private static String profileName(ServerPlayer p) {
        // GameProfile became a record in 1.21.9 — getName() is now name().
        //? if <1.21.9
        return p.getGameProfile().getName();
        //? if >=1.21.9
        /*return p.getGameProfile().name();*/
    }

    private List<String> introLines() {
        return PeerCraftUi.wrap(this.font,
                Component.translatable("peercraft.handoff.picker.intro").getString(),
                contentWidth());
    }

    private boolean noneConnected() {
        return candidates.isEmpty();
    }

    private final long animationStart = System.nanoTime();

    //? if <26.1 {
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (minecraft.level != null) {
            super.renderBackground(graphics, mouseX, mouseY, partialTick);
            SteampunkSettingsTheme.frame(graphics, (width - panelWidth()) / 2, panelTop(), panelWidth(), panelHeight(),
                    SteampunkSettingsTheme.PANEL, SteampunkSettingsTheme.BORDER);
        } else {
            SteampunkSettingsTheme.screenBackground(graphics, width, height, (width - panelWidth()) / 2,
                    panelTop(), panelWidth(), panelHeight(), (System.nanoTime() - animationStart) / 1_000_000L);
        }
    }
    //?} else {
    /*@Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (minecraft.level != null) {
            super.extractBackground(graphics, mouseX, mouseY, partialTick);
            SteampunkSettingsTheme.frame(graphics, (width - panelWidth()) / 2, panelTop(), panelWidth(), panelHeight(),
                    SteampunkSettingsTheme.PANEL, SteampunkSettingsTheme.BORDER);
        } else {
            SteampunkSettingsTheme.screenBackground(graphics, width, height, (width - panelWidth()) / 2,
                    panelTop(), panelWidth(), panelHeight(), (System.nanoTime() - animationStart) / 1_000_000L);
        }
    }*/
    //?}

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, cx, panelTop() + 14, SteampunkSettingsTheme.ACCENT);
        if (maxScroll() > 0 && visibleRows() > 0) {
            int top = rowsTop(), track = visibleRows() * 24 - 4;
            int thumb = Math.min(track, Math.max(12, track * visibleRows() / candidates.size()));
            int thumbY = top + (track - thumb) * scroll / maxScroll();
            int x = contentLeft() + contentWidth() + 4;
            graphics.fill(x, top, x + 2, top + track, SteampunkSettingsTheme.BORDER);
            graphics.fill(x, thumbY, x + 2, thumbY + thumb, SteampunkSettingsTheme.ACCENT);
        }
        int y = panelTop() + 38;
        for (String line : introLines()) {
            graphics.drawCenteredString(this.font, line, cx, y, 0xFFAAAAAA);
            y += 11;
        }
        if (noneConnected()) {
            int emptyY = rowsTop();
            for (String line : PeerCraftUi.wrap(this.font,
                    Component.translatable("peercraft.handoff.picker.no_candidates").getString(), contentWidth())) {
                graphics.drawCenteredString(this.font, line, cx, emptyY, 0xFFFF5555);
                emptyY += 11;
            }
        }
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        graphics.centeredText(this.font, this.title, cx, panelTop() + 14, SteampunkSettingsTheme.ACCENT);
        if (maxScroll() > 0 && visibleRows() > 0) {
            int top = rowsTop(), track = visibleRows() * 24 - 4;
            int thumb = Math.min(track, Math.max(12, track * visibleRows() / candidates.size()));
            int thumbY = top + (track - thumb) * scroll / maxScroll();
            int x = contentLeft() + contentWidth() + 4;
            graphics.fill(x, top, x + 2, top + track, SteampunkSettingsTheme.BORDER);
            graphics.fill(x, thumbY, x + 2, thumbY + thumb, SteampunkSettingsTheme.ACCENT);
        }
        int y = panelTop() + 38;
        for (String line : introLines()) {
            graphics.centeredText(this.font, line, cx, y, 0xFFAAAAAA);
            y += 11;
        }
        if (noneConnected()) {
            int emptyY = rowsTop();
            for (String line : PeerCraftUi.wrap(this.font,
                    Component.translatable("peercraft.handoff.picker.no_candidates").getString(), contentWidth())) {
                graphics.centeredText(this.font, line, cx, emptyY, 0xFFFF5555);
                emptyY += 11;
            }
        }
    }*/
    //?}
}
