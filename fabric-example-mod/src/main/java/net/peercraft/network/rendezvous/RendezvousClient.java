package net.peercraft.network.rendezvous;

import net.peercraft.network.p2p.P2PSender;
import net.peercraft.network.p2p.RawPacketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

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
 * anything (REGISTER is reused for it since it's already idempotent) — and it keeps
 * running for the whole hosting session, not just until the first match: the server
 * treats a room code as reusable for as long as the host keeps sending it (see
 * RoomRegistry.ROOM_TTL_MILLIS on the server side), so a joiner reconnecting later
 * with the same code — or a different joiner entirely — must still be able to match.
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
        // changed=false the first time (initial registration); true if a later keepalive
        // REGISTER comes back with a DIFFERENT code than before — meaning the room the
        // host thought was still live actually isn't anymore (rendezvous server restarted,
        // or the room genuinely expired despite the keepalive — e.g. a long connectivity
        // gap) and the server minted a brand new one. Without surfacing this, the host
        // would keep sharing a now-dead code with no indication anything changed.
        void onRoomCreated(String code, boolean changed);
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

    // true only for registerRoom() (host side). A host's room is reusable for as long as
    // it keeps hosting — so unlike joinRoom()'s one-shot resolve-then-punch flow, the host
    // must keep listening for (and can legitimately receive many) further PEER_FOUND
    // matches: a joiner reconnecting with the same code, or a different joiner entirely.
    private volatile boolean hostMode = false;
    // Debounces host-side match handling against the server retransmitting the same
    // PEER_FOUND — keyed per-peer, not a single field, since several different joiners can
    // legitimately be mid-match at once: a single shared token would fail to dedupe a late
    // retransmit for peer A once a newer match for peer B has overwritten it.
    private final Map<RendezvousProtocol.Address, Long> lastMatchedTokenByPeer = new ConcurrentHashMap<>();
    // Host-role only (Фаза 5) — the joinerAccountId attached to each peer's most recent
    // PEER_FOUND, so P2PBridge.beginHostPunch can look it up by peer address right after
    // onMatched fires. Keyed the same way as lastMatchedTokenByPeer, for the same reason
    // (several joiners can legitimately be mid-match at once).
    private final Map<RendezvousProtocol.Address, java.util.UUID> lastMatchedAccountIdByPeer = new ConcurrentHashMap<>();
    private volatile int maxPlayers = 1;
    private volatile IntSupplier currentPlayerCountSupplier = () -> 0;
    // null = anonymous host (unchanged pre-Фаза-4 behavior). Set only via the account-aware
    // registerRoom overload — lets a logged-in host's room show up as "hosting" in their
    // friends' presence (see AccountService.setHosting on the server).
    private volatile java.util.UUID accountId;
    private volatile byte[] accountSessionToken;
    // Last room code we told the host about — lets handleRoomCreated notice when a later
    // keepalive REGISTER comes back with a different code (see RoomCallback.onRoomCreated).
    private volatile String lastKnownRoomCode = null;

    public RendezvousClient(P2PSender sender, InetAddress rendezvousAddress, int rendezvousPort) {
        this.sender = sender;
        this.rendezvousAddress = rendezvousAddress;
        this.rendezvousPort = rendezvousPort;
    }

    // Останавливает и retry-цикл REGISTER/JOIN, и (что важнее) keepalive-цикл — без
    // этого keepalive-поток продолжал бы слать REGISTER каждые
    // KEEPALIVE_INTERVAL_MILLIS вечно (пока жив процесс) даже после того, как хост
    // реально закрыл мир — именно так и родился этот метод.
    @Override
    public void cancel() {
        state = State.DONE;
    }

    public void registerRoom(int maxPlayers, IntSupplier currentPlayerCountSupplier, RoomCallback roomCallback, MatchCallback matchCallback) {
        registerRoom(maxPlayers, currentPlayerCountSupplier, null, null, roomCallback, matchCallback);
    }

    /** As {@link #registerRoom(int, IntSupplier, RoomCallback, MatchCallback)}, but attaches the host's account so the room shows up as "hosting" in their friends' presence (Фаза 4). */
    public void registerRoom(int maxPlayers, IntSupplier currentPlayerCountSupplier, java.util.UUID accountId, byte[] accountSessionToken,
                              RoomCallback roomCallback, MatchCallback matchCallback) {
        this.hostMode = true;
        this.maxPlayers = maxPlayers;
        this.currentPlayerCountSupplier = currentPlayerCountSupplier;
        this.accountId = accountId;
        this.accountSessionToken = accountSessionToken;
        this.roomCallback = roomCallback;
        this.matchCallback = matchCallback;
        this.state = State.REGISTERING;
        startRetryLoop(this::encodeCurrentRegister, State.REGISTERING);
    }

    private byte[] encodeCurrentRegister() {
        int count = this.currentPlayerCountSupplier.getAsInt();
        java.util.UUID accId = this.accountId;
        byte[] token = this.accountSessionToken;
        if (accId != null && token != null) {
            return RendezvousProtocol.encodeRegisterWithAccount(this.maxPlayers, count, accId, token);
        }
        return RendezvousProtocol.encodeRegister(this.maxPlayers, count);
    }

    public void joinRoom(String code, MatchCallback matchCallback) {
        this.matchCallback = matchCallback;
        this.state = State.JOINING;
        byte[] payload = RendezvousProtocol.encodeJoin(code);
        startRetryLoop(() -> payload, State.JOINING);
    }

    /** As {@link #joinRoom(String, MatchCallback)}, but attaches the joiner's account so the host can identify them for save-data isolation (Фаза 5). */
    public void joinRoom(String code, byte[] accountSessionToken, MatchCallback matchCallback) {
        this.matchCallback = matchCallback;
        this.state = State.JOINING;
        byte[] payload = RendezvousProtocol.encodeJoinWithAccount(code, accountSessionToken);
        startRetryLoop(() -> payload, State.JOINING);
    }

    /**
     * Host-role only: the joinerAccountId the server attached to the most recent PEER_FOUND
     * for this peer, if the joiner was logged in when they joined — see beginHostPunch's use
     * of this in P2PBridge. Empty for anonymous joiners.
     */
    public java.util.Optional<java.util.UUID> accountIdForPeer(RendezvousProtocol.Address peer) {
        return java.util.Optional.ofNullable(lastMatchedAccountIdByPeer.get(peer));
    }

    /** Resends the supplier's current payload only while we're still in {@code activeState} — stops the moment we move past it. */
    private void startRetryLoop(Supplier<byte[]> payloadSupplier, State activeState) {
        Thread retryThread = new Thread(() -> {
            long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MILLIS;
            String host = rendezvousAddress.getHostAddress();
            while (state == activeState && System.currentTimeMillis() < deadline) {
                sender.sendData(payloadSupplier.get(), host, rendezvousPort);
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
            // Ожидаемо в норме (peer PUNCH-трафик — забота PunchCoordinator, а не наша), но
            // не логировать вообще — значит потерять след, если сюда когда-нибудь придёт
            // что-то неожиданное (например, PUNCH от пира долетел до нас раньше, чем
            // P2PBridge переключил rendezvousListener на PunchCoordinator).
            LOGGER.debug("[RendezvousClient] Пакет от {}:{} — не от сервера знакомств ({}:{}), игнорируем",
                    address.getHostAddress(), port, rendezvousAddress.getHostAddress(), rendezvousPort);
            return;
        }

        int type = RendezvousProtocol.messageType(data, length);
        switch (type) {
            case RendezvousProtocol.TYPE_ROOM_CREATED -> handleRoomCreated(data, length);
            case RendezvousProtocol.TYPE_PEER_FOUND -> handlePeerFound(data, length);
            case RendezvousProtocol.TYPE_JOIN_FAIL -> handleJoinFail(data, length);
            default -> LOGGER.warn("[RendezvousClient] Неизвестный тип сообщения {} от сервера знакомств {}:{} — возможно, рассинхронизация версий протокола",
                    type, address.getHostAddress(), port);
        }
    }

    private void handleRoomCreated(byte[] data, int length) {
        if (state != State.REGISTERING && state != State.WAITING_FOR_PEER) {
            return; // not currently hosting — stray/late reply, ignore
        }
        boolean firstTime = state == State.REGISTERING;
        if (firstTime) {
            state = State.WAITING_FOR_PEER;
            startKeepaliveLoop();
        }

        // The server replies with ROOM_CREATED to every REGISTER, including keepalive
        // resends — normally the same code every time. If it ever comes back different
        // (rendezvous server restarted and lost its rooms, or ours genuinely expired
        // despite the keepalive — e.g. a long connectivity gap), the host needs to know
        // the code they've been sharing is dead and here's the new one.
        RendezvousProtocol.RoomCreated roomCreated = RendezvousProtocol.decodeRoomCreated(data, length);
        String previousCode = lastKnownRoomCode;
        boolean changed = previousCode != null && !previousCode.equals(roomCreated.code());
        if (firstTime || changed) {
            lastKnownRoomCode = roomCreated.code();
            RoomCallback callback = roomCallback;
            if (callback != null) {
                callback.onRoomCreated(roomCreated.code(), changed);
            }
        }
    }

    /** Keeps the host's NAT mapping to the rendezvous server open while it waits for a joiner — see class docs. */
    private void startKeepaliveLoop() {
        Thread keepaliveThread = new Thread(() -> {
            String host = rendezvousAddress.getHostAddress();
            while (state == State.WAITING_FOR_PEER) {
                try {
                    Thread.sleep(KEEPALIVE_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (state == State.WAITING_FOR_PEER) {
                    // Re-encoded fresh on every tick (not once, outside the loop) so
                    // currentPlayerCount reflects who's actually connected right now — this is
                    // what lets a slot freed up by a leaving player become joinable again
                    // within one keepalive interval.
                    byte[] payload = encodeCurrentRegister();
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
        RendezvousProtocol.PeerFound peerFound = RendezvousProtocol.decodePeerFound(data, length);

        if (hostMode) {
            // The room stays reusable while we keep hosting, so this can legitimately fire
            // more than once — for several DIFFERENT joiners concurrently, or the same
            // joiner reconnecting — state deliberately stays WAITING_FOR_PEER so the
            // keepalive loop (and this listener) keeps running. Dedupe per-peer in case the
            // server retransmits the same PEER_FOUND, which would otherwise spawn a
            // redundant punch; peers are told apart by address, so this can't accidentally
            // dedupe a real second joiner against a first one's token.
            if (peerFound.joinerAccountId().isPresent()) {
                lastMatchedAccountIdByPeer.put(peerFound.peer(), peerFound.joinerAccountId().get());
            } else {
                lastMatchedAccountIdByPeer.remove(peerFound.peer());
            }
            Long lastToken = lastMatchedTokenByPeer.put(peerFound.peer(), peerFound.token());
            if (lastToken != null && lastToken == peerFound.token()) {
                return;
            }
            MatchCallback callback = matchCallback;
            if (callback != null) {
                callback.onMatched(peerFound.peer(), peerFound.token());
            }
            return;
        }

        // Joiner: one-shot — resolving the peer is this attempt's entire job, punching
        // follows and a fresh join click starts an entirely new RendezvousClient anyway.
        state = State.DONE;
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
