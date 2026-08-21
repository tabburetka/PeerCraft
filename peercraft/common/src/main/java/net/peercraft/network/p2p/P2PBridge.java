package net.peercraft.network.p2p;

import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.proxy.LocalProxy;
import net.peercraft.network.rendezvous.PunchCoordinator;
import net.peercraft.network.rendezvous.RendezvousClient;
import net.peercraft.network.rendezvous.RendezvousProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class P2PBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    public static final P2PBridge INSTANCE = new P2PBridge();

    // How many recently-sent DATA packets we keep around per connection so a NACK can
    // be honored by resending the exact original bytes. LAN-appropriate default; revisit
    // once this runs over real (higher-latency, lossier) internet P2P.
    private static final int RETRANSMIT_BUFFER_CAPACITY = 256;

    // Some local network stacks/firewalls silently drop UDP datagrams above a certain size on
    // certain ports (empirically found: ~16.3KB on this dev machine's default PeerCraft ports,
    // even though the same size sails through on other ports and MAX_UDP_PAYLOAD_SIZE=65507 is
    // never hit) — retrying a dropped oversized datagram doesn't help since the drop is
    // deterministic, not random loss. Rather than depend on a specific local threshold (which
    // will vary by machine/NAT/router once this is real internet P2P), never send a chunk larger
    // than this — well under every size limit observed, real or firewall-imposed.
    private static final int MAX_CHUNK_SIZE = 8000;

    private P2PReceiver receiver;
    private P2PSender sender;
    private LocalProxy proxy;

    // Joiner role only — a joiner always talks to exactly one host.
    private volatile PeerAddress clientTargetPeer;
    private int localMinecraftPort;

    private boolean isHost = false;

    // Max players in the room — set by the host in startHostViaRendezvous(...).
    // The static local path (startHost) stays single-peer by design (it's a dev-only path
    // for testing on one machine, not the real feature's path), so this value doesn't
    // matter for it.
    private volatile int maxPlayers = 1;

    // HOST: one TCP connection to the local IntegratedServer PER JOINER, not one for the
    // whole bridge — otherwise a second player would disconnect the first (see
    // handleHostIncoming). Both maps index the same set of HostConnection objects: by
    // sessionId (the canonical trust key, as before) and by peer address (fast admission
    // of new connections + a live player count for currentPlayerCount). Both maps are
    // only ever mutated inside synchronized methods (handleHostIncoming/
    // closeHostConnection), same as the old single currentHostConnection field;
    // ConcurrentHashMap so reads (without holding that same lock) from
    // sendChunked/listenMcResponses and currentPlayerCount() stay safe.
    private final Map<Long, HostConnection> hostConnectionsBySessionId = new ConcurrentHashMap<>();
    private final Map<PeerAddress, HostConnection> hostConnectionsByAddress = new ConcurrentHashMap<>();

    // HOST: addresses that actually succeeded at hole punching — only such an address may
    // open a HostConnection (see handleHostIncoming). There used to be no admission control
    // here at all: any UDP sender that guessed the host's relay port and crafted a frame
    // with seq==0 got a live TCP connection to the local MC server. Never removed on
    // disconnect (see closeHostConnection) — deliberately, so a quick reconnect by the same
    // player doesn't need a fresh NAT punch; because of that it is NOT used to count the
    // current number of players (see currentPlayerCount()) — the live hostConnectionsByAddress
    // is used for that instead.
    private final Set<PeerAddress> authorizedPeers = ConcurrentHashMap.newKeySet();

    // HOST (Phase 5): the joiner's accountId keyed by PeerAddress, if the joiner was logged
    // in when they connected — populated in beginHostPunch (arrives together with
    // PEER_FOUND), read in startNewHostConnection to associate the accountId with the
    // socket's local port for PlayerIdentityRegistry (see ServerLoginNetworkHandlerMixin).
    // Like authorizedPeers, never removed when a single joiner disconnects — only cleared
    // entirely in cancelRendezvous(), for the same reason: a quick reconnect by the same
    // player shouldn't lose its account binding without a fresh NAT punch.
    private final Map<PeerAddress, java.util.UUID> joinerAccountIdByAddress = new ConcurrentHashMap<>();

    // HOST: active NAT-punch attempts, one per joiner — do NOT cancel each other when a new
    // one is added (unlike rendezvousListener below, which stays a single slot only for the
    // joiner role, where only one attempt can ever be in flight at once).
    private final Map<PeerAddress, PunchCoordinator> activePunches = new ConcurrentHashMap<>();

    private volatile ClientSession currentClientSession;

    // Joiner role only: active during the one-shot rendezvous/hole-punch flow
    // (RendezvousClient first, then PunchCoordinator) — null before and after it. A joiner
    // always punches to exactly one host at a time, so a single slot here is still correct
    // (the host uses activePunches above instead).
    private volatile RawPacketListener rendezvousListener;

    // The host's persistent RendezvousClient — lives for the whole hosting session (until
    // cancelRendezvous() is called on world close, or a new hosting attempt begins), not
    // just until the first match. The room stays reusable for as long as the host holds it
    // (see RoomRegistry.ROOM_TTL_MILLIS on the server), so this slot must keep receiving
    // traffic from the rendezvous server (and sending keepalives) during and after punching
    // specific joiners — hence it's kept separate from activePunches rather than merged
    // with them.
    private volatile RendezvousClient hostRendezvousClient;

    // Prevents a repeated click on "Connect" on the Join screen from silently tearing down
    // an already-in-progress or already-established rendezvous connection — previously a
    // repeated call to startClientViaRendezvous just recreated the receiver/sender on top of
    // a live session. true from the moment an attempt starts; reset on failure (see
    // handleFailed) and when the local TCP client actually disconnects from LocalProxy (see
    // endClientSession).
    private final AtomicBoolean rendezvousClientBusy = new AtomicBoolean(false);

    private P2PBridge() {
        this.receiver = new P2PReceiver();
        this.sender = new P2PSender(null);
    }

    public void startHost(int mcPort) {
        int hostUdpPort = PeerCraftConfig.hostUdpPort();
        int peerPort = PeerCraftConfig.peerPortForHost();
        String peerHost = PeerCraftConfig.peerHost();
        this.isHost = true;
        this.localMinecraftPort = mcPort;
        this.maxPlayers = 1;

        restartReceiver(hostUdpPort);
        try {
            authorizedPeers.add(new PeerAddress(InetAddress.getByName(peerHost), peerPort));
        } catch (UnknownHostException e) {
            LOGGER.error("[P2PBridge] Не удалось разрешить статический адрес пира {}", peerHost, e);
        }

        LOGGER.info("[P2PBridge] ХОСТ ГОТОВ: LAN порт MC {}, UDP слушает на {}, ожидает пира {}:{}", mcPort, receiver.getBoundPort(), peerHost, peerPort);
    }

    // Called on the CLIENT
    public void startClient() {
        int clientUdpPort = PeerCraftConfig.clientUdpPort();
        int peerPort = PeerCraftConfig.peerPortForClient();
        String peerHost = PeerCraftConfig.peerHost();
        this.isHost = false;

        restartReceiver(clientUdpPort);
        setClientTargetPeer(peerHost, peerPort);

        LOGGER.info("[P2PBridge] КЛИЕНТ ГОТОВ: UDP слушает на {}, пакеты шлёт на {}:{}", receiver.getBoundPort(), peerHost, peerPort);
    }

    // Listener for room registration progress on the HOST — primarily so the caller can be
    // told the room code (e.g. to show it in chat), not just log it. Like ConnectListener,
    // its methods may be called from a background thread.
    public interface HostListener {
        // changed=true means this ISN'T the room's original code — see
        // RendezvousClient.RoomCallback for why that can happen (rendezvous server
        // restart, or a long connectivity gap despite the keepalive).
        void onRoomCreated(String code, boolean changed);
        void onFailed(String reason);
    }

    private static final HostListener LOGGING_HOST_LISTENER = new HostListener() {
        @Override
        public void onRoomCreated(String code, boolean changed) {
            if (changed) {
                LOGGER.warn("[P2PBridge] Код комнаты изменился (старый больше не действителен): {}", code);
            }
        }

        @Override
        public void onFailed(String reason) {
            LOGGER.error("[P2PBridge] {}", reason);
        }
    };

    // Called on the HOST instead of startHost(...) when peercraft.internetPlay is enabled:
    // registers the room on the rendezvous server instead of using the static
    // peerHost/peerPort, then punches NAT for each joiner that gets matched.
    public void startHostViaRendezvous(int mcPort) {
        startHostViaRendezvous(mcPort, 1, LOGGING_HOST_LISTENER);
    }

    public void startHostViaRendezvous(int mcPort, int maxPlayers, HostListener listener) {
        this.isHost = true;
        this.localMinecraftPort = mcPort;
        this.maxPlayers = maxPlayers;
        restartReceiver(PeerCraftConfig.hostUdpPort());

        InetAddress rendezvousAddress = resolveRendezvousAddress();
        if (rendezvousAddress == null) {
            listener.onFailed("Не удалось разрешить адрес сервера знакомств");
            return;
        }
        int rendezvousPort = PeerCraftConfig.rendezvousPort();

        LOGGER.info("[P2PBridge] Регистрируемся на сервере знакомств {}:{} (макс. игроков: {})...", rendezvousAddress.getHostAddress(), rendezvousPort, maxPlayers);
        RendezvousClient client = new RendezvousClient(sender, rendezvousAddress, rendezvousPort);
        setHostRendezvousClient(client);

        // Attach the host's logged-in account (if any) so their room shows up as "hosting" in
        // friends' presence — anonymous hosting (no account) is unchanged, see
        // RendezvousClient.registerRoom's two overloads.
        net.peercraft.network.account.AccountClient.AccountSession session =
                net.peercraft.network.account.AccountClient.INSTANCE.getCurrentSession();

        client.registerRoom(
                maxPlayers,
                this::currentPlayerCount,
                session != null ? session.accountId() : null,
                session != null ? session.sessionToken() : null,
                (code, changed) -> {
                    if (changed) {
                        LOGGER.warn("[P2PBridge] Код комнаты изменился! Новый код для второго игрока: {}", code);
                    } else {
                        LOGGER.info("[P2PBridge] Комната создана! Код для второго игрока: {}", code);
                    }
                    listener.onRoomCreated(code, changed);
                },
                new RendezvousClient.MatchCallback() {
                    @Override
                    public void onMatched(RendezvousProtocol.Address peer, long token) {
                        beginHostPunch(peer, token, client.accountIdForPeer(peer));
                    }

                    @Override
                    public void onFailed(String reason) {
                        LOGGER.error("[P2PBridge] Не удалось создать комнату на сервере знакомств: {}", reason);
                        listener.onFailed("Не удалось создать комнату на сервере знакомств: " + reason);
                    }
                }
        );
    }

    // Live count of connected players — passed through to RendezvousClient and forwarded to
    // RoomRegistry on the rendezvous server via the keepalive REGISTER. This is what lets a
    // slot freed up by a leaving player become available again without a dedicated "player
    // left" event — see RoomRegistry.join() on the server.
    private int currentPlayerCount() {
        return hostConnectionsByAddress.size();
    }

    // Listener for join-via-rendezvous progress — gives the caller (e.g. an in-game screen)
    // real-time status instead of just logs. All methods may be called from a background
    // thread (the retry thread in RendezvousClient/PunchCoordinator), so the caller is
    // responsible for marshaling to the right thread if needed.
    public interface ConnectListener {
        void onStatus(String message);
        void onConnected();
        void onFailed(String reason);
    }

    private static final ConnectListener LOGGING_LISTENER = new ConnectListener() {
        @Override
        public void onStatus(String message) {
            LOGGER.info("[P2PBridge] {}", message);
        }

        @Override
        public void onConnected() {
        }

        @Override
        public void onFailed(String reason) {
            LOGGER.error("[P2PBridge] {}", reason);
        }
    };

    // Called on the CLIENT instead of startClient() when peercraft.internetPlay is enabled:
    // joins a room by code (peercraft.roomCode) instead of the static peerHost/peerPort,
    // then punches NAT and only afterwards calls setClientTargetPeer(...).
    public void startClientViaRendezvous() {
        startClientViaRendezvous(PeerCraftConfig.roomCode(), PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(), LOGGING_LISTENER);
    }

    // Like startClientViaRendezvous(), but the room code and rendezvous server address are
    // supplied explicitly by the caller (e.g. an in-game screen) instead of being read from
    // PeerCraftConfig — and join progress is reported through the listener, not just logged.
    public void startClientViaRendezvous(String code, String rendezvousHost, int rendezvousPort, ConnectListener listener) {
        if (code == null || code.isBlank()) {
            listener.onFailed("код комнаты не задан — присоединяться не к чему");
            return;
        }

        // Prevents a repeated click on "Connect" from silently tearing down an
        // already-in-progress or already-established connection — see rendezvousClientBusy.
        // Reset either on failure (below, via guardedListener), or when the local client
        // actually disconnects from the world (endClientSession).
        if (!rendezvousClientBusy.compareAndSet(false, true)) {
            listener.onFailed("Уже идёт подключение или соединение уже установлено — сначала выйдите из текущего мира, если хотите присоединиться заново.");
            return;
        }
        ConnectListener guardedListener = new ConnectListener() {
            @Override
            public void onStatus(String message) {
                listener.onStatus(message);
            }

            @Override
            public void onConnected() {
                listener.onConnected();
            }

            @Override
            public void onFailed(String reason) {
                rendezvousClientBusy.set(false);
                listener.onFailed(reason);
            }
        };

        this.isHost = false;
        // Someone who was hosting earlier in this same launch (and so stopped LocalProxy
        // when opening to LAN — see OpenToLanMixin) may have left the world and now wants to
        // join by code: without this, 127.0.0.1:<proxyPort> would be a dead port by the time
        // ConnectScreen.startConnecting(...) runs.
        if (!isProxyRunning()) {
            startProxy(PeerCraftConfig.proxyPort());
        }
        restartReceiver(PeerCraftConfig.clientUdpPort());

        InetAddress rendezvousAddress = resolveRendezvousAddress(rendezvousHost, guardedListener);
        if (rendezvousAddress == null) {
            return;
        }

        guardedListener.onStatus("Присоединяемся к комнате " + code + " через сервер знакомств " + rendezvousAddress.getHostAddress() + ":" + rendezvousPort + "...");
        RendezvousClient client = new RendezvousClient(sender, rendezvousAddress, rendezvousPort);
        setRendezvousListener(client);

        // Attaching our account (if logged in) lets the host identify us for save-data
        // isolation (Phase 5) — anonymous joining (no account) is unchanged.
        net.peercraft.network.account.AccountClient.AccountSession joinerSession =
                net.peercraft.network.account.AccountClient.INSTANCE.getCurrentSession();
        RendezvousClient.MatchCallback matchCallback = new RendezvousClient.MatchCallback() {
            @Override
            public void onMatched(RendezvousProtocol.Address peer, long token) {
                beginClientPunch(peer, token, guardedListener);
            }

            @Override
            public void onFailed(String reason) {
                guardedListener.onFailed("Не удалось присоединиться к комнате " + code + ": " + reason);
            }
        };
        if (joinerSession != null) {
            client.joinRoom(code, joinerSession.sessionToken(), matchCallback);
        } else {
            client.joinRoom(code, matchCallback);
        }
    }

    // Called by LocalProxy when the local Minecraft TCP client actually disconnects from
    // the proxy (session closed cleanly or dropped) — the only way rendezvousClientBusy can
    // honestly reset after a SUCCESSFUL join, without permanently blocking a repeat Join
    // after leaving the world.
    public void endClientSession(long sessionId) {
        if (this.currentClientSession != null && this.currentClientSession.sessionId == sessionId) {
            this.currentClientSession = null;
        }
        rendezvousClientBusy.set(false);
    }

    private InetAddress resolveRendezvousAddress() {
        return resolveRendezvousAddress(PeerCraftConfig.rendezvousHost(), LOGGING_LISTENER);
    }

    private InetAddress resolveRendezvousAddress(String host, ConnectListener listener) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            listener.onFailed("Не удалось разрешить адрес сервера знакомств " + host + ": " + e.getMessage());
            return null;
        }
    }

    // HOST: starts a NAT punch for one joiner. Unlike the joiner-side beginClientPunch(...)
    // below, this does NOT cancel other in-flight attempts — activePunches holds them all
    // in parallel, one per PeerAddress.
    private void beginHostPunch(RendezvousProtocol.Address peer, long token, java.util.Optional<java.util.UUID> joinerAccountId) {
        PeerAddress addr = new PeerAddress(peer.host(), peer.port());
        if (joinerAccountId.isPresent()) {
            joinerAccountIdByAddress.put(addr, joinerAccountId.get());
        } else {
            joinerAccountIdByAddress.remove(addr);
        }

        // Defensive limit check in case of a race with RoomRegistry's own check (the
        // rendezvous server should already have rejected an over-limit JOIN itself, see
        // RoomRegistry.join()) — not expected to trigger normally. Counted against live
        // HostConnections + in-progress punch attempts, not against authorizedPeers (which
        // never shrinks, see its comment) — otherwise long-disconnected players would
        // permanently "eat" slots.
        if (hostConnectionsByAddress.size() + activePunches.size() >= maxPlayers) {
            LOGGER.warn("[P2PBridge] Игнорируем матч с {}:{} — комната уже полна ({} из {} игроков)",
                    peer.host().getHostAddress(), peer.port(), hostConnectionsByAddress.size(), maxPlayers);
            return;
        }

        LOGGER.info("[P2PBridge] (Хост) Пир найден: {}:{}, начинаем hole punching...", peer.host().getHostAddress(), peer.port());
        PunchCoordinator punch = new PunchCoordinator(sender, peer, token, new PunchCoordinator.Callback() {
            @Override
            public void onSuccess(String ip, int port) {
                activePunches.remove(addr);
                authorizedPeers.add(addr);
                LOGGER.info("[P2PBridge] (Хост) P2P-соединение установлено напрямую с {}:{}", ip, port);
            }

            @Override
            public void onFailure(String reason) {
                activePunches.remove(addr);
                LOGGER.error("[P2PBridge] (Хост) Hole punching с {}:{} не удался: {}", peer.host().getHostAddress(), peer.port(), reason);
            }
        });
        PunchCoordinator previous = activePunches.put(addr, punch);
        if (previous != null) {
            // The same peer was already being punched (e.g. a repeat match before the
            // previous attempt expired) — cancel specifically that one, other attempts (for
            // other addresses) are left untouched.
            previous.cancel();
        }
        punch.start();
    }

    // JOINER: starts a NAT punch to the host. A joiner always punches to exactly one peer
    // at a time, so a single rendezvousListener slot here is correct (unlike the host, see
    // beginHostPunch above).
    private void beginClientPunch(RendezvousProtocol.Address peer, long token, ConnectListener listener) {
        LOGGER.info("[P2PBridge] Пир найден: {}:{}, начинаем hole punching...", peer.host().getHostAddress(), peer.port());
        listener.onStatus("Пир найден: " + peer.host().getHostAddress() + ":" + peer.port() + ", пробиваем NAT...");
        PunchCoordinator punch = new PunchCoordinator(sender, peer, token, new PunchCoordinator.Callback() {
            @Override
            public void onSuccess(String ip, int port) {
                clearRendezvousListener();
                setClientTargetPeer(ip, port);
                LOGGER.info("[P2PBridge] P2P-соединение установлено напрямую с {}:{}", ip, port);
                listener.onConnected();
            }

            @Override
            public void onFailure(String reason) {
                clearRendezvousListener();
                LOGGER.error("[P2PBridge] Hole punching не удался: {}", reason);
                listener.onFailed("Hole punching не удался: " + reason);
            }
        });
        setRendezvousListener(punch);
        punch.start();
    }

    // Replaces the current rendezvousListener with a new one, first stopping (cancel()) the
    // old one — otherwise its background threads (e.g. RendezvousClient's keepalive
    // REGISTER) would keep running forever with no owner. See RawPacketListener.cancel().
    // Relevant only for the JOINER role — see the comment on the rendezvousListener field.
    private void setRendezvousListener(RawPacketListener newListener) {
        RawPacketListener previous = this.rendezvousListener;
        this.rendezvousListener = newListener;
        if (previous != null) {
            previous.cancel();
        }
    }

    private void clearRendezvousListener() {
        setRendezvousListener(null);
    }

    // Like setRendezvousListener, but for the host's separate persistent RendezvousClient
    // slot — see the hostRendezvousClient field.
    private void setHostRendezvousClient(RendezvousClient newClient) {
        RendezvousClient previous = this.hostRendezvousClient;
        this.hostRendezvousClient = newClient;
        if (previous != null) {
            previous.cancel();
        }
    }

    // Stops the host's current rendezvous/punch activity — the host's persistent
    // RendezvousClient and all in-progress punch attempts (activePunches) — with no error
    // reported. Called when the host closes their world (see OpenToLanMixin.onStopServer),
    // so the room doesn't keep living (and stay reusable) after hosting has actually
    // stopped.
    public void cancelRendezvous() {
        setHostRendezvousClient(null);
        clearRendezvousListener();
        for (PeerAddress addr : activePunches.keySet()) {
            PunchCoordinator punch = activePunches.remove(addr);
            if (punch != null) {
                punch.cancel();
            }
        }
        authorizedPeers.clear();
        joinerAccountIdByAddress.clear();
    }

    private void restartReceiver(int port) {
        if (this.receiver != null) {
            this.receiver.stop();
        }
        this.receiver = new P2PReceiver();
        this.receiver.start(port);
        // Recreates the sender on the same socket as the receiver — see the comment in
        // P2PSender. Without this, a punched hole-punching NAT mapping would be useless.
        this.sender = new P2PSender(this.receiver.getSocket());
    }

    public void startProxy(int port) {
        if (this.proxy != null) {
            this.proxy.stop();
        }
        this.proxy = new LocalProxy(this);
        this.proxy.start(port);
        LOGGER.info("[P2PBridge] Запрошен запуск локального TCP-прокси на 127.0.0.1:{}", port);
    }

    // JOINER ONLY: sets the bridge's single target host.
    private void setClientTargetPeer(String ip, int port) {
        try {
            this.clientTargetPeer = new PeerAddress(InetAddress.getByName(ip), port);
            LOGGER.info("[P2PBridge] Назначен целевой хост: {}:{}", ip, port);
        } catch (UnknownHostException e) {
            LOGGER.error("[P2PBridge] Не удалось разрешить адрес хоста {}", ip, e);
        }
    }

    // Called by LocalProxy on every new incoming TCP connection from the client-side MC.
    // Returns the new session id, which must be passed to every subsequent
    // sendProxyDataToP2P(...) call for this TCP connection.
    public long beginClientSession(Socket socket) {
        long sessionId = ThreadLocalRandom.current().nextLong();
        this.currentClientSession = new ClientSession(sessionId);
        LOGGER.info("[P2PBridge] Новая клиентская сессия {} для {}", sessionId, socket.getRemoteSocketAddress());
        return sessionId;
    }

    // Sends data from LocalProxy (the client) into the P2P network over UDP
    public void sendProxyDataToP2P(long sessionId, byte[] data) {
        ClientSession session = this.currentClientSession;
        if (session == null || session.sessionId != sessionId) {
            LOGGER.warn("[P2PBridge] Отмена отправки: клиентская сессия {} больше не активна", sessionId);
            return;
        }
        PeerAddress dest = this.clientTargetPeer;
        if (dest == null) {
            LOGGER.warn("[P2PBridge] Отмена отправки: целевой хост ещё не задан!");
            return;
        }
        sendChunked(dest, sessionId, session.outSeq, data, session.sentPackets);
    }

    private void sendEncoded(PeerAddress dest, byte[] framed) {
        if (dest == null) {
            LOGGER.warn("[P2PBridge] Отмена отправки: адрес назначения не задан!");
            return;
        }
        if (sender == null) {
            LOGGER.error("[P2PBridge] Отмена отправки: P2PSender не инициализирован!");
            return;
        }
        sender.sendData(framed, dest.ip(), dest.port());
    }

    private void sendFramed(PeerAddress dest, long sessionId, long seq, byte flags, byte[] data) {
        sendEncoded(dest, FramedPacket.encode(sessionId, seq, flags, data));
    }

    // Like sendFramed, but also saves the encoded packet in the connection's retransmit
    // buffer — so the original bytes can be resent in response to a NACK.
    private void sendFramedAndBuffer(PeerAddress dest, long sessionId, long seq, byte flags, byte[] data, Map<Long, byte[]> retransmitBuffer) {
        byte[] framed = FramedPacket.encode(sessionId, seq, flags, data);
        synchronized (retransmitBuffer) {
            retransmitBuffer.put(seq, framed);
        }
        sendEncoded(dest, framed);
    }

    // Splits one TCP read into several UDP datagrams no larger than MAX_CHUNK_SIZE, each
    // with its own next seq. The receiving ReorderBuffer already knows how to transparently
    // reassemble adjacent seqs back into one chunk, so the receiver needs no changes.
    private void sendChunked(PeerAddress dest, long sessionId, AtomicLong outSeq, byte[] data, Map<Long, byte[]> retransmitBuffer) {
        int offset = 0;
        do {
            int len = Math.min(MAX_CHUNK_SIZE, data.length - offset);
            byte[] chunk = Arrays.copyOfRange(data, offset, offset + len);
            sendFramedAndBuffer(dest, sessionId, outSeq.getAndIncrement(), (byte) 0, chunk, retransmitBuffer);
            offset += len;
        } while (offset < data.length);
    }

    private void sendNack(PeerAddress dest, long sessionId, long missingSeq) {
        sendEncoded(dest, FramedPacket.encodeNack(sessionId, missingSeq));
    }

    private void resendIfBuffered(PeerAddress dest, long sessionId, long seq, Map<Long, byte[]> retransmitBuffer) {
        byte[] framed;
        synchronized (retransmitBuffer) {
            framed = retransmitBuffer.get(seq);
        }
        if (framed != null) {
            LOGGER.info("[P2PBridge] Повторно отправляем seq={} для сессии {} по NACK", seq, sessionId);
            sendEncoded(dest, framed);
        } else {
            LOGGER.debug("[P2PBridge] NACK на seq={} для сессии {}, но пакет уже вытеснен из буфера ретрансляции", seq, sessionId);
        }
    }

    public LocalProxy getProxy() {
        return this.proxy;
    }

    public boolean isProxyRunning() {
        return this.proxy != null && this.proxy.isRunning();
    }

    public int getProxyPort() {
        return this.proxy != null ? this.proxy.getBoundPort() : 0;
    }

    // Receives a UDP packet from P2PReceiver: strips the framing and forwards it to the local Minecraft TCP socket
    public void handleIncomingPacket(byte[] data, int length, InetAddress senderAddress, int senderPort) {
        // Rendezvous/punch traffic is recognized by its own magic byte (FramedPacket.decode
        // would reject it anyway, since the first byte doesn't match VERSION) — hand it off
        // to the active rendezvous listeners and leave the normal relay path untouched.
        if (length >= 1 && data[0] == RendezvousProtocol.MAGIC) {
            RendezvousClient hostClient = this.hostRendezvousClient;
            if (hostClient != null) {
                hostClient.onPacket(data, length, senderAddress, senderPort);
            }
            if (this.isHost) {
                // On the host, several punch attempts can be in flight at once (one per
                // joiner) — broadcast to all of them, each PunchCoordinator filters by its
                // own expected address+token, so this is safe and bounded by maxPlayers.
                boolean anyPunchActive = false;
                for (PunchCoordinator punch : activePunches.values()) {
                    anyPunchActive = true;
                    punch.onPacket(data, length, senderAddress, senderPort);
                }
                if (hostClient == null && !anyPunchActive) {
                    LOGGER.debug("[P2PBridge] Получен rendezvous-пакет от {}:{}, но ни один слушатель не активен — игнорируем", senderAddress, senderPort);
                }
            } else {
                RawPacketListener listener = this.rendezvousListener;
                if (listener != null) {
                    listener.onPacket(data, length, senderAddress, senderPort);
                } else if (hostClient == null) {
                    // Usually a harmless late packet from the rendezvous server/peer, arriving
                    // after the rendezvous/punch phase already ended (whether it succeeded or
                    // not) — but we still log it, otherwise it would just vanish unexplained.
                    LOGGER.debug("[P2PBridge] Получен rendezvous-пакет от {}:{}, но ни один слушатель не активен — игнорируем", senderAddress, senderPort);
                }
            }
            return;
        }

        FramedPacket frame = FramedPacket.decode(data, length);
        if (frame == null) {
            LOGGER.warn("[P2PBridge] Отброшен пакет: не удалось разобрать заголовок ({} байт)", length);
            return;
        }

        if (this.isHost) {
            handleHostIncoming(frame, new PeerAddress(senderAddress, senderPort));
        } else {
            handleClientIncoming(frame);
        }
    }

    private synchronized void handleHostIncoming(FramedPacket frame, PeerAddress sender) {
        HostConnection conn = this.hostConnectionsBySessionId.get(frame.sessionId());

        if (frame.type() == FramedPacket.TYPE_NACK) {
            if (conn != null) {
                resendIfBuffered(conn.peerAddress, conn.sessionId, frame.nackSeq(), conn.sentPackets);
            }
            return;
        }

        if (conn != null) {
            // Either the same peer as before in this session, or their NAT remapped the
            // external port between packets — tolerated implicitly, as before (this
            // preserves the bridge's existing single-peer-session behavior, just made
            // explicit instead of accidental): move the connection's address instead of
            // starting a new session.
            if (!sender.equals(conn.peerAddress)) {
                hostConnectionsByAddress.remove(conn.peerAddress);
                conn.peerAddress = sender;
                hostConnectionsByAddress.put(sender, conn);
            }
            deliverToHost(conn, frame);
            return;
        }

        if (frame.seq() != 0) {
            // Not the start of a new session — don't pick up in the middle of someone else's/a stale stream.
            LOGGER.debug("[P2PBridge] Отброшен пакет чужой/устаревшей сессии {} (seq={})", frame.sessionId(), frame.seq());
            return;
        }

        if (!authorizedPeers.contains(sender)) {
            // This address never went through hole punching (or it's a stranger's UDP
            // sender that guessed the host's relay port) — don't open it a TCP connection
            // to the MC server.
            LOGGER.warn("[P2PBridge] Отброшена попытка новой сессии от неавторизованного {}:{}", sender.ip(), sender.port());
            return;
        }

        // Reconnect by the same joiner (a new logical TCP session with the same address) —
        // close only their previous HostConnection, other joiners are unaffected.
        HostConnection existingForAddress = hostConnectionsByAddress.get(sender);
        if (existingForAddress != null) {
            closeHostConnection(existingForAddress);
        }

        HostConnection newConn = startNewHostConnection(frame.sessionId(), sender);
        if (newConn == null) {
            return;
        }
        deliverToHost(newConn, frame);
    }

    private HostConnection startNewHostConnection(long sessionId, PeerAddress peerAddress) {
        try {
            LOGGER.info("[P2PBridge] Подключаемся к локальному MC серверу 127.0.0.1:{} (сессия {}, пир {}:{})...", localMinecraftPort, sessionId, peerAddress.ip(), peerAddress.port());
            Socket mcSocket = new Socket("127.0.0.1", localMinecraftPort);
            HostConnection conn = new HostConnection(sessionId, peerAddress, mcSocket);
            hostConnectionsBySessionId.put(sessionId, conn);
            hostConnectionsByAddress.put(peerAddress, conn);

            java.util.UUID joinerAccountId = joinerAccountIdByAddress.get(peerAddress);
            if (joinerAccountId != null) {
                PlayerIdentityRegistry.INSTANCE.put(conn.localPort, joinerAccountId);
            }

            Thread readerThread = new Thread(() -> listenMcResponses(conn), "PeerCraft-MC-Reader");
            readerThread.setDaemon(true);
            readerThread.start();

            LOGGER.info("[P2PBridge] УСПЕШНО подключились к локальному MC серверу (сессия {}, {} из {} игроков)!", sessionId, hostConnectionsByAddress.size(), maxPlayers);
            return conn;
        } catch (IOException e) {
            LOGGER.error("[P2PBridge] Не удалось подключиться к локальному MC серверу на порту {}", localMinecraftPort, e);
            return null;
        }
    }

    private void deliverToHost(HostConnection conn, FramedPacket frame) {
        try {
            ReorderBuffer.Result result = conn.inBuf.accept(frame.seq(), frame.payload());
            if (result.deliverable != null && result.deliverable.length > 0) {
                conn.mcOut.write(result.deliverable);
                conn.mcOut.flush();
            }
            if (result.requestSeq != null) {
                sendNack(conn.peerAddress, conn.sessionId, result.requestSeq);
                LOGGER.info("[P2PBridge] Запросили повторную отправку seq={} для сессии {}", result.requestSeq, conn.sessionId);
            }
        } catch (ReorderBuffer.SessionBrokenException e) {
            LOGGER.error("[P2PBridge] Сессия {} повреждена: {} — закрываем соединение с MC", conn.sessionId, e.getMessage());
            closeHostConnection(conn);
        } catch (IOException e) {
            LOGGER.error("[P2PBridge] Ошибка проброса байт в MC-сервер", e);
            closeHostConnection(conn);
        }
    }

    private synchronized void handleClientIncoming(FramedPacket frame) {
        ClientSession session = this.currentClientSession;

        if (frame.type() == FramedPacket.TYPE_NACK) {
            if (session != null && session.sessionId == frame.sessionId()) {
                resendIfBuffered(this.clientTargetPeer, session.sessionId, frame.nackSeq(), session.sentPackets);
            }
            return;
        }

        if (session == null || session.sessionId != frame.sessionId()) {
            LOGGER.debug("[P2PBridge] Отброшен ответ чужой/устаревшей сессии {} (seq={})", frame.sessionId(), frame.seq());
            return;
        }

        try {
            ReorderBuffer.Result result = session.inBuf.accept(frame.seq(), frame.payload());
            if (result.deliverable != null && result.deliverable.length > 0 && this.proxy != null) {
                this.proxy.sendToClient(result.deliverable);
            }
            if (result.requestSeq != null) {
                sendNack(this.clientTargetPeer, session.sessionId, result.requestSeq);
                LOGGER.info("[P2PBridge] Запросили повторную отправку seq={} для клиентской сессии {}", result.requestSeq, session.sessionId);
            }
        } catch (ReorderBuffer.SessionBrokenException e) {
            LOGGER.error("[P2PBridge] Клиентская сессия {} повреждена: {} — закрываем соединение с локальным MC-клиентом", session.sessionId, e.getMessage());
            if (this.currentClientSession == session) {
                this.currentClientSession = null;
            }
            if (this.proxy != null) {
                this.proxy.disconnectClient();
            }
        }
    }

    private void listenMcResponses(HostConnection conn) {
        byte[] buffer = new byte[32768];
        InputStream mcIn = conn.mcIn;
        try {
            int bytesRead;
            while (conn.active && (bytesRead = mcIn.read(buffer)) != -1) {
                byte[] payload = new byte[bytesRead];
                System.arraycopy(buffer, 0, payload, 0, bytesRead);

                LOGGER.info("[P2PBridge] Получен ответ от Minecraft-сервера ({} байт, сессия {}). Отправляем по UDP...", bytesRead, conn.sessionId);
                sendChunked(conn.peerAddress, conn.sessionId, conn.outSeq, payload, conn.sentPackets);
            }
        } catch (Exception e) {
            LOGGER.warn("[P2PBridge] Чтение из Minecraft-сервера остановлено (сессия {}): {}", conn.sessionId, e.toString());
        } finally {
            sendFramed(conn.peerAddress, conn.sessionId, conn.outSeq.getAndIncrement(), (byte) FramedPacket.FLAG_FIN, new byte[0]);
            closeHostConnection(conn);
        }
    }

    // Closes the connection and removes it from both maps (keyed by its current, possibly
    // already-remapped, conn.peerAddress) — a stale thread from an already-closed
    // HostConnection can't clobber the state of a newer session on the same address, because
    // we check for "map entry -> this exact conn" equality rather than just removing by key.
    private synchronized void closeHostConnection(HostConnection conn) {
        conn.active = false;
        closeQuietly(conn.mcSocket);
        // The joinerAccountIdByAddress entry itself stays (see its field comment — needed for
        // a fast reconnect without a fresh punch), but THIS specific local-port mapping is
        // dead the moment the socket closes: a reconnect opens a brand new Socket with a new
        // ephemeral port, and startNewHostConnection re-populates PlayerIdentityRegistry then.
        PlayerIdentityRegistry.INSTANCE.remove(conn.localPort);
        hostConnectionsBySessionId.remove(conn.sessionId, conn);
        hostConnectionsByAddress.remove(conn.peerAddress, conn);
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    public int getReceiverPort() {
        return receiver != null ? receiver.getBoundPort() : 0;
    }

    public void stop() {
        for (HostConnection conn : hostConnectionsByAddress.values()) {
            closeHostConnection(conn);
        }
        this.currentClientSession = null;
        if (receiver != null) receiver.stop();
        if (proxy != null) proxy.stop();
        LOGGER.info("[P2PBridge] Мост и ресурсы остановлены.");
    }

    private static Map<Long, byte[]> newRetransmitBuffer() {
        return new LinkedHashMap<Long, byte[]>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                return size() > RETRANSMIT_BUFFER_CAPACITY;
            }
        };
    }

    private static final class HostConnection {
        final long sessionId;
        volatile PeerAddress peerAddress;
        final Socket mcSocket;
        // Captured once at construction rather than re-querying mcSocket.getLocalPort() later
        // (e.g. in closeHostConnection, after the socket may already be closed) — see
        // PlayerIdentityRegistry's use of this exact value.
        final int localPort;
        final OutputStream mcOut;
        final InputStream mcIn;
        final ReorderBuffer inBuf = new ReorderBuffer();
        final AtomicLong outSeq = new AtomicLong(0);
        final Map<Long, byte[]> sentPackets = newRetransmitBuffer();
        volatile boolean active = true;

        HostConnection(long sessionId, PeerAddress peerAddress, Socket mcSocket) throws IOException {
            this.sessionId = sessionId;
            this.peerAddress = peerAddress;
            this.mcSocket = mcSocket;
            this.localPort = mcSocket.getLocalPort();
            this.mcOut = mcSocket.getOutputStream();
            this.mcIn = mcSocket.getInputStream();
        }
    }

    private static final class ClientSession {
        final long sessionId;
        final ReorderBuffer inBuf = new ReorderBuffer();
        final AtomicLong outSeq = new AtomicLong(0);
        final Map<Long, byte[]> sentPackets = newRetransmitBuffer();

        ClientSession(long sessionId) {
            this.sessionId = sessionId;
        }
    }
}
