package net.peercraft.network.handoff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host-side state machine for one <b>graceful handoff</b> attempt: offer the running world to
 * a chosen successor, wait for accept/decline, (M2) ship the world archive, then tell every
 * joiner to reconnect to the new host.
 *
 * <p>Driven by {@link HandoffProtocol} datagrams that {@code P2PBridge} demuxes off the shared
 * socket (the {@code 0xE3} branch of {@code handleIncomingPacket}). All I/O primitives are
 * injected — no {@code net.minecraft.*} here. Callbacks fire on this class's own daemon
 * thread; the client/GUI layer marshals to the game thread.
 *
 * <p>M1 scope: the full handshake (OFFER &rarr; ACCEPT/DECLINE &rarr; MIGRATE &rarr;
 * MIGRATE_OK) with a pluggable {@link Transfer} step. M1 passes a no-op transfer; M2 plugs in
 * the real archive + mod-sync file transfer.
 */
public final class HandoffCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    private static final long OFFER_RETRY_MILLIS = 700;
    /** Successor never answered the offer at all — give up and let the host keep hosting. */
    private static final long OFFER_TIMEOUT_MILLIS = 20_000;
    /** Missing READY must never be interpreted as success. */
    private static final long MIGRATE_OK_TIMEOUT_MILLIS = 180_000;
    private static final long KEEPALIVE_INTERVAL_MILLIS = 3_000;
    /** How many times to repeat the MIGRATE broadcast to each joiner (it is one-shot and lossy). */
    private static final int MIGRATE_REPEAT = 5;
    private static final long MIGRATE_REPEAT_INTERVAL_MILLIS = 250;

    /** Sends a datagram to one peer. */
    public interface PeerSender {
        void send(java.net.InetAddress ip, int port, byte[] data);
    }

    /** The M2 archive+transfer step. {@code onDone} on success, {@code onFail} with a reason key otherwise. */
    public interface Transfer {
        void run(Runnable onDone, java.util.function.Consumer<String> onFail);
    }

    public interface Callbacks {
        void onAccepted();

        void onDeclined(String reasonKey);

        /** Successor confirmed its integrated server is up and registered — safe to stop hosting. */
        void onSuccessorReady();

        /** The attempt failed (timeout, transfer error, explicit cancel) — the host stays hosting. */
        void onAborted(String reasonKey);

        void onStatus(String messageKey);
    }

    private enum State {OFFERING, TRANSFERRING, MIGRATING, WAITING_OK, DONE, ABORTED}

    private final long offerId;
    private final byte[] offerDatagram;
    private final InetAddress successorIp;
    private final int successorPort;
    private final UUID successorAccountId;
    private final PeerSender sender;
    private final java.util.function.Supplier<List<java.net.SocketAddress>> joinerAddresses;
    private final Transfer transfer;
    private final Callbacks callbacks;

    private final AtomicReference<State> state = new AtomicReference<>(State.OFFERING);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile Thread worker;

    private HandoffCoordinator(HandoffProtocol.Offer offer, byte[] offerDatagram,
                               InetAddress successorIp, int successorPort, UUID successorAccountId,
                               PeerSender sender,
                               java.util.function.Supplier<List<java.net.SocketAddress>> joinerAddresses,
                               Transfer transfer, Callbacks callbacks) {
        this.offerId = offer.offerId();
        this.offerDatagram = offerDatagram;
        this.successorIp = successorIp;
        this.successorPort = successorPort;
        this.successorAccountId = successorAccountId;
        this.sender = sender;
        this.joinerAddresses = joinerAddresses;
        this.transfer = transfer;
        this.callbacks = callbacks;
    }

    public static HandoffCoordinator start(HandoffProtocol.Offer offer,
                                           InetAddress successorIp, int successorPort, UUID successorAccountId,
                                           PeerSender sender,
                                           java.util.function.Supplier<List<java.net.SocketAddress>> joinerAddresses,
                                           Transfer transfer, Callbacks callbacks) {
        byte[] datagram = HandoffProtocol.encodeOffer(offer.offerId(), offer.worldLabel(), offer.estArchiveBytes(),
                offer.maxPlayers(), offer.flags(), offer.requiredMods(), offer.worldId());
        HandoffCoordinator c = new HandoffCoordinator(offer, datagram, successorIp, successorPort, successorAccountId,
                sender, joinerAddresses, transfer, callbacks);
        c.begin();
        return c;
    }

    public long offerId() {
        return offerId;
    }

    public boolean isTerminal() {
        State s = state.get();
        return s == State.DONE || s == State.ABORTED;
    }

    /**
     * Whether {@code MIGRATE} has already gone out. Past this point the successor's connection
     * to THIS host is expected to drop on its own — loading its own world necessarily
     * disconnects it first — so that drop must not be treated as a failure (see
     * {@code P2PBridge.closeHostConnection}, which only cancels a still-{@code false} attempt).
     */
    public boolean migrationStarted() {
        State s = state.get();
        return s == State.MIGRATING || s == State.WAITING_OK || s == State.DONE;
    }

    private void begin() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::runOfferLoop, "PeerCraft-Handoff-Host");
        worker.setDaemon(true);
        worker.start();
    }

    /** Host-initiated cancel (e.g. the host closed the picker, or chose someone else). */
    public void cancel(String reasonKey) {
        if (transitionToTerminal(State.ABORTED)) {
            sendToSuccessor(HandoffProtocol.encodeAbort(offerId, reasonKey));
            callbacks.onAborted(reasonKey);
        }
    }

    private void runOfferLoop() {
        long deadline = System.currentTimeMillis() + OFFER_TIMEOUT_MILLIS;
        long lastKeepalive = 0;
        try {
            while (state.get() == State.OFFERING && System.currentTimeMillis() < deadline) {
                sendToSuccessor(offerDatagram);
                long now = System.currentTimeMillis();
                if (now - lastKeepalive >= KEEPALIVE_INTERVAL_MILLIS) {
                    sendToSuccessor(HandoffProtocol.encodePing());
                    lastKeepalive = now;
                }
                Thread.sleep(OFFER_RETRY_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (state.get() == State.OFFERING) {
            if (transitionToTerminal(State.ABORTED)) {
                callbacks.onAborted("peercraft.handoff.abort.no_response");
            }
        }
    }

    // ---- inbound datagrams (from P2PBridge demux) ----

    public void onPacket(byte[] data, int length, InetAddress senderAddress, int senderPort) {
        int type = HandoffProtocol.messageType(data, length);
        if (type < 0) {
            return;
        }
        // Only the chosen peer can advance this attempt; other admitted players cannot.
        if (!successorIp.equals(senderAddress) || (type != HandoffProtocol.T_MIGRATE_OK && successorPort != senderPort)) return;
        switch (type) {
            case HandoffProtocol.T_PING:
                // keepalive from the successor; nothing to do
                break;
            case HandoffProtocol.T_ACCEPT:
                if (HandoffProtocol.decodeOfferId(data, length) == offerId) {
                    handleAccept();
                }
                break;
            case HandoffProtocol.T_DECLINE: {
                HandoffProtocol.Decline d = HandoffProtocol.decodeDecline(data, length);
                if (d.offerId() == offerId && transitionToTerminal(State.ABORTED)) {
                    callbacks.onDeclined(d.reasonKey().isEmpty() ? "peercraft.handoff.decline.unknown" : d.reasonKey());
                }
                break;
            }
            case HandoffProtocol.T_MIGRATE_OK:
                if (HandoffProtocol.decodeOfferId(data, length) == offerId) {
                    handleMigrateOk();
                }
                break;
            case HandoffProtocol.T_ABORT: {
                HandoffProtocol.Abort a = HandoffProtocol.decodeAbort(data, length);
                if (a.offerId() == offerId && transitionToTerminal(State.ABORTED)) {
                    callbacks.onAborted(a.reasonKey().isEmpty() ? "peercraft.handoff.abort.successor" : a.reasonKey());
                }
                break;
            }
            default:
                break;
        }
    }

    private void handleAccept() {
        if (!state.compareAndSet(State.OFFERING, State.TRANSFERRING)) {
            return; // duplicate ACCEPT, or already past this point
        }
        callbacks.onAccepted();
        callbacks.onStatus("peercraft.handoff.status.transferring");
        // Freeze the rest of the room right now, in parallel with the transfer, instead of
        // making them wait for it to finish: every OTHER joiner gets MIGRATE immediately and
        // starts looking for the new host, so nobody keeps playing (and diverging from the
        // archive already about to be taken) during a transfer that can take a while on a big
        // world. The successor is excluded — it must stay connected here to receive the
        // archive at all — and gets its own MIGRATE only once that transfer is verified done.
        broadcastMigrate(true);
        transfer.run(this::afterTransfer, reason -> {
            if (transitionToTerminal(State.ABORTED)) {
                sendToSuccessor(HandoffProtocol.encodeAbort(offerId, reason));
                callbacks.onAborted(reason);
            }
        });
    }

    private void afterTransfer() {
        if (!state.compareAndSet(State.TRANSFERRING, State.MIGRATING)) {
            return;
        }
        callbacks.onStatus("peercraft.handoff.status.migrating");
        // Tell the successor it's time to become host, and re-send to everyone else too (the
        // early broadcast in handleAccept was one-shot and lossy, and anyone who joined mid-
        // transfer never got it at all) — HandoffClientAgent.onPacket ignores a MIGRATE it's
        // already seen, so a repeat to someone who left already is harmless.
        state.compareAndSet(State.MIGRATING, State.WAITING_OK);
        broadcastMigrate(false);
        // Missing READY must not be interpreted as a successful handoff.
        Thread wait = new Thread(() -> {
            try {
                Thread.sleep(MIGRATE_OK_TIMEOUT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (state.get() == State.WAITING_OK) {
                cancel("peercraft.handoff.abort.no_response");
            }
        }, "PeerCraft-Handoff-WaitOK");
        wait.setDaemon(true);
        wait.start();
    }

    /**
     * Fans {@code MIGRATE} out to every current joiner, repeated a few times because it's a
     * one-shot lossy datagram. {@code excludeSuccessor} skips the chosen successor's own
     * address (used for the early, transfer-time broadcast — it must stay a normal connected
     * joiner here so the archive transfer itself keeps working); the post-transfer broadcast
     * passes {@code false} and includes a direct send to the successor too, so it learns its
     * own account id is the rendezvous key.
     */
    private void broadcastMigrate(boolean excludeSuccessor) {
        byte[] migrate = HandoffProtocol.encodeMigrate(offerId, successorAccountId);
        for (int i = 0; i < MIGRATE_REPEAT; i++) {
            for (java.net.SocketAddress sa : joinerAddresses.get()) {
                if (sa instanceof java.net.InetSocketAddress) {
                    java.net.InetSocketAddress isa = (java.net.InetSocketAddress) sa;
                    InetAddress addr = isa.getAddress();
                    if (addr == null) {
                        continue;
                    }
                    if (excludeSuccessor && addr.equals(successorIp) && isa.getPort() == successorPort) {
                        continue;
                    }
                    sender.send(addr, isa.getPort(), migrate);
                }
            }
            if (!excludeSuccessor) {
                sendToSuccessor(migrate);
            }
            try {
                Thread.sleep(MIGRATE_REPEAT_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void handleMigrateOk() {
        if (state.compareAndSet(State.WAITING_OK, State.DONE)) {
            callbacks.onSuccessorReady();
        }
    }

    private boolean transitionToTerminal(State terminal) {
        while (true) {
            State cur = state.get();
            if (cur == State.DONE || cur == State.ABORTED) {
                return false;
            }
            if (state.compareAndSet(cur, terminal)) {
                if (worker != null) {
                    worker.interrupt();
                }
                return true;
            }
        }
    }

    private void sendToSuccessor(byte[] data) {
        sender.send(successorIp, successorPort, data);
    }
}
