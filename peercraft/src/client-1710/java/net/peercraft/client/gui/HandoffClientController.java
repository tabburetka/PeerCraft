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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Forge 1.7.10 backport of {@code src/main/.../client/gui/HandoffClientController.java} (cf.
 * the 1.12.2 twin, byte-identical there except {@code addScheduledTask} -&gt;
 * {@code func_152344_a}).
 */
public final class HandoffClientController {

    private static final Logger LOGGER = LogManager.getLogger("peercraft");

    public static final HandoffClientController INSTANCE = new HandoffClientController();

    private volatile HandoffOfferScreen currentOfferScreen;
    private volatile Path receivedWorldZip;
    private volatile HandoffProtocol.Offer acceptedOffer;

    private HandoffClientController() {
    }

    public Path takeReceivedWorldZip() {
        Path z = receivedWorldZip;
        receivedWorldZip = null;
        return z;
    }

    public HandoffProtocol.Offer acceptedOffer() {
        return acceptedOffer;
    }

    public void register() {
        P2PBridge.INSTANCE.setOnClientConnected(this::installForSession);
    }

    private void installForSession() {
        AccountClient.AccountSession session = AccountClient.INSTANCE.getCurrentSession();
        final UUID localAccountId = session != null ? session.accountId() : null;

        HandoffClientAgent agent = new HandoffClientAgent(
                localAccountId,
                P2PBridge.INSTANCE::sendRawDatagram,
                new HandoffClientAgent.Callbacks() {
                    @Override
                    public void onOffer(HandoffProtocol.Offer offer) {
                        handleOffer(offer, localAccountId);
                    }

                    @Override
                    public void onMigrate(UUID successorAccountId, boolean amSuccessor) {
                        Minecraft mc = Minecraft.getMinecraft();
                        mc.func_152344_a(() -> PeerCraftUi.setScreen(mc,
                                new HostMigrationScreen(successorAccountId, amSuccessor)));
                    }

                    @Override
                    public void onAborted(String reasonKey) {
                        LOGGER.info("[Handoff] Передача отменена хостом: {}", reasonKey);
                        HandoffOfferScreen screen = currentOfferScreen;
                        if (screen != null) {
                            Minecraft.getMinecraft().func_152344_a(() -> screen.onAbortedExternally(reasonKey));
                        }
                    }
                });

        P2PBridge.INSTANCE.installHandoffClientAgent(agent);
        LOGGER.debug("[Handoff] Клиентский агент установлен на сессию (аккаунт: {})", localAccountId);
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
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> {
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
        net.minecraft.entity.player.EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player != null) {
            player.addChatMessage(new net.minecraft.util.ChatComponentText(PeerCraftLang.tr(key, args)));
        }
    }

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

    private static List<String> missingRequiredMods(List<HandoffProtocol.ModRef> required) {
        Set<String> installed = new HashSet<String>();
        for (PlatformMod m : Services.PLATFORM.getInstalledMods()) {
            installed.add(m.id());
        }
        List<String> missing = new ArrayList<String>();
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
