package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.integrated.IntegratedServer;
import net.peercraft.client.PeerCraftHostOptions;
import net.peercraft.network.handoff.HandoffCoordinator;
import net.peercraft.network.handoff.HandoffProtocol;
import net.peercraft.network.modsync.ModSyncFilter;
import net.peercraft.network.p2p.P2PBridge;
import net.peercraft.platform.Services;
import net.peercraft.platform.services.PlatformMod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Forge 1.12.2 backport of {@code src/main/.../client/gui/HandoffPlayerPickerScreen.java} (cf.
 * the 1.16.5 twin). {@code PlayerList.getPlayer(UUID)} is {@code getPlayerByUUID(UUID)} on
 * 1.12.2 and returns {@code EntityPlayerMP} directly; {@code GameProfile.getName()} is
 * unchanged (the record conversion was 1.21.9, far outside this tree).
 */
public class HandoffPlayerPickerScreen extends GuiScreen {

    private static final Logger LOGGER = LogManager.getLogger("peercraft");

    private final GuiScreen lastScreen;

    public HandoffPlayerPickerScreen(GuiScreen lastScreen) {
        this.lastScreen = lastScreen;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int cx = this.width / 2;
        List<P2PBridge.HandoffCandidate> candidates = P2PBridge.INSTANCE.connectedJoiners();
        IntegratedServer server = this.mc.getIntegratedServer();

        int y = this.height / 2 - 40;
        for (int i = 0; i < candidates.size(); i++) {
            P2PBridge.HandoffCandidate c = candidates.get(i);
            String name = displayName(server, c, i);
            boolean eligible = c.signedIn() && !c.declinedSuccessor();
            String label = !c.signedIn()
                    ? PeerCraftLang.tr("peercraft.handoff.picker.row_not_signed_in", name)
                    : c.declinedSuccessor()
                            ? PeerCraftLang.tr("peercraft.handoff.picker.row_declined_successor", name)
                            : PeerCraftLang.tr("peercraft.handoff.picker.hand_off", name);
            IdButton b = IdButton.builder(label, () -> confirmAndChoose(c, name)).bounds(cx - 155, y, 310, 20).build();
            b.enabled = eligible;
            this.addButton(b);
            y += 24;
        }

        this.addButton(IdButton.builder(PeerCraftLang.tr("peercraft.handoff.picker.cancel"),
                        () -> PeerCraftUi.setScreen(this.mc, lastScreen))
                .bounds(cx - 100, this.height - 40, 200, 20).build());
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    // Set by confirmAndChoose() right before pushing the GuiYesNo, consumed by confirmClicked().
    private P2PBridge.HandoffCandidate pendingCandidate;
    private String pendingName;

    /** Gates the actual offer behind a Yes/No confirmation when handoffConfirmBeforeOffer() is set (the default) — a handoff can't be cleanly undone once the successor accepts. */
    private void confirmAndChoose(P2PBridge.HandoffCandidate c, String name) {
        if (!net.peercraft.config.PeerCraftConfig.handoffConfirmBeforeOffer()) {
            choose(c, name);
            return;
        }
        this.pendingCandidate = c;
        this.pendingName = name;
        PeerCraftUi.setScreen(this.mc, new net.minecraft.client.gui.GuiYesNo(this,
                PeerCraftLang.tr("peercraft.handoff.picker.confirm.title"),
                PeerCraftLang.tr("peercraft.handoff.picker.confirm.body", name),
                PeerCraftLang.tr("peercraft.handoff.picker.confirm.yes"),
                PeerCraftLang.tr("peercraft.handoff.picker.confirm.no"), 0));
    }

    /** {@code GuiYesNoCallback} — fired by the {@code GuiYesNo} pushed from {@link #confirmAndChoose}. */
    @Override
    public void confirmClicked(boolean result, int id) {
        P2PBridge.HandoffCandidate c = this.pendingCandidate;
        String name = this.pendingName;
        this.pendingCandidate = null;
        this.pendingName = null;
        if (result && c != null) {
            choose(c, name);
        } else {
            PeerCraftUi.setScreen(this.mc, this);
        }
    }

    /** Posts a chat line for a handoff event, gated on the chatNotify setting. */
    private static void notifyChat(String key, Object... args) {
        if (!net.peercraft.config.PeerCraftConfig.handoffChatNotify()) {
            return;
        }
        net.minecraft.entity.player.EntityPlayer player = Minecraft.getMinecraft().player;
        if (player != null) {
            player.sendMessage(new net.minecraft.util.text.TextComponentString(PeerCraftLang.tr(key, args)));
        }
    }

    private void choose(P2PBridge.HandoffCandidate c, String name) {
        net.minecraft.server.MinecraftServer server = this.mc.getIntegratedServer();

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
                estSize,
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
        PeerCraftUi.setScreen(this.mc, status);
    }

    private static String worldLabel() {
        String w = PeerCraftHostOptions.worldName;
        return (w != null && !w.trim().isEmpty()) ? w : "world";
    }

    private static int flags() {
        int f = 0;
        if (PeerCraftHostOptions.allowUnlicensedPlayers) f |= HandoffProtocol.OFFER_FLAG_ALLOW_UNLICENSED;
        if (PeerCraftHostOptions.friendsOnly) f |= HandoffProtocol.OFFER_FLAG_FRIENDS_ONLY;
        if (PeerCraftHostOptions.publicRoom) f |= HandoffProtocol.OFFER_FLAG_PUBLIC_ROOM;
        return f;
    }

    private static List<HandoffProtocol.ModRef> requiredMods() {
        List<HandoffProtocol.ModRef> out = new ArrayList<HandoffProtocol.ModRef>();
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
        if (server != null && c.accountId() != null) {
            try {
                EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(c.accountId());
                if (p != null) {
                    return p.getGameProfile().getName();
                }
            } catch (RuntimeException ignored) {
            }
        }
        return PeerCraftLang.tr("peercraft.handoff.picker.player_fallback", index + 1);
    }

    private List<String> introLines() {
        return PeerCraftUi.wrap(this.fontRenderer, PeerCraftLang.tr("peercraft.handoff.picker.intro"),
                Math.min(this.width - 60, 380));
    }

    private boolean noneConnected() {
        return P2PBridge.INSTANCE.connectedJoiners().isEmpty();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        int cx = this.width / 2;
        this.drawCenteredString(this.fontRenderer, PeerCraftLang.tr("peercraft.handoff.picker.title"), cx, 24, 0xFFFFFFFF);
        int y = 44;
        for (String line : introLines()) {
            this.drawCenteredString(this.fontRenderer, line, cx, y, 0xFFAAAAAA);
            y += 11;
        }
        if (noneConnected()) {
            this.drawCenteredString(this.fontRenderer, PeerCraftLang.tr("peercraft.handoff.picker.no_candidates"),
                    cx, this.height / 2 - 40, 0xFFFF5555);
        }
    }
}
