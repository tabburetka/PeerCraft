package net.peercraft.network.handoff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Joiner-side receiver for {@link HandoffProtocol} traffic from the current host. Handles two
 * things:
 * <ul>
 *   <li><b>Successor role</b> — an {@code T_OFFER} arrives naming this client as the chosen
 *       successor; the GUI shows an accept/decline prompt, then {@link #accept()} /
 *       {@link #decline(String)} reply, and later {@link #signalReady()} confirms the new
 *       server is up.</li>
 *   <li><b>Any joiner</b> — an {@code T_MIGRATE} arrives telling everyone the world has moved;
 *       the GUI shows a "reconnecting…" screen and re-joins by looking up the successor's
 *       account id on the rendezvous server.</li>
 * </ul>
 *
 * <p>No {@code net.minecraft.*}. Callbacks fire on the P2P receive thread; the GUI layer
 * marshals to the game thread.
 */
public final class HandoffClientAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    private static final int REPLY_REPEAT = 5;
    private static final long REPLY_INTERVAL_MILLIS = 300;

    public interface Callbacks {
        /** An offer to become the successor arrived. The GUI decides and calls {@link #accept()} or {@link #decline(String)}. */
        void onOffer(HandoffProtocol.Offer offer);

        /**
         * The host broadcast MIGRATE. {@code successorAccountId} is the rendezvous key to look
         * the new room up by. {@code amSuccessor} is true on the client that accepted the
         * offer (it should launch its integrated server instead of reconnecting).
         */
        void onMigrate(UUID successorAccountId, boolean amSuccessor);

        /** The handoff was called off by the host (or the offer withdrawn) — carry on as normal. */
        void onAborted(String reasonKey);
    }

    private final UUID localAccountId; // nullable — anonymous joiners can't be a successor
    private final Sender sender;
    private final Callbacks callbacks;

    /** Sends a datagram back to the host's relay address. */
    public interface Sender {
        void send(InetAddress ip, int port, byte[] data);
    }

    private final AtomicReference<InetAddress> hostIp = new AtomicReference<>();
    private volatile int hostPort;
    private final AtomicReference<Long> activeOfferId = new AtomicReference<>();
    private final AtomicBoolean accepted = new AtomicBoolean(false);
    private final AtomicBoolean migrateSeen = new AtomicBoolean(false);

    public HandoffClientAgent(UUID localAccountId, Sender sender, Callbacks callbacks) {
        this.localAccountId = localAccountId;
        this.sender = sender;
        this.callbacks = callbacks;
    }

    public void onPacket(byte[] data, int length, InetAddress senderAddress, int senderPort) {
        int type = HandoffProtocol.messageType(data, length);
        if (type < 0) {
            return;
        }
        hostIp.set(senderAddress);
        hostPort = senderPort;

        switch (type) {
            case HandoffProtocol.T_PING:
                break;
            case HandoffProtocol.T_OFFER: {
                HandoffProtocol.Offer offer = HandoffProtocol.decodeOffer(data, length);
                if (activeOfferId.compareAndSet(null, offer.offerId())) {
                    LOGGER.info("[Handoff] Получено предложение стать новым хостом (offerId={}, мир='{}', ~{} байт)",
                            offer.offerId(), offer.worldLabel(), offer.estArchiveBytes());
                    callbacks.onOffer(offer);
                }
                break;
            }
            case HandoffProtocol.T_MIGRATE: {
                HandoffProtocol.Migrate m = HandoffProtocol.decodeMigrate(data, length);
                if (migrateSeen.compareAndSet(false, true)) {
                    boolean amSuccessor = accepted.get()
                            && localAccountId != null
                            && localAccountId.equals(m.successorAccountId());
                    LOGGER.info("[Handoff] Хост инициировал миграцию мира к {} (я преемник: {})",
                            m.successorAccountId(), amSuccessor);
                    callbacks.onMigrate(m.successorAccountId(), amSuccessor);
                }
                break;
            }
            case HandoffProtocol.T_ABORT: {
                HandoffProtocol.Abort a = HandoffProtocol.decodeAbort(data, length);
                Long active = activeOfferId.get();
                if (active == null || a.offerId() == active) {
                    LOGGER.info("[Handoff] Хост отменил передачу: {}", a.reasonKey());
                    callbacks.onAborted(a.reasonKey().isEmpty() ? "peercraft.handoff.abort.unknown" : a.reasonKey());
                }
                break;
            }
            default:
                break;
        }
    }

    /** GUI accepted the offer — reply ACCEPT (best-effort repeated). */
    public void accept() {
        Long id = activeOfferId.get();
        if (id == null || !accepted.compareAndSet(false, true)) {
            return;
        }
        repeat(HandoffProtocol.encodeAccept(id));
    }

    /** GUI declined — reply DECLINE with a {@code peercraft.handoff.decline.*} key. */
    public void decline(String reasonKey) {
        Long id = activeOfferId.get();
        if (id == null) {
            return;
        }
        repeat(HandoffProtocol.encodeDecline(id, reasonKey));
        activeOfferId.set(null);
    }

    /** Successor's integrated server is up and registered — tell the (possibly already gone) host it's safe to stop. */
    public void signalReady() {
        Long id = activeOfferId.get();
        if (id == null) {
            return;
        }
        repeat(HandoffProtocol.encodeMigrateOk(id));
    }

    public boolean hasActiveOffer() {
        return activeOfferId.get() != null;
    }

    private void repeat(byte[] datagram) {
        InetAddress ip = hostIp.get();
        if (ip == null) {
            return;
        }
        final int port = hostPort;
        Thread t = new Thread(() -> {
            for (int i = 0; i < REPLY_REPEAT; i++) {
                sender.send(ip, port, datagram);
                try {
                    Thread.sleep(REPLY_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "PeerCraft-Handoff-Reply");
        t.setDaemon(true);
        t.start();
    }
}
