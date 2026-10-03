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
public class HandoffPlayerPickerScreen extends PeerCraftDialogScreen {

    private static final Logger LOGGER = LogManager.getLogger("peercraft");

    private final GuiScreen lastScreen;
    private List<P2PBridge.HandoffCandidate> candidates = new ArrayList<>();
    private final List<String> rowLabels = new ArrayList<>();
    private int scrollOffset, visibleRows, listTop;

    public HandoffPlayerPickerScreen(GuiScreen lastScreen) {
        super(PeerCraftLang.tr("peercraft.handoff.picker.title"), 440, 400);
        this.lastScreen = lastScreen;
    }

    @Override
    public void initGui() {
        super.initGui();
        candidates = P2PBridge.INSTANCE.connectedJoiners();
        listTop = dialog.contentTop() + introLines().size() * 12 + 8;
        rebuildRows();
    }
    private int maxScroll() { return Math.max(0, candidates.size() - visibleRows); }
    private void rebuildRows() {
        this.buttonList.clear(); rowLabels.clear();
        visibleRows = Math.max(0, (backY() - 8 - listTop) / dialog.buttonPitch());
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll()));
        IntegratedServer server = this.mc.getIntegratedServer();
        for (int i = 0; i < Math.min(visibleRows, candidates.size() - scrollOffset); i++) {
            int index = i + scrollOffset;
            P2PBridge.HandoffCandidate c = candidates.get(index);
            String name = displayName(server, c, index);
            boolean eligible = c.signedIn() && !c.declinedSuccessor();
            String label = !c.signedIn() ? PeerCraftLang.tr("peercraft.handoff.picker.row_not_signed_in", name)
                    : c.declinedSuccessor() ? PeerCraftLang.tr("peercraft.handoff.picker.row_declined_successor", name)
                    : PeerCraftLang.tr("peercraft.handoff.picker.hand_off", name);
            IdButton b = IdButton.builder(label, () -> confirmAndChoose(c, name))
                    .bounds(dialog.contentX(), listTop + i * dialog.buttonPitch(), dialog.contentWidth() - 8, dialog.buttonHeight()).build();
            b.enabled = eligible; this.buttonList.add(b); rowLabels.add(label);
        }
        dialogAction(PeerCraftLang.tr("peercraft.handoff.picker.cancel"), () -> PeerCraftUi.setScreen(this.mc, lastScreen), false, 0, 1);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof IdButton) {
            ((IdButton) button).onPress.run();
        }
    }

    /** Preserve the configured confirmation gate before starting any transfer. */
    private void confirmAndChoose(P2PBridge.HandoffCandidate c, String name) {
        if (!net.peercraft.config.PeerCraftConfig.handoffConfirmBeforeOffer()) { choose(c, name); return; }
        PeerCraftUi.setScreen(this.mc, new PeerCraftConfirmScreen(result -> {
            if (result) choose(c, name); else PeerCraftUi.setScreen(this.mc, this);
        }, PeerCraftLang.tr("peercraft.handoff.picker.confirm.title"),
                PeerCraftLang.tr("peercraft.handoff.picker.confirm.body", name),
                PeerCraftLang.tr("peercraft.handoff.picker.confirm.yes"),
                PeerCraftLang.tr("peercraft.handoff.picker.confirm.no")));
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
                dialog.contentWidth() - 8);
    }

    private boolean noneConnected() {
        return P2PBridge.INSTANCE.connectedJoiners().isEmpty();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        int y = dialog.contentTop();
        for (String line : introLines()) {
            this.drawCenteredString(this.fontRenderer, line, width / 2, y, PeerCraftUi.TEXT_MUTED); y += 12;
        }
        if (candidates.isEmpty()) dialog.status(this.fontRenderer, PeerCraftLang.tr("peercraft.handoff.picker.no_candidates"), listTop,
                Math.max(0, backY() - listTop - 8), PeerCraftUi.TEXT_ERROR);
        if (maxScroll() > 0 && visibleRows > 0) {
            int track = visibleRows * dialog.buttonPitch(), thumb = Math.max(8, track * visibleRows / candidates.size());
            int thumbY = listTop + (track - thumb) * scrollOffset / maxScroll();
            drawRect(dialog.left + dialog.width - 7, listTop, dialog.left + dialog.width - 5, listTop + track, net.peercraft.client.theme.SteampunkPalette.BORDER);
            drawRect(dialog.left + dialog.width - 7, thumbY, dialog.left + dialog.width - 5, thumbY + thumb, net.peercraft.client.theme.SteampunkPalette.ACCENT);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (mouseX >= dialog.contentX() && mouseX < dialog.contentX() + dialog.contentWidth() - 8 && mouseY >= listTop) {
            int row = (mouseY - listTop) / dialog.buttonPitch();
            if (row < rowLabels.size() && (mouseY - listTop) % dialog.buttonPitch() < dialog.buttonHeight()) {
                String label = rowLabels.get(row);
                if (this.fontRenderer.getStringWidth(label) > dialog.contentWidth() - 20)
                    drawHoveringText(PeerCraftUi.wrap(this.fontRenderer, label, Math.max(1, dialog.contentWidth())), mouseX, mouseY);
            }
        }
    }
    @Override protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) { PeerCraftUi.setScreen(mc, lastScreen); return; }
        if (keyCode == org.lwjgl.input.Keyboard.KEY_NEXT || keyCode == org.lwjgl.input.Keyboard.KEY_PRIOR) {
            scrollOffset += (keyCode == org.lwjgl.input.Keyboard.KEY_NEXT ? 1 : -1) * Math.max(1, visibleRows); rebuildRows(); return;
        }
        super.keyTyped(typedChar, keyCode);
    }
    @Override public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int delta = org.lwjgl.input.Mouse.getEventDWheel();
        int x = org.lwjgl.input.Mouse.getEventX() * width / mc.displayWidth;
        int y = height - org.lwjgl.input.Mouse.getEventY() * height / mc.displayHeight - 1;
        if (delta != 0 && x >= dialog.contentX() && x < dialog.left + dialog.width && y >= listTop && y < backY() - 8) {
            scrollOffset -= (int) Math.signum(delta) * 3; rebuildRows();
        }
    }
}
