package net.peercraft.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.TranslatableComponent;
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
 * Minecraft 1.16.5 backport of {@code src/main/.../client/gui/HandoffPlayerPickerScreen.java}.
 * {@code GameProfile.getName()} is unchanged on 1.16.5 (the record conversion was 1.21.9) —
 * no version branch needed for {@code profileName}.
 */
public class HandoffPlayerPickerScreen extends PeerCraftDialogScreen {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    private final Screen lastScreen;
    private int scrollOffset;
    private int listTop, listBottom, shownRows;
    private List<P2PBridge.HandoffCandidate> candidates;
    private List<String> introLines;

    public HandoffPlayerPickerScreen(Screen lastScreen) {
        super(new TranslatableComponent("peercraft.handoff.picker.title"), 400);
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        super.init();
        candidates = P2PBridge.INSTANCE.connectedJoiners();
        IntegratedServer server = this.minecraft.getSingleplayerServer();
        introLines = introLines();
        int introRows = Math.min(introLines.size(), Math.max(1, (dialog.height - dialog.headerHeight - 100) / 12));
        listTop = dialog.contentTop() + introRows * 12 + 10;
        dialogAction(new TranslatableComponent("peercraft.handoff.picker.cancel"), btn -> onClose(), false, 0, 1);
        listBottom = bodyBottom();
        shownRows = Math.max(0, (listBottom - listTop) / dialog.buttonPitch());
        scrollOffset = Math.max(0, Math.min(scrollOffset, candidates.size() - shownRows));
        int shown = Math.min(shownRows, candidates.size() - scrollOffset);
        for (int i = 0; i < shown; i++) {
            int candidateIndex = i + scrollOffset;
            P2PBridge.HandoffCandidate candidate = candidates.get(candidateIndex);
            String name = displayName(server, candidate, candidateIndex);
            boolean eligible = candidate.signedIn() && !candidate.declinedSuccessor();
            TranslatableComponent label = !candidate.signedIn()
                    ? new TranslatableComponent("peercraft.handoff.picker.row_not_signed_in", name)
                    : candidate.declinedSuccessor()
                            ? new TranslatableComponent("peercraft.handoff.picker.row_declined_successor", name)
                            : new TranslatableComponent("peercraft.handoff.picker.hand_off", name);
            Button button = new SteampunkButton(dialog.contentX(), listTop + i * dialog.buttonPitch(), dialog.contentWidth(), dialog.buttonHeight(), label,
                    btn -> confirmAndChoose(candidate, name));
            button.active = eligible;
            addButton(button);
        }
    }

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
                new TranslatableComponent("peercraft.handoff.picker.confirm.title"),
                new TranslatableComponent("peercraft.handoff.picker.confirm.body", name),
                new TranslatableComponent("peercraft.handoff.picker.confirm.yes"),
                new TranslatableComponent("peercraft.handoff.picker.confirm.no")));
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
                player.displayClientMessage(new TranslatableComponent(key, args), false);
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
        PeerCraftUi.setScreen(this.minecraft, status);
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
        if (server != null && c.accountId() != null) {
            try {
                ServerPlayer p = server.getPlayerList().getPlayer(c.accountId());
                if (p != null) {
                    return p.getGameProfile().getName();
                }
            } catch (RuntimeException ignored) {
            }
        }
        return new TranslatableComponent("peercraft.handoff.picker.player_fallback", index + 1).getString();
    }

    private List<String> introLines() {
        return PeerCraftUi.wrap(this.font,
                new TranslatableComponent("peercraft.handoff.picker.intro").getString(),
                dialog.contentWidth() - 10);
    }

    @Override public void onClose() { PeerCraftUi.setScreen(minecraft, lastScreen); }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        int introRows = Math.min(introLines.size(), Math.max(1, (listTop - dialog.contentTop() - 10) / 12));
        for (int i = 0; i < introRows; i++) GuiComponent.drawCenteredString(poseStack, font, introLines.get(i), width / 2,
                dialog.contentTop() + i * 12, PeerCraftUi.TEXT_MUTED);
        if (candidates.isEmpty()) dialog.status(poseStack, font, new TranslatableComponent("peercraft.handoff.picker.no_candidates"),
                listTop, Math.max(12, listBottom - listTop), PeerCraftUi.TEXT_ERROR);
        if (shownRows > 0 && candidates.size() > shownRows) {
            int track = shownRows * dialog.buttonPitch();
            int thumb = Math.max(8, track * shownRows / candidates.size());
            int y = listTop + (track - thumb) * scrollOffset / (candidates.size() - shownRows);
            GuiComponent.fill(poseStack, dialog.left + dialog.width - 7, listTop, dialog.left + dialog.width - 5, listTop + track, 0xFF49331F);
            GuiComponent.fill(poseStack, dialog.left + dialog.width - 7, y, dialog.left + dialog.width - 5, y + thumb, PeerCraftUi.TEXT_ACCENT);
        }
        super.render(poseStack, mouseX, mouseY, partialTick);
        if (mouseY >= listTop && mouseY < listBottom) {
            for (net.minecraft.client.gui.components.AbstractWidget button : buttons) {
                if (button.y >= listTop && button.y < listBottom && button.isMouseOver(mouseX, mouseY)) {
                    renderTooltip(poseStack, button.getMessage(), mouseX, mouseY);
                    break;
                }
            }
        }
    }
    private boolean moveRows(int delta) {
        if (candidates == null || shownRows == 0) return false;
        int next = Math.max(0, Math.min(Math.max(0, candidates.size() - shownRows), scrollOffset + delta));
        if (next == scrollOffset) return false;
        scrollOffset = next;
        setFocused(null);
        buttons.clear();
        children.clear();
        init();
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if ((key == 266 || key == 267) && moveRows((key == 266 ? -1 : 1) * Math.max(1, shownRows))) return true;
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (candidates != null && x >= dialog.contentX() && x < dialog.left + dialog.width && y >= listTop && y < listBottom) {
            if (shownRows > 0 && candidates.size() > shownRows && delta != 0) {
                moveRows(-(int) Math.signum(delta) * 3);
                return true;
            }
        }
        return super.mouseScrolled(x, y, delta);
    }
}
