package net.peercraft.network.rendezvous;

import net.peercraft.network.p2p.P2PSender;
import net.peercraft.network.p2p.RawPacketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Talks to the rendezvous server to either publish a room (host) or join one by code
 * (joiner). Reports the matched peer's address+token via {@link MatchCallback} once
 * PEER_FOUND arrives — actually starting the punch is the caller's job (P2PBridge),
 * since that's also what owns swapping the active {@link RawPacketListener}.
 *
 * Must be fed incoming datagrams via {@link #onPacket} (wired through P2PBridge's
 * demux). Retries its own REGISTER/JOIN on a timer since UDP gives no delivery
 * guarantee — safe because the server's handling of both is idempotent. The retry
 * loop only resends the specific message it started with, and only until *that*
 * message's reply arrives — once the host has its room code (WAITING_FOR_PEER), there
 * is nothing left to retry towards getting the room, but the host still sends a low-
 * frequency keepalive (see KEEPALIVE_INTERVAL_MILLIS): a router's outbound UDP NAT
 * mapping to the rendezvous server closes after a period of inactivity (commonly
 * ~30-60s on consumer routers), and once it closes, the eventual unsolicited
 * PEER_FOUND reply — sent whenever a friend actually joins, which could be minutes
 * later — gets silently dropped by the host's own NAT/firewall before it even reaches
 * this client. The keepalive exists purely to keep that mapping open, not to retry
 * anything (REGISTER is reused for it since it's already idempotent).
 */
public final class RendezvousClient implements RawPacketListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final long RETRY_INTERVAL_MILLIS = 500;
    // How long we'll keep retrying REGISTER/JOIN before giving up on reaching the
    // rendezvous server at all — not how long we wait for a peer to show up (that's
    // WAITING_FOR_PEER, bounded only by the keepalive below plus the room's own
    // server-side TTL, not by a client-side timeout).
    private static final long CONNECT_TIMEOUT_MILLIS = 10_000;
    // Comfortably under typical consumer-router UDP NAT idle timeouts (~30-60s) so the
    // host's mapping to the rendezvous server never closes while waiting for a peer.
    private static final long KEEPALIVE_INTERVAL_MILLIS = 15_000;

    public interface RoomCallback {
        void onRoomCreated(String code);
    }

    public interface MatchCallback {
        void onMatched(RendezvousProtocol.Address peer, long token);
        void onFailed(String reason);
    }

    private final P2PSender sender;
    private final InetAddress rendezvousAddress;
    private final int rendezvousPort;

    private enum State { REGISTERING, JOINING, WAITING_FOR_PEER, DONE }

    private volatile State state;
    private volatile RoomCallback roomCallback;
    private volatile MatchCallback matchCallback;
    private final AtomicBoolean resultDelivered = new AtomicBoolean(false);

    public RendezvousClient(P2PSender sender, InetAddress rendezvousAddress, int rendezvousPort) {
        this.sender = sender;
        this.rendezvousAddress = rendezvousAddress;
        this.rendezvousPort = rendezvousPort;
    }

    public void registerRoom(RoomCallback roomCallback, MatchCallback matchCallback) {
        this.roomCallback = roomCallback;
        this.matchCallback = matchCallback;
        this.state = State.REGISTERING;
        startRetryLoop(RendezvousProtocol.encodeRegister(), State.REGISTERING);
    }

    public void joinRoom(String code, MatchCallback matchCallback) {
        this.matchCallback = matchCallback;
        this.state = State.JOINING;
        startRetryLoop(RendezvousProtocol.encodeJoin(code), State.JOINING);
    }

    /** Resends {@code payload} only while we're still in {@code activeState} — stops the moment we move past it. */
    private void startRetryLoop(byte[] payload, State activeState) {
        Thread retryThread = new Thread(() -> {
            long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MILLIS;
            String host = rendezvousAddress.getHostAddress();
            while (state == activeState && System.currentTimeMillis() < deadline) {
                sender.sendData(payload, host, rendezvousPort);
                try {
                    Thread.sleep(RETRY_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (state == activeState) {
                fail("сервер знакомств не ответил за " + CONNECT_TIMEOUT_MILLIS + " мс");
            }
        }, "PeerCraft-Rendezvous");
        retryThread.setDaemon(true);
        retryThread.start();
    }

    @Override
    public void onPacket(byte[] data, int length, InetAddress address, int port) {
        if (!address.equals(rendezvousAddress) || port != rendezvousPort) {
            return; // not from the rendezvous server — ignore (peer PUNCH traffic is PunchCoordinator's job)
        }

        int type = RendezvousProtocol.messageType(data, length);
        switch (type) {
            case RendezvousProtocol.TYPE_ROOM_CREATED -> handleRoomCreated(data, length);
            case RendezvousProtocol.TYPE_PEER_FOUND -> handlePeerFound(data, length);
            case RendezvousProtocol.TYPE_JOIN_FAIL -> handleJoinFail(data, length);
            default -> { }
        }
    }

    private void handleRoomCreated(byte[] data, int length) {
        if (state != State.REGISTERING) {
            return; // already past this step, or a stray retransmit of an old reply
        }
        state = State.WAITING_FOR_PEER;
        RendezvousProtocol.RoomCreated roomCreated = RendezvousProtocol.decodeRoomCreated(data, length);
        startKeepaliveLoop();
        RoomCallback callback = roomCallback;
        if (callback != null) {
            callback.onRoomCreated(roomCreated.code());
        }
    }

    /** Keeps the host's NAT mapping to the rendezvous server open while it waits for a joiner — see class docs. */
    private void startKeepaliveLoop() {
        Thread keepaliveThread = new Thread(() -> {
            byte[] payload = RendezvousProtocol.encodeRegister();
            String host = rendezvousAddress.getHostAddress();
            while (state == State.WAITING_FOR_PEER) {
                try {
                    Thread.sleep(KEEPALIVE_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (state == State.WAITING_FOR_PEER) {
                    sender.sendData(payload, host, rendezvousPort);
                }
            }
        }, "PeerCraft-Rendezvous-Keepalive");
        keepaliveThread.setDaemon(true);
        keepaliveThread.start();
    }

    private void handlePeerFound(byte[] data, int length) {
        if (state != State.WAITING_FOR_PEER && state != State.JOINING) {
            return;
        }
        state = State.DONE;
        RendezvousProtocol.PeerFound peerFound = RendezvousProtocol.decodePeerFound(data, length);
        if (resultDelivered.compareAndSet(false, true)) {
            MatchCallback callback = matchCallback;
            if (callback != null) {
                callback.onMatched(peerFound.peer(), peerFound.token());
            }
        }
    }

    private void handleJoinFail(byte[] data, int length) {
        if (state != State.JOINING) {
            return;
        }
        fail(describeReason(RendezvousProtocol.decodeJoinFailReason(data, length)));
    }

    private void fail(String reason) {
        state = State.DONE;
        if (resultDelivered.compareAndSet(false, true)) {
            LOGGER.warn("[RendezvousClient] {}", reason);
            MatchCallback callback = matchCallback;
            if (callback != null) {
                callback.onFailed(reason);
            }
        }
    }

    private static String describeReason(byte reason) {
        return switch (reason) {
            case RendezvousProtocol.REASON_INVALID_CODE -> "неверный код комнаты";
            case RendezvousProtocol.REASON_ALREADY_CLAIMED -> "комната уже занята";
            case RendezvousProtocol.REASON_EXPIRED -> "код комнаты истёк";
            case RendezvousProtocol.REASON_SERVER_BUSY -> "сервер знакомств перегружен, попробуйте позже";
            default -> "неизвестная ошибка сервера знакомств (" + reason + ")";
        };
    }
}
