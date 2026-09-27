package net.peercraft.client.gui;

import net.minecraft.client.Minecraft;
import net.peercraft.network.account.AccountClient;
import net.peercraft.network.handoff.HandoffClientAgent;
import net.peercraft.network.handoff.HandoffProtocol;
import net.peercraft.network.handoff.WorldTransfer;
import net.peercraft.network.modsync.ModSyncFilter;
import net.peercraft.network.p2p.P2PBridge;
import net.peercraft.network.p2p.PeerAddress;
import net.peercraft.platform.Services;
import net.peercraft.platform.services.PlatformMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Joiner-side glue for host handoff: on every successful join-via-rendezvous it installs a
 * {@link HandoffClientAgent} on {@link P2PBridge} and turns its callbacks into screens.
 *
 * <ul>
 *   <li>An <b>offer</b> to become the successor is auto-declined if this client isn't signed
 *       in or is missing required mods; otherwise {@link HandoffOfferScreen} asks the player.</li>
 *   <li>A <b>MIGRATE</b> broadcast opens {@link HostMigrationScreen} — the successor launches
 *       its own world (M3), everyone else reconnects to the new host (M3).</li>
 * </ul>
 *
 * Wired once from {@code PeerCraftClientCommon.initClient()} via
 * {@link P2PBridge#setOnClientConnected(Runnable)}.
 */
public final class HandoffClientController {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    public static final HandoffClientController INSTANCE = new HandoffClientController();

    private volatile HandoffOfferScreen currentOfferScreen;
    /** Set on the successor once the world archive has arrived and verified — read by the launcher on MIGRATE. */
    private volatile Path receivedWorldZip;
    /** The offer this client accepted as successor — carries the host options to re-host with. */
    private volatile HandoffProtocol.Offer acceptedOffer;

    private HandoffClientController() {
    }

    /** The verified world archive received as the handoff successor, or {@code null}. Consumed by the launcher. */
    public Path takeReceivedWorldZip() {
        Path z = receivedWorldZip;
        receivedWorldZip = null;
        return z;
    }

    public HandoffProtocol.Offer acceptedOffer() {
        return acceptedOffer;
    }

    /** Call once at client init. */
    public void register() {
        net.peercraft.client.handoff.SafeHandoffSession.INSTANCE.register();
        P2PBridge.INSTANCE.setOnClientConnected(this::installForSession);
    }

    private void installForSession() {
        P2PBridge.INSTANCE.setHandoffControlReceiver(net.peercraft.client.handoff.SafeHandoffSession.INSTANCE);
        P2PBridge.INSTANCE.installHandoffClientAgent(null);
        sendPreference();
    }

    /** Sends the current "decline as successor" preference to the current host, if connected. Safe to call anytime — a no-op when not joined. */
    private void sendPreference() {
        PeerAddress host = P2PBridge.INSTANCE.currentHostPeer();
        if (host == null) {
            return;
        }
        P2PBridge.INSTANCE.sendRawDatagram(host.host(), host.port(),
                HandoffProtocol.encodeSuccessorPreference(net.peercraft.config.PeerCraftConfig.declineHandoffSuccessor()));
    }

    /** Called from the Settings screen's Save — resends the preference immediately if the player is currently connected, so an already-hosted room picks up the change without a reconnect. */
    public void resendPreference() {
        sendPreference();
    }

    private void handleOffer(HandoffProtocol.Offer offer, UUID localAccountId) {
        HandoffClientAgent agent = P2PBridge.INSTANCE.handoffClientAgent();
        if (agent == null) {
            return;
        }
        if (!net.peercraft.config.PeerCraftConfig.handoff()) {
            agent.decline("peercraft.handoff.decline.declined");
            return;
        }
        if (localAccountId == null) {
            agent.decline("peercraft.handoff.decline.not_signed_in");
            return;
        }
        List<String> missing = missingRequiredMods(offer.requiredMods());
        if (!missing.isEmpty()) {
            LOGGER.info("[Handoff] Отклоняем предложение — не хватает модов: {}", missing);
            agent.decline("peercraft.handoff.decline.missing_mods");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            notifyChat("peercraft.handoff.chat.offer_received");
            HandoffOfferScreen screen = new HandoffOfferScreen(offer, agent,
                    () -> currentOfferScreen = null,
                    () -> setupWorldReceiver(offer));
            currentOfferScreen = screen;
            PeerCraftUi.setScreen(mc, screen);
        });
    }

    /** Posts a chat line for a handoff event, gated on the chatNotify setting. Must run on the client thread. */
    private static void notifyChat(String key, Object... args) {
        if (!net.peercraft.config.PeerCraftConfig.handoffChatNotify()) {
            return;
        }
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            net.minecraft.network.chat.Component message = net.minecraft.network.chat.Component.translatable(key, args);
            // Player.displayClientMessage(Component, boolean) was replaced by
            // sendSystemMessage(Component) in 26.1 (the actionbar variant is sendOverlayMessage).
            //? if <26.1
            player.displayClientMessage(message, false);
            //? if >=26.1
            /*player.sendSystemMessage(message);*/
        }
    }

    /** Called when the successor accepts — stands up the 0xE4 receiver so the host's BEGIN is handled. */
    private void setupWorldReceiver(HandoffProtocol.Offer offer) {
        PeerAddress host = P2PBridge.INSTANCE.currentHostPeer();
        if (host == null) {
            LOGGER.warn("[Handoff] Нет адреса хоста — не могу принять мир");
            return;
        }
        this.acceptedOffer = offer;
        this.receivedWorldZip = null;
        Path part = Services.PLATFORM.getConfigDir()
                .resolve("peercraft").resolve("handoff-tmp")
                .resolve("incoming-" + offer.offerId() + ".zip.part");

        WorldTransfer receiver = WorldTransfer.receiver(offer.offerId(), part, 0L,
                P2PBridge.INSTANCE::sendRawDatagram, host.host(), host.port(),
                new WorldTransfer.ReceiverCallbacks() {
                    @Override
                    public void onProgress(long received, long total) {
                        HandoffOfferScreen s = currentOfferScreen;
                        if (s != null) {
                            s.onReceiveProgress(received, total);
                        }
                    }

                    @Override
                    public void onComplete(Path verifiedZip) {
                        LOGGER.info("[Handoff] Мир получен и проверен: {}", verifiedZip);
                        receivedWorldZip = verifiedZip;
                        HandoffOfferScreen s = currentOfferScreen;
                        if (s != null) {
                            s.onWorldReceived();
                        }
                    }

                    @Override
                    public void onFailed(String reasonKey) {
                        LOGGER.warn("[Handoff] Приём мира не удался: {}", reasonKey);
                    }
                });
        P2PBridge.INSTANCE.setSuccessorWorldTransfer(receiver);
    }

    /** Required-mod ids this client doesn't have installed (ignoring the loader/MC/PeerCraft ids). */
    private static List<String> missingRequiredMods(List<HandoffProtocol.ModRef> required) {
        Set<String> installed = new HashSet<>();
        for (PlatformMod m : Services.PLATFORM.getInstalledMods()) {
            installed.add(m.id());
        }
        List<String> missing = new ArrayList<>();
        for (HandoffProtocol.ModRef ref : required) {
            String id = ref.id();
            if (id == null || id.isEmpty() || ModSyncFilter.HARD_EXCLUDED_IDS.contains(id)) {
                continue;
            }
            if (!installed.contains(id)) {
                missing.add(id);
            }
        }
        return missing;
    }
}
