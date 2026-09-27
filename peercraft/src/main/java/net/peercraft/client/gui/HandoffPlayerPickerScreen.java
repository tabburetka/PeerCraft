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

    public HandoffPlayerPickerScreen(Screen lastScreen) {
        super(Component.translatable("peercraft.handoff.picker.title"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        List<P2PBridge.HandoffCandidate> candidates = P2PBridge.INSTANCE.connectedJoiners();
        IntegratedServer server = this.minecraft.getSingleplayerServer();

        int y = this.height / 2 - 40;
        for (int i = 0; i < candidates.size(); i++) {
            P2PBridge.HandoffCandidate c = candidates.get(i);
            String name = displayName(server, c, i);
            boolean eligible = c.signedIn() && !c.declinedSuccessor();
            Component label = !c.signedIn()
                    ? Component.translatable("peercraft.handoff.picker.row_not_signed_in", name)
                    : c.declinedSuccessor()
                            ? Component.translatable("peercraft.handoff.picker.row_declined_successor", name)
                            : Component.translatable("peercraft.handoff.picker.hand_off", name);
            Button b = Button.builder(label, btn -> confirmAndChoose(c, name))
                    .bounds(cx - 155, y, 310, 20).build();
            b.active = eligible;
            this.addRenderableWidget(b);
            y += 24;
        }

        this.addRenderableWidget(Button.builder(Component.translatable("peercraft.handoff.picker.cancel"),
                        btn -> PeerCraftUi.setScreen(this.minecraft, lastScreen))
                .bounds(cx - 100, this.height - 40, 200, 20).build());
    }

    /** Gates the actual offer behind a Yes/No confirmation when handoffConfirmBeforeOffer() is set (the default) — a handoff can't be cleanly undone once the successor accepts. */
    private void confirmAndChoose(P2PBridge.HandoffCandidate c, String name) {
        if (!net.peercraft.config.PeerCraftConfig.handoffConfirmBeforeOffer()) {
            choose(c, name);
            return;
        }
        Screen self = this;
        PeerCraftUi.setScreen(this.minecraft, new net.minecraft.client.gui.screens.ConfirmScreen(
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

        boolean session = net.peercraft.client.handoff.SafeHandoffSession.INSTANCE.begin(server, c.peer(), offer, callbacks);
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
                Math.min(this.width - 60, 380));
    }

    private boolean noneConnected() {
        return P2PBridge.INSTANCE.connectedJoiners().isEmpty();
    }

    //? if <26.1 {
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if <1.21.6
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, cx, 24, 0xFFFFFFFF);
        int y = 44;
        for (String line : introLines()) {
            graphics.drawCenteredString(this.font, line, cx, y, 0xFFAAAAAA);
            y += 11;
        }
        if (noneConnected()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("peercraft.handoff.picker.no_candidates"),
                    cx, this.height / 2 - 40, 0xFFFF5555);
        }
    }
    //?} else {
    /*@Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        graphics.centeredText(this.font, this.title, cx, 24, 0xFFFFFFFF);
        int y = 44;
        for (String line : introLines()) {
            graphics.centeredText(this.font, line, cx, y, 0xFFAAAAAA);
            y += 11;
        }
        if (noneConnected()) {
            graphics.centeredText(this.font,
                    Component.translatable("peercraft.handoff.picker.no_candidates"),
                    cx, this.height / 2 - 40, 0xFFFF5555);
        }
    }*/
    //?}
}
