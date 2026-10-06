package net.peercraft.network.rendezvous;

import net.peercraft.network.p2p.P2PSender;
import net.peercraft.network.p2p.RawPacketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/** Legacy small-packet punching. Compatible peers use DirectConnectivityCoordinator instead. */
public final class PunchCoordinator implements RawPacketListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final long RETRY_INTERVAL_MILLIS = 300;
    private static final long TIMEOUT_MILLIS = 10_000;

    public interface Callback {
        void onSuccess(String ip, int port);
        void onFailure(String reason);
    }

    private final P2PSender sender;
    private volatile RendezvousProtocol.Address peer;
    private volatile boolean cancelled, verifyingRemap;
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

    // Stops the punch attempt without invoking the callback — the punch thread is already
    // time-bounded (TIMEOUT_MILLIS) anyway, but this gives an immediate, silent stop for
    // when this listener is silently replaced by a new one before the timeout elapses.
    @Override
    public void cancel() {
        cancelled = true;
        done.set(true);
    }

    private void runPunchLoop() {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        byte[] punch = RendezvousProtocol.encodePunch(token);
        while (!done.get() && System.currentTimeMillis() < deadline) {
            RendezvousProtocol.Address endpoint = peer;
            sender.sendData(punch, endpoint.host().getHostAddress(), endpoint.port());
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
                    peer.host().getHostAddress(), peer.port(), TIMEOUT_MILLIS);
            callback.onFailure("hole punching timeout");
        }
    }

    @Override
    public synchronized void onPacket(byte[] data, int length, InetAddress address, int port) {
        if (cancelled || address == null || port < 1 || port > 65535) return;
        int type = RendezvousProtocol.messageType(data, length);
        if (type != RendezvousProtocol.TYPE_PUNCH && type != RendezvousProtocol.TYPE_PUNCH_ACK) return;
        // Check attempt ownership before considering a changed NAT port.
        if (RendezvousProtocol.decodeToken(data, length) != token || !address.equals(peer.host())) return;
        if (port != peer.port()) {
            if (done.get()) return;
            peer = new RendezvousProtocol.Address(address, port); verifyingRemap = true;
            // Probe first: an old counterpart can answer before its success callback unbinds it.
            sender.sendData(RendezvousProtocol.encodePunch(token), address.getHostAddress(), port);
            if (type == RendezvousProtocol.TYPE_PUNCH)
                sender.sendData(RendezvousProtocol.encodePunchAck(token), address.getHostAddress(), port);
            return;
        }
        if (type == RendezvousProtocol.TYPE_PUNCH)
            sender.sendData(RendezvousProtocol.encodePunchAck(token), address.getHostAddress(), port);
        if (verifyingRemap && type != RendezvousProtocol.TYPE_PUNCH_ACK) return;
        if (done.compareAndSet(false, true)) {
            LOGGER.info("[PunchCoordinator] Пробили NAT до {}:{}", address.getHostAddress(), port);
            callback.onSuccess(address.getHostAddress(), port);
        }
    }
}
