package net.peercraft.network.rendezvous;

import net.peercraft.network.p2p.P2PSender;
import net.peercraft.network.p2p.RawPacketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives the actual UDP hole-punch attempt once a peer's rendezvous-observed address
 * is known: sends PUNCH datagrams to it repeatedly while listening for a PUNCH or
 * PUNCH_ACK back from exactly that address with the matching token. No relay fallback
 * — a peer this can't reach within the timeout is reported as a clean failure (see the
 * project plan: a home-hosted rendezvous server can't sustain relaying full game
 * traffic for multiple pairs, so that's not attempted).
 */
public final class PunchCoordinator implements RawPacketListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final long RETRY_INTERVAL_MILLIS = 300;
    private static final long TIMEOUT_MILLIS = 10_000;

    public interface Callback {
        void onSuccess(String ip, int port);
        void onFailure(String reason);
    }

    private final P2PSender sender;
    private final RendezvousProtocol.Address peer;
    private final long token;
    private final Callback callback;
    private final AtomicBoolean done = new AtomicBoolean(false);

    public PunchCoordinator(P2PSender sender, RendezvousProtocol.Address peer, long token, Callback callback) {
        this.sender = sender;
        this.peer = peer;
        this.token = token;
        this.callback = callback;
    }

    public void start() {
        Thread punchThread = new Thread(this::runPunchLoop, "PeerCraft-Punch");
        punchThread.setDaemon(true);
        punchThread.start();
    }

    private void runPunchLoop() {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        byte[] punch = RendezvousProtocol.encodePunch(token);
        String peerIp = peer.host().getHostAddress();

        while (!done.get() && System.currentTimeMillis() < deadline) {
            sender.sendData(punch, peerIp, peer.port());
            try {
                Thread.sleep(RETRY_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (done.compareAndSet(false, true)) {
            LOGGER.warn("[PunchCoordinator] Не удалось установить прямое P2P-соединение с {}:{} за {} мс "
                    + "(возможно, симметричный NAT или файрвол блокирует UDP) — hole punching не удался.",
                    peerIp, peer.port(), TIMEOUT_MILLIS);
            callback.onFailure("hole punching timeout");
        }
    }

    @Override
    public void onPacket(byte[] data, int length, InetAddress address, int port) {
        int type = RendezvousProtocol.messageType(data, length);
        if (type != RendezvousProtocol.TYPE_PUNCH && type != RendezvousProtocol.TYPE_PUNCH_ACK) {
            return;
        }
        if (!address.equals(peer.host()) || port != peer.port()) {
            return;
        }
        if (RendezvousProtocol.decodeToken(data, length) != token) {
            return;
        }

        if (type == RendezvousProtocol.TYPE_PUNCH) {
            // Ack once so the other side (which might still be waiting on its own ack) can stop too.
            sender.sendData(RendezvousProtocol.encodePunchAck(token), address.getHostAddress(), port);
        }

        if (done.compareAndSet(false, true)) {
            LOGGER.info("[PunchCoordinator] Пробили NAT до {}:{}", address.getHostAddress(), port);
            callback.onSuccess(address.getHostAddress(), port);
        }
    }
}
