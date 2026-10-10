package net.peercraft.network.p2p;

import net.peercraft.config.ModSyncMode;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.handoff.HandoffClientAgent;
import net.peercraft.network.handoff.HandoffCoordinator;
import net.peercraft.network.handoff.HandoffProtocol;
import net.peercraft.network.handoff.WorldTransfer;
import net.peercraft.network.handoff.WorldTransferProtocol;
import net.peercraft.network.modsync.ModSyncAgent;
import net.peercraft.network.modsync.ModSyncCoordinator;
import net.peercraft.network.modsync.ModSyncHostProvider;
import net.peercraft.network.modsync.ModSyncLink;
import net.peercraft.network.modsync.ModSyncProtocol;
import net.peercraft.network.proxy.LocalProxy;
import net.peercraft.network.connectivity.DirectCandidates;
import net.peercraft.network.connectivity.DirectConnectivityCoordinator;
import net.peercraft.network.relay.DirectPeerTransport;
import net.peercraft.network.relay.RelayBrokerClient;
import net.peercraft.network.relay.RelayPeerTransport;
import net.peercraft.network.transport.PeerTransport;
import net.peercraft.network.transport.SecureDatagramChannel;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.function.Consumer;
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

    // How many recently-sent DATA packets we keep around per connection so a NACK can be
    // honored by resending the exact original bytes. Must stay >= ReorderBuffer.MAX_PENDING's
    // default: a NACK for a seq we've already evicted here can't be answered, which then
    // strands the peer's reorder buffer on that gap until it times out and kills the session.
    // Bumped from the old LAN-appropriate 256 now that this runs over real (lossier,
    // DPI-mangled) internet P2P.
    private static final int RETRANSMIT_BUFFER_CAPACITY = 4096;

    // Some local network stacks/firewalls silently drop UDP datagrams above a certain size on
    // certain ports (empirically found: ~16.3KB on this dev machine's default PeerCraft ports,
    // even though the same size sails through on other ports and MAX_UDP_PAYLOAD_SIZE=65507 is
    // never hit) — retrying a dropped oversized datagram doesn't help since the drop is
    // deterministic, not random loss. Rather than depend on a specific local threshold (which
    // will vary by machine/NAT/router once this is real internet P2P), never send a chunk larger
    // than this — well under every size limit observed, real or firewall-imposed.
    private static final int MAX_CHUNK_SIZE = 8000;

    // Optional pacing (milliseconds) inserted between the datagrams of one multi-chunk
    // payload — 0 (default) keeps the original send-them-all-at-once behavior. A small
    // value (1–2) smears the world-sync firehose out over time so a congested or
    // DPI-mangled uplink drops fewer datagrams, at the cost of slightly slower bulk
    // transfer. Override with -Dpeercraft.send.pacingMillis.
    private static final long SEND_PACING_MILLIS = readSendPacingMillis();

    private static long readSendPacingMillis() {
        try {
            long v = Long.parseLong(System.getProperty("peercraft.send.pacingMillis", "0").trim());
            return (v < 0 || v > 50) ? 0 : v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

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

    // Physical endpoints never become identity keys for negotiated routes.
    private final Map<PeerAddress, NetworkAttempt> networkAttempts = new ConcurrentHashMap<>();
    private final Map<PeerAddress, DirectConnectivityCoordinator> activeDirectChecks = new ConcurrentHashMap<>();
    private final Map<PeerAddress, PeerTransport> peerRoutes = new ConcurrentHashMap<>();
    private final Map<UUID, DirectBinding> directBindings = new ConcurrentHashMap<>();
    private final Map<PeerAddress, PeerTransport> retainedPeerRoutes = new ConcurrentHashMap<>();
    private final Set<PeerAddress> securedPeers = ConcurrentHashMap.newKeySet();
    private volatile Consumer<String> onTransportFailure;
    private volatile Consumer<String> onTransportSelected;
    public void setOnTransportFailure(Consumer<String> hook) { onTransportFailure = hook; }
    public void setOnTransportSelected(Consumer<String> hook) { onTransportSelected = hook; }
    private volatile String lastJoinedRoomCode = "";
    public String lastJoinedRoomCode() { return lastJoinedRoomCode; }
    public boolean isClientSessionActive() { return currentClientSession != null; }
    public String currentTransportMode() {
        PeerTransport route = clientTargetPeer == null ? null : peerRoutes.get(clientTargetPeer);
        return route == null ? "direct" : route.mode();
    }

    // HOST: one mod-sync coordinator per authorized joiner, keyed by peer address. Lives from
    // the joiner's first 0xE2 datagram (after its punch succeeded) until it graduates to a real
    // relay session (startNewHostConnection) or the world closes (cancelRendezvous). Only an
    // address already in authorizedPeers may open one — same admission gate as handleHostIncoming.
    private final Map<PeerAddress, ModSyncCoordinator> modSyncHostSessions = new ConcurrentHashMap<>();

    // HOST: what to tell joiners about this host's mods; null when the host isn't participating
    // in mod-sync (feature off, or startHost's static local path). Set in startHostViaRendezvous.
    private volatile ModSyncHostProvider modSyncHostProvider;

    // HOST: the in-progress graceful-handoff attempt (offer a chosen joiner the world and
    // leave). null when no handoff is running. One at a time — the host picks one successor.
    // Cleared in cancelRendezvous() and when the attempt reaches a terminal state.
    private final net.peercraft.network.handoff.HandoffCapabilities handoffCapabilities =
            new net.peercraft.network.handoff.HandoffCapabilities(
                    (peer, packet) -> sendRawDatagram(peer.getAddress(), peer.getPort(), packet), 0L);

    private volatile boolean handoffAdmissionClosed;
    public void setHandoffAdmissionClosed(boolean closed) { handoffAdmissionClosed = closed; }
    public boolean handoffAdmissionClosed() { return handoffAdmissionClosed; }
    public void enableSafeHandoff() { handoffCapabilities.enable(net.peercraft.network.handoff.HandoffCapabilities.SAFE_HANDOFF); }
    public String handoffAuthorityHost() throws IOException { return resolveRendezvousAddress().getHostAddress(); }

    public void requireHandoffCapabilities(java.util.Set<java.net.InetSocketAddress> participants) throws IOException {
        handoffCapabilities.require(participants, net.peercraft.network.handoff.HandoffCapabilities.SAFE_HANDOFF, 5_000);
    }

    public interface HandoffControlReceiver {
        void onPacket(byte[] bytes, int length, java.net.InetSocketAddress sender);
        default boolean onWorldPacket(byte[] bytes, int length, java.net.InetSocketAddress sender) { return false; }
    }
    private volatile HandoffControlReceiver independentHandoffReceiver;
    public void setHandoffControlReceiver(HandoffControlReceiver receiver) { independentHandoffReceiver = receiver; }

    private volatile HandoffCoordinator handoffHostSession;
    private volatile net.peercraft.network.handoff.HandoffTransport independentHandoff;

    private volatile java.util.concurrent.CompletableFuture<Void> handoffRetention = java.util.concurrent.CompletableFuture.completedFuture(null);
    /** Called only from a handoff worker, before sending OFFER/PREPARE or stopping a world. */
    public void awaitHandoffRetention(long offerId) throws IOException {
        java.util.concurrent.CompletableFuture<Void> confirmation;
        synchronized (this) {
            if (independentHandoff == null || independentHandoff.offerId != offerId)
                throw new IOException("No matching handoff transport");
            confirmation = handoffRetention;
        }
        try { confirmation.get(20, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Relay retention interrupted", e); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) { throw new IOException("Relay retention is unconfirmed", e); }
    }

    /** V2 operation ownership must be established before the game connection is closed. */
    public synchronized void retainHandoffTransport(long offerId, java.util.Set<java.net.InetSocketAddress> participants) {
        if (independentHandoff != null && !independentHandoff.isClosed())
            throw new IllegalStateException("Another handoff transport is active");
        retainHandoffTransport(offerId, participants, peer -> HandoffProtocol.encodePing());
    }
    public synchronized void retainHandoffTransport(long offerId, java.util.Set<java.net.InetSocketAddress> participants,
            java.util.function.Function<java.net.InetSocketAddress, byte[]> heartbeat) {
        if (independentHandoff != null && !independentHandoff.isClosed())
            throw new IllegalStateException("Another handoff transport is active");
        java.util.List<java.util.concurrent.CompletableFuture<Void>> holds = new java.util.ArrayList<>();
        for (InetSocketAddress peer : participants) {
            PeerAddress logical = new PeerAddress(peer.getAddress(), peer.getPort());
            PeerTransport route = peerRoutes.get(logical);
            if (route != null) { retainedPeerRoutes.put(logical, route); holds.add(route.retainHandoff(offerId)); }
        }
        handoffRetention = java.util.concurrent.CompletableFuture.allOf(holds.toArray(new java.util.concurrent.CompletableFuture<?>[0]));
        independentHandoff = new net.peercraft.network.handoff.HandoffTransport(offerId, participants,
                (peer, bytes) -> sendRawDatagram(peer.getAddress(), peer.getPort(), bytes), heartbeat);
    }

    public synchronized void prepareSourceStop(long offerId) {
        net.peercraft.network.handoff.HandoffTransport transport = independentHandoff;
        if (transport == null || transport.offerId != offerId)
            throw new IllegalStateException("No matching handoff transport");
        if (!handoffRetention.isDone() || handoffRetention.isCompletedExceptionally())
            throw new IllegalStateException("Relay handoff retention is unconfirmed");
        transport.sourceStopping();
    }

    public synchronized void releaseHandoffTransport(long offerId) {
        net.peercraft.network.handoff.HandoffTransport transport = independentHandoff;
        if (transport != null && transport.offerId == offerId) {
            independentHandoff = null;
            transport.close();
            for (Map.Entry<PeerAddress, PeerTransport> entry : retainedPeerRoutes.entrySet()) {
                entry.getValue().releaseHandoff(offerId);
                if (peerRoutes.get(entry.getKey()) != entry.getValue()) closeRoute(entry.getValue());
            }
            retainedPeerRoutes.clear();
        }
    }

    private boolean retainingHandoffTransport() {
        net.peercraft.network.handoff.HandoffTransport transport = independentHandoff;
        return transport != null && !transport.isClosed();
    }

    private volatile net.peercraft.network.handoff.HandoffAuthorityClient handoffAuthority;
    private volatile net.peercraft.network.handoff.HandoffRoomRegistration handoffRoom;
    private volatile String registeredRoomCode;

    public synchronized net.peercraft.network.handoff.HandoffAuthorityClient handoffAuthority() throws IOException {
        if (handoffAuthority == null) {
            InetAddress address = resolveRendezvousAddress();
            if (address == null) throw new IOException("Could not resolve handoff authority");
            handoffAuthority = new net.peercraft.network.handoff.HandoffAuthorityClient(this::sendRawDatagram,
                    address, PeerCraftConfig.rendezvousPort());
        }
        return handoffAuthority;
    }
    public String registeredRoomCode() { return registeredRoomCode; }
    public void prepareHandoffRoom(long offerId) {
        prepareHandoffRoom(new java.util.UUID(0, 0), offerId);
    }
    public void prepareHandoffRoom(java.util.UUID sessionId, long offerId) {
        handoffRoom = new net.peercraft.network.handoff.HandoffRoomRegistration(sessionId, offerId, 180_000);
    }
    public void awaitHandoffRoom(long offerId, Runnable onReady, java.util.function.Consumer<String> onFailed) {
        awaitHandoffRoom(new java.util.UUID(0, 0), offerId, onReady, onFailed);
    }
    public void awaitHandoffRoom(java.util.UUID sessionId, long offerId, Runnable onReady, java.util.function.Consumer<String> onFailed) {
        net.peercraft.network.handoff.HandoffRoomRegistration ticket = handoffRoom;
        if (ticket == null || ticket.offerId != offerId || !ticket.sessionId.equals(sessionId)) { onFailed.accept("peercraft.handoff.abort.transfer_failed"); return; }
        Thread wait = new Thread(() -> {
            try { ticket.await(); onReady.run(); }
            catch (IOException e) { onFailed.accept("peercraft.handoff.abort.no_response"); }
        }, "PeerCraft-Handoff-Room");
        wait.setDaemon(true); wait.start();
    }


    // JOINER: receives handoff control traffic (0xE3) from the current host for the whole
    // time this client is connected to a world — an offer to become the successor, or the
    // "everyone reconnect" MIGRATE broadcast. Installed when the join completes, cleared in
    // endClientSession. Kept out of the rendezvousListener slot on purpose: that slot is
    // recycled during punch/mod-sync, but a MIGRATE can arrive at any point mid-session.
    private volatile HandoffClientAgent handoffClientAgent;

    // Client-layer hook run once per successful join-via-rendezvous, right before the vanilla
    // client connects to the local proxy — set from PeerCraftClientCommon. Keeps GUI/handoff
    // wiring out of this loader-agnostic class.
    private volatile Runnable onClientConnected;

    // World-save archive transfer (0xE4), one direction only: HOST serves it, the chosen
    // SUCCESSOR receives it. hostWorldTransfer lives during a handoff's transfer phase;
    // successorWorldTransfer lives on the successor's client between accepting the offer and
    // its world being written. Both cleared on teardown.
    private volatile WorldTransfer hostWorldTransfer;
    private volatile WorldTransfer successorWorldTransfer;
    /** HOST: the peer we're currently handing the world to — so a mid-transfer drop can abort cleanly. */
    private volatile PeerAddress handoffSuccessorPeer;

    // HOST: latest "don't consider me as a successor" preference per joiner (0xE3
    // T_SUCCESSOR_PREFERENCE), independent of any in-progress handoff attempt — a joiner sends
    // this once right after connecting and again whenever they flip the setting. Absent =
    // willing (default). Cleared in cancelRendezvous(), same lifetime as authorizedPeers.
    private final Map<PeerAddress, Boolean> successorOptOutByAddress = new ConcurrentHashMap<>();

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
    private volatile ClientJoinAttempt currentJoinAttempt;

    /** Ownership of one join, including mod sync and the vanilla connection screen. */
    public final class ClientJoinAttempt {
        private volatile boolean cancelled;
        private final ModSyncAgent modSync;
        private ClientJoinAttempt(ModSyncAgent modSync) { this.modSync = modSync; }
        public boolean isCurrent() { return currentJoinAttempt == this && !cancelled; }
        public boolean isBusy() { return isCurrent() && rendezvousClientBusy.get(); }
        public void cancel() {
            synchronized (P2PBridge.this) {
                if (!isBusy() || retainingHandoffTransport()) return;
                cancelled = true; // Invalidate queued callbacks before releasing resources.
                if (modSync != null) modSync.cancel();
                ClientSession session = currentClientSession;
                if (session != null) endClientSession(session.sessionId);
                if (proxy != null) proxy.disconnectClient();
                releaseClientJoin();
            }
        }
    }

    private void releaseClientJoin() {
        closeClientRoute();
        clearRendezvousListener();
        modSyncActive.set(false);
        clientTargetPeer = null;
        rendezvousClientBusy.set(false);
    }

    private void forClientAttempt(ConnectListener listener, Runnable action) {
        synchronized (this) {
            if (listener.isCurrentAttempt()) action.run();
        }
    }

    // JOINER: true while a mod-sync handshake/transfer is running in the rendezvousListener slot
    // (between a successful punch and onConnected/abort). The busy-watchdog must not touch the
    // listener or the busy flag while this is set — a large P2P mod transfer legitimately keeps
    // rendezvousClientBusy true with no ClientSession for minutes. Cleared in every ModSyncAgent
    // Outcome path (and abortModSyncClient).
    private final AtomicBoolean modSyncActive = new AtomicBoolean(false);

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
        startHostViaRendezvous(mcPort, maxPlayers, false, listener);
    }

    /** As {@link #startHostViaRendezvous(int, int, HostListener)}, but additionally gates JOIN to the host's PeerCraft friends list (Phase 6, no-op if the host isn't logged into an account — see RendezvousClient.registerRoom). */
    public void startHostViaRendezvous(int mcPort, int maxPlayers, boolean friendsOnly, HostListener listener) {
        startHostViaRendezvous(mcPort, maxPlayers, friendsOnly, false, "", "", listener);
    }

    /**
     * As {@link #startHostViaRendezvous(int, int, boolean, HostListener)}, but additionally
     * lists the room in the public game browser (Phase 7, works with or without an account —
     * see RendezvousClient.registerRoom). {@code worldName}/{@code mcVersion} are only
     * meaningful while {@code publicRoom} is true — {@code mcVersion} isn't auto-detected here:
     * this class has no {@code net.minecraft.*} imports (it's called from Mixins that do), so
     * the caller computes it and passes it in, same as {@code worldName}.
     */
    public void startHostViaRendezvous(int mcPort, int maxPlayers, boolean friendsOnly, boolean publicRoom, String worldName, String mcVersion, HostListener listener) {
        startHostViaRendezvous(mcPort, maxPlayers, friendsOnly, publicRoom, worldName, mcVersion, listener, null);
    }

    /**
     * As {@link #startHostViaRendezvous(int, int, boolean, boolean, String, String, HostListener)},
     * but also answers joiners' mod-sync handshakes from {@code modSyncProvider} (see
     * {@link ModSyncHostProvider}). A {@code null} provider means this host doesn't participate —
     * joiners get no manifest and connect straight through.
     */
    public void startHostViaRendezvous(int mcPort, int maxPlayers, boolean friendsOnly, boolean publicRoom, String worldName, String mcVersion, HostListener listener, ModSyncHostProvider modSyncProvider) {
        this.isHost = true;
        this.localMinecraftPort = mcPort;
        this.maxPlayers = maxPlayers;
        this.modSyncHostProvider = modSyncProvider;
        if (!restartReceiver(PeerCraftConfig.hostUdpPort())) {
            // The UDP socket couldn't bind (port already taken — most often a second running
            // Minecraft client with PeerCraft). Without this the RendezvousClient below would
            // send REGISTER through a null socket for CONNECT_TIMEOUT_MILLIS and then report
            // the misleading "rendezvous server did not respond".
            listener.onFailed("peercraft.p2p.fail.udp_port_busy");
            return;
        }

        InetAddress rendezvousAddress = resolveRendezvousAddress();
        if (rendezvousAddress == null) {
            listener.onFailed("peercraft.p2p.fail.resolve_rendezvous");
            return;
        }
        int rendezvousPort = PeerCraftConfig.rendezvousPort();

        LOGGER.info("[P2PBridge] Регистрируемся на сервере знакомств {}:{} (макс. игроков: {})...", rendezvousAddress.getHostAddress(), rendezvousPort, maxPlayers);
        RendezvousClient client = new RendezvousClient(sender, rendezvousAddress, rendezvousPort);
        client.setConnectivityAdvertisement(new RendezvousProtocol.ConnectivityAdvertisement(
                PeerCraftConfig.relayEnabled(), PeerCraftConfig.relayEnabled(), DirectCandidates.gather(receiver.getBoundPort())));
        setHostRendezvousClient(client);

        // Attach the host's logged-in account (if any) so their room shows up as "hosting" in
        // friends' presence — anonymous hosting (no account) is unchanged, see
        // RendezvousClient.registerRoom's two overloads.
        net.peercraft.network.account.AccountClient.AccountSession session =
                net.peercraft.network.account.AccountClient.INSTANCE.getCurrentSession();

        final net.peercraft.network.handoff.HandoffRoomRegistration registration = this.handoffRoom;
        client.registerRoom(
                maxPlayers,
                this::currentPlayerCount,
                session != null ? session.accountId() : null,
                session != null ? session.sessionToken() : null,
                friendsOnly,
                publicRoom,
                worldName,
                mcVersion,
                (code, changed) -> {
                    if (this.hostRendezvousClient != client) return;
                    registeredRoomCode = code;
                    if (registration != null && registration == this.handoffRoom) registration.registered(code);
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
                    @Override public void onMatchedDetailed(RendezvousProtocol.PeerFound info) {
                        if (!info.networkOffer().isPresent()) { onMatched(info.peer(), info.token()); return; }
                        beginNegotiatedConnection(info, true, registeredRoomCode, rendezvousAddress, rendezvousPort,
                                session != null ? session.sessionToken() : null, LOGGING_LISTENER, null);
                    }

                    @Override
                    public void onFailed(String reason) {
                        LOGGER.error("[P2PBridge] Не удалось создать комнату на сервере знакомств: {}", reason);
                        if (registration != null && registration == handoffRoom) registration.failed(reason);
                        listener.onFailed(reason); // key from RendezvousClient; OpenToLanMixin nest-translates it
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
        default void onStarted(ClientJoinAttempt attempt) {}
        default boolean isCurrentAttempt() { return true; }
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
        startClientViaRendezvous(code, rendezvousHost, rendezvousPort, listener, null);
    }

    /**
     * As {@link #startClientViaRendezvous(String, String, int, ConnectListener)}, but once the
     * NAT punch succeeds — and before {@code listener.onConnected()} — runs {@code modSync}'s
     * mod-sync handshake with the host over the punched link. A {@code null} agent (or
     * {@code peercraft.modSync=false}) skips it and connects immediately, exactly as before.
     */
    public synchronized void startClientViaRendezvous(String code, String rendezvousHost, int rendezvousPort, ConnectListener listener, ModSyncAgent modSync) {
        //? if >=1.17
        if (code == null || code.isBlank()) {
        //? if <1.17
        /*if (code == null || code.trim().isEmpty()) {*/
            listener.onFailed("peercraft.p2p.fail.no_code");
            return;
        }

        // Prevents a repeated click on "Connect" from silently tearing down an
        // already-in-progress or already-established connection — see rendezvousClientBusy.
        // Reset either on failure (below, via guardedListener), or when the local client
        // actually disconnects from the world (endClientSession).
        if (!rendezvousClientBusy.compareAndSet(false, true)) {
            listener.onFailed("peercraft.p2p.fail.already_connecting");
            return;
        }
        final ClientJoinAttempt attempt = new ClientJoinAttempt(modSync);
        currentJoinAttempt = attempt;
        listener.onStarted(attempt);
        armBusyWatchdog(attempt);
        ConnectListener guardedListener = new ConnectListener() {
            @Override public boolean isCurrentAttempt() { return attempt.isBusy(); }
            @Override public void onStatus(String message) {
                forClientAttempt(this, () -> listener.onStatus(message));
            }
            @Override public void onConnected() {
                forClientAttempt(this, () -> {
                    Runnable hook = onClientConnected;
                    if (hook != null) {
                        try { hook.run(); }
                        catch (RuntimeException e) {
                            LOGGER.warn("[P2PBridge] onClientConnected hook threw: {}", e.toString());
                        }
                    }
                    listener.onConnected();
                });
            }
            @Override public void onFailed(String reason) {
                forClientAttempt(this, () -> {
                    releaseClientJoin();
                    listener.onFailed(reason);
                });
            }
        };

        lastJoinedRoomCode = code.trim();
        this.isHost = false;
        // Someone who was hosting earlier in this same launch (and so stopped LocalProxy
        // when opening to LAN — see OpenToLanMixin) may have left the world and now wants to
        // join by code: without this, 127.0.0.1:<proxyPort> would be a dead port by the time
        // ConnectScreen.startConnecting(...) runs.
        if (!isProxyRunning()) {
            startProxy(PeerCraftConfig.proxyPort());
        }
        if (!isProxyRunning()) {
            guardedListener.onFailed("peercraft.p2p.fail.proxy_port_busy");
            return;
        }
        if (!restartReceiver(PeerCraftConfig.clientUdpPort())) {
            // Port already taken (most often a second running Minecraft client with PeerCraft) —
            // fail loudly now instead of letting the join stall for CONNECT_TIMEOUT_MILLIS and
            // surface as the misleading "rendezvous server did not respond".
            guardedListener.onFailed("peercraft.p2p.fail.udp_port_busy");
            return;
        }

        InetAddress rendezvousAddress = resolveRendezvousAddress(rendezvousHost, guardedListener);
        if (rendezvousAddress == null) {
            return;
        }

        guardedListener.onStatus("peercraft.p2p.status.joining");
        RendezvousClient client = new RendezvousClient(sender, rendezvousAddress, rendezvousPort);
        client.setConnectivityAdvertisement(new RendezvousProtocol.ConnectivityAdvertisement(
                PeerCraftConfig.relayEnabled(), PeerCraftConfig.relayEnabled(), DirectCandidates.gather(receiver.getBoundPort())));
        setRendezvousListener(client);

        // Attaching our account (if logged in) lets the host identify us for save-data
        // isolation (Phase 5) — anonymous joining (no account) is unchanged.
        net.peercraft.network.account.AccountClient.AccountSession joinerSession =
                net.peercraft.network.account.AccountClient.INSTANCE.getCurrentSession();
        RendezvousClient.MatchCallback matchCallback = new RendezvousClient.MatchCallback() {
            @Override
            public void onMatched(RendezvousProtocol.Address peer, long token) {
                forClientAttempt(guardedListener, () -> beginClientPunch(peer, token, guardedListener, modSync));
            }
            @Override public void onMatchedDetailed(RendezvousProtocol.PeerFound info) {
                if (!info.networkOffer().isPresent()) { onMatched(info.peer(), info.token()); return; }
                forClientAttempt(guardedListener, () -> beginNegotiatedConnection(info, false, code, rendezvousAddress, rendezvousPort,
                        joinerSession != null ? joinerSession.sessionToken() : null, guardedListener, modSync));
            }

            @Override
            public void onFailed(String reason) {
                guardedListener.onFailed(reason); // reason is a translation key (see RendezvousClient.describeReason)
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
    public synchronized void endClientSession(long sessionId) {
        ClientSession session = this.currentClientSession;
        if (session == null || session.sessionId != sessionId) return;
        ClientJoinAttempt attempt = currentJoinAttempt;
        if (attempt != null) attempt.cancelled = true;
        if (session != null && session.sessionId == sessionId) {
            // Tell the host we're gone *now* with a best-effort FIN, so it drops its TCP
            // connection to the integrated server and that server runs its normal
            // player-quit path immediately. Without this the host only notices once its
            // own keep-alive to us times out (~15-30s), and until then the joiner's
            // avatar is still standing in the world — damageable, and not kickable.
            PeerAddress dest = this.clientTargetPeer;
            if (dest != null) {
                sendFramed(dest, sessionId, session.outSeq.getAndIncrement(), (byte) FramedPacket.FLAG_FIN, new byte[0]);
            }
            this.currentClientSession = null;
        }
        // The handoff GUI (HostMigrationScreen / the successor launcher) captures everything
        // it needs from the onMigrate/onOffer callback synchronously, so the agent can go
        // when the session ends — a fresh one is installed on the next join.
        if (retainingHandoffTransport()) {
            rendezvousClientBusy.set(false);
            return;
        }
        closeClientRoute();
        this.handoffClientAgent = null;
        WorldTransfer wt = this.successorWorldTransfer;
        if (wt != null) {
            // Keep a running receive alive across the disconnect only if it hasn't finished —
            // the successor's transfer legitimately continues while the vanilla client drops.
            // A finished/stopped one is dead weight; drop it.
            this.successorWorldTransfer = null;
            wt.stop();
        }
        rendezvousClientBusy.set(false);
    }

    // Called by a non-successor joiner (HostMigrationScreen) right before reconnecting to the
    // new host after a handoff MIGRATE. At that point the vanilla client is still nominally
    // connected to the OLD host — the caller disconnects it, but that teardown is async (the
    // LocalProxy socket close that would normally clear rendezvousClientBusy via
    // endClientSession hasn't necessarily been noticed yet), so the very next
    // startClientViaRendezvous(...) call for the NEW host would otherwise be rejected as
    // "already connecting". Clears this bridge's own client-session bookkeeping immediately
    // instead of waiting on that.
    public void prepareForHandoffReconnect() {
        this.currentClientSession = null;
        this.handoffClientAgent = null;
        rendezvousClientBusy.set(false);
    }

    private InetAddress resolveRendezvousAddress() {
        return resolveRendezvousAddress(PeerCraftConfig.rendezvousHost(), LOGGING_LISTENER);
    }

    private InetAddress resolveRendezvousAddress(String host, ConnectListener listener) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            listener.onFailed("peercraft.p2p.fail.resolve_rendezvous");
            return null;
        }
    }

    private static final class NetworkAttempt {
        final PeerAddress peer;
        final RendezvousProtocol.NetworkOffer offer;
        final boolean host;
        final ConnectListener listener;
        final ModSyncAgent modSync;
        final AtomicBoolean selected = new AtomicBoolean(), relayStarted = new AtomicBoolean();
        volatile boolean cancelled;
        volatile RelayPeerTransport relay;
        NetworkAttempt(PeerAddress peer, RendezvousProtocol.NetworkOffer offer, boolean host,
                ConnectListener listener, ModSyncAgent modSync) {
            this.peer = peer; this.offer = offer; this.host = host; this.listener = listener; this.modSync = modSync;
        }
    }
    private static final class DirectBinding {
        final PeerAddress peer; final DirectPeerTransport route;
        DirectBinding(PeerAddress peer, DirectPeerTransport route) { this.peer = peer; this.route = route; }
    }

    private int pendingNetworkAttemptCount() {
        int count = 0;
        for (NetworkAttempt attempt : networkAttempts.values()) if (!attempt.selected.get() && !attempt.cancelled) count++;
        return count;
    }
    private void beginNegotiatedConnection(RendezvousProtocol.PeerFound info, boolean hostRole,
            String room, InetAddress rendezvousAddress, int rendezvousPort, byte[] sessionToken, ConnectListener listener, ModSyncAgent modSync) {
        PeerAddress peer = new PeerAddress(info.peer().host(), info.peer().port());
        NetworkAttempt existing = networkAttempts.get(peer);
        if (existing != null && existing.offer.attemptId().equals(info.networkOffer().get().attemptId())) return;
        if (hostRole && existing == null && hostConnectionsByAddress.size() + activePunches.size()
                + pendingNetworkAttemptCount() >= maxPlayers) return;
        if (existing != null) cancelAttempt(existing);
        if (hostRole && info.joinerAccountId().isPresent()) joinerAccountIdByAddress.put(peer, info.joinerAccountId().get());
        NetworkAttempt attempt = new NetworkAttempt(peer, info.networkOffer().get(), hostRole, listener, modSync);
        networkAttempts.put(peer, attempt);
        if (!hostRole) clientTargetPeer = peer;
        listener.onStatus("peercraft.p2p.status.checking_direct");
        DirectConnectivityCoordinator checks = new DirectConnectivityCoordinator(sender, peer, attempt.offer, hostRole,
                new DirectConnectivityCoordinator.Callback() {
                    @Override public void onSuccess(String ip, int port) {
                        synchronized (P2PBridge.this) {
                            try {
                                DirectPeerTransport route = new DirectPeerTransport(sender,
                                        new InetSocketAddress(InetAddress.getByName(ip), port), attempt.offer.attemptId(),
                                        attempt.offer.challengeKey(), hostRole);
                                if (selectRoute(attempt, route)) {
                                    directBindings.put(attempt.offer.attemptId(), new DirectBinding(peer, route));
                                    RelayPeerTransport pending = attempt.relay;
                                    if (pending != null) pending.close();
                                    finishConnection(attempt, route);
                                }
                            } catch (UnknownHostException error) { failAttempt(attempt, "peercraft.p2p.fail.hole_punching"); }
                        }
                    }
                    @Override public void onFailure(String reason) {
                        DirectConnectivityCoordinator checks = activeDirectChecks.get(attempt.peer);
                        LOGGER.info("[P2PBridge] {}: {}; candidates={}", attempt.offer.attemptId(), reason,
                                checks == null ? java.util.Collections.emptyMap() : checks.diagnostics());
                        beginRelay(attempt, info.token(), room, sessionToken);
                    }
                });
        DirectConnectivityCoordinator previous = activeDirectChecks.put(peer, checks);
        if (previous != null) previous.cancel();
        checks.setCandidateRelay(rendezvousAddress, rendezvousPort);
        try { checks.addDiscoveryServer(InetAddress.getByName("stun.cloudflare.com"), 3478); }
        catch (UnknownHostException unavailable) { LOGGER.debug("[P2PBridge] STUN discovery unavailable"); }
        checks.start();
    }

    private void beginRelay(NetworkAttempt attempt, long token, String room, byte[] sessionToken) {
        if (attempt.cancelled || attempt.selected.get() || !attempt.relayStarted.compareAndSet(false, true)) return;
        String refusal = null;
        if (sessionToken == null) refusal = "peercraft.p2p.fail.relay_account_required";
        else if (!PeerCraftConfig.relayEnabled()) refusal = "peercraft.p2p.fail.relay_disabled";
        else if (!attempt.offer.relayCapable()) refusal = "peercraft.p2p.fail.relay_unavailable";
        else if (PeerCraftConfig.relayBrokerUrl().isEmpty()
                || !PeerCraftConfig.relayBrokerUrl().equals(attempt.offer.brokerUrl())) refusal = "peercraft.p2p.fail.relay_not_configured";
        if (refusal != null) { failAttempt(attempt, refusal); return; }
        try {
            RelayBrokerClient broker = new RelayBrokerClient(PeerCraftConfig.relayBrokerUrl(), sessionToken);
            RelayPeerTransport relay = new RelayPeerTransport(broker, token, room, attempt.offer.attemptId(), attempt.host,
                    new RelayPeerTransport.Listener() {
                        @Override public void onConnected(RelayPeerTransport route) {
                            if (!selectRoute(attempt, route)) return;
                            DirectConnectivityCoordinator checks = activeDirectChecks.remove(attempt.peer);
                            if (checks != null) checks.cancel();
                            finishConnection(attempt, route);
                        }
                        @Override public void onData(byte[] bytes) {
                            PeerTransport route = attempt.relay;
                            if ((!attempt.cancelled || retainedPeerRoutes.get(attempt.peer) == route) && (peerRoutes.get(attempt.peer) == route
                                    || retainedPeerRoutes.get(attempt.peer) == route))
                                dispatchDecoded(bytes, bytes.length, attempt.peer.host(), attempt.peer.port());
                        }
                        @Override public void onFailure(String reason) {
                            if (!attempt.selected.get()) failAttempt(attempt, reason);
                            else if (peerRoutes.get(attempt.peer) == attempt.relay
                                    || retainedPeerRoutes.get(attempt.peer) == attempt.relay)
                                transportFailed(attempt.peer, attempt.relay, reason, attempt.host);
                        }
                    });
            attempt.relay = relay;
            if (attempt.cancelled || attempt.selected.get()) { relay.close(); return; }
            attempt.listener.onStatus("peercraft.p2p.status.relay_connecting");
            relay.start();
        } catch (IOException error) { failAttempt(attempt, "peercraft.p2p.fail.relay_not_configured"); }
    }

    private synchronized boolean selectRoute(NetworkAttempt attempt, PeerTransport route) {
        if (attempt.cancelled || (!attempt.host && !attempt.listener.isCurrentAttempt()) || networkAttempts.get(attempt.peer) != attempt
                || !attempt.selected.compareAndSet(false, true)) { route.close(); return false; }
        PeerTransport previous = peerRoutes.put(attempt.peer, route);
        if (previous != null && retainedPeerRoutes.get(attempt.peer) != previous) closeRoute(previous);
        securedPeers.add(attempt.peer);
        authorizedPeers.add(attempt.peer);
        if (!attempt.host) clientTargetPeer = attempt.peer;
        return true;
    }
    private synchronized void finishConnection(NetworkAttempt attempt, PeerTransport route) {
        if (attempt.cancelled || (!attempt.host && !attempt.listener.isCurrentAttempt())) return;
        attempt.listener.onStatus("peercraft.p2p.status." + route.mode() + "_connected");
        Consumer<String> modeHook = onTransportSelected;
        if (modeHook != null && !attempt.host) modeHook.accept("peercraft.p2p.mode." + route.mode());
        if (attempt.host) return;
        clearRendezvousListener();
        if (attempt.modSync == null || PeerCraftConfig.modSyncClientMode() == ModSyncMode.OFF) attempt.listener.onConnected();
        else runModSyncHandshake(attempt.modSync, attempt.listener);
    }
    private synchronized void failAttempt(NetworkAttempt attempt, String reason) {
        if (attempt.cancelled || attempt.selected.get() || networkAttempts.get(attempt.peer) != attempt) return;
        cancelAttempt(attempt);
        networkAttempts.remove(attempt.peer, attempt);
        if (!attempt.host) clearRendezvousListener();
        attempt.listener.onFailed(reason);
    }
    private void cancelAttempt(NetworkAttempt attempt) {
        attempt.cancelled = true;
        DirectConnectivityCoordinator checks = activeDirectChecks.remove(attempt.peer);
        if (checks != null) checks.cancel();
        if (attempt.relay != null && retainedPeerRoutes.get(attempt.peer) != attempt.relay) attempt.relay.close();
    }
    private void closeRoute(PeerTransport route) {
        route.close();
        for (Map.Entry<UUID, DirectBinding> binding : directBindings.entrySet())
            if (binding.getValue().route == route) directBindings.remove(binding.getKey(), binding.getValue());
    }
    private synchronized void transportFailed(PeerAddress peer, PeerTransport route, String reason, boolean hostRole) {
        if (route == null || (peerRoutes.get(peer) != route && retainedPeerRoutes.get(peer) != route)) return;
        boolean retained = retainedPeerRoutes.get(peer) == route;
        peerRoutes.remove(peer, route); retainedPeerRoutes.remove(peer, route); closeRoute(route);
        WorldTransfer transfer = hostRole ? hostWorldTransfer : successorWorldTransfer;
        if (transfer != null && (hostRole ? peer.equals(handoffSuccessorPeer)
                : peer.equals(clientTargetPeer) || retained))
            transfer.transportFailed("peercraft.handoff.abort.transfer_failed");
        authorizedPeers.remove(peer);
        NetworkAttempt attempt = networkAttempts.get(peer);
        if (attempt != null) { cancelAttempt(attempt); networkAttempts.remove(peer, attempt); }
        if (hostRole) {
            HostConnection conn = hostConnectionsByAddress.get(peer);
            if (conn != null) closeHostConnection(conn);
        } else {
            Consumer<String> hook = onTransportFailure;
            if (hook != null && (currentClientSession != null || retainingHandoffTransport())) hook.accept(reason);
            if (proxy != null) proxy.disconnectClient();
            rendezvousClientBusy.set(false);
            if (attempt != null && currentClientSession == null) attempt.listener.onFailed(reason);
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
                try {
                    PeerAddress actual = new PeerAddress(InetAddress.getByName(ip), port);
                    authorizedPeers.add(actual);
                    if (joinerAccountId.isPresent()) joinerAccountIdByAddress.put(actual, joinerAccountId.get());
                } catch (UnknownHostException invalid) { return; }
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
    private void beginClientPunch(RendezvousProtocol.Address peer, long token, ConnectListener listener, ModSyncAgent modSync) {
        LOGGER.info("[P2PBridge] Пир найден: {}:{}, начинаем hole punching...", peer.host().getHostAddress(), peer.port());
        listener.onStatus("peercraft.p2p.status.peer_found");
        PunchCoordinator punch = new PunchCoordinator(sender, peer, token, new PunchCoordinator.Callback() {
            @Override
            public void onSuccess(String ip, int port) {
                forClientAttempt(listener, () -> {
                    clearRendezvousListener();
                    setClientTargetPeer(ip, port);
                    LOGGER.info("[P2PBridge] P2P-соединение установлено напрямую с {}:{}", ip, port);
                    if (modSync == null || PeerCraftConfig.modSyncClientMode() == ModSyncMode.OFF) {
                        listener.onConnected();
                        return;
                    }
                    runModSyncHandshake(modSync, listener);
                });
            }

            @Override
            public void onFailure(String reason) {
                forClientAttempt(listener, () -> {
                    clearRendezvousListener();
                    LOGGER.error("[P2PBridge] Hole punching не удался: {}", reason);
                    listener.onFailed("peercraft.p2p.fail.hole_punching");
                });
            }
        });
        setRendezvousListener(punch);
        punch.start();
    }

    // JOINER: the mod-sync handshake occupies the single rendezvousListener slot between a
    // successful punch and onConnected(). The agent (client layer) drives the handshake over
    // the link and calls exactly one Outcome method.
    private void runModSyncHandshake(ModSyncAgent modSync, ConnectListener listener) {
        modSyncActive.set(true);
        ModSyncLink link = new ModSyncLink() {
            @Override
            public void send(byte[] data) {
                forClientAttempt(listener, () -> sendEncoded(clientTargetPeer, data));
            }

            @Override
            public void bindInbound(RawPacketListener inbound) {
                forClientAttempt(listener, () -> setRendezvousListener(inbound));
            }

            @Override
            public void unbind() {
                forClientAttempt(listener, () -> clearRendezvousListener());
            }
        };
        modSync.run(link, new ModSyncAgent.Outcome() {
            @Override
            public void proceedToConnect() {
                forClientAttempt(listener, () -> {
                    modSyncActive.set(false);
                    clearRendezvousListener();
                    listener.onConnected();
                });
            }

            @Override
            public void abortJoin() {
                forClientAttempt(listener, () -> {
                    modSyncActive.set(false);
                    abortModSyncClient();
                });
            }

            @Override
            public void fail(String reasonKey) {
                forClientAttempt(listener, () -> {
                    modSyncActive.set(false);
                    clearRendezvousListener();
                    listener.onFailed(reasonKey);
                });
            }
        });
    }

    // JOINER: mod sync finished without a live LocalProxy TCP session (mods installed and a
    // restart is needed, or the player cancelled), so neither endClientSession nor
    // guardedListener.onFailed will reset rendezvousClientBusy — do it here, or a second
    // "Connect" this launch wedges on peercraft.p2p.fail.already_connecting.
    private void closeClientRoute() {
        if (retainingHandoffTransport() || clientTargetPeer == null) return;
        Consumer<String> modeHook = onTransportSelected;
        if (modeHook != null) modeHook.accept(null);
        NetworkAttempt attempt = networkAttempts.remove(clientTargetPeer);
        if (attempt != null) cancelAttempt(attempt);
        PeerTransport route = peerRoutes.remove(clientTargetPeer);
        if (route != null) closeRoute(route);
        authorizedPeers.remove(clientTargetPeer);
        securedPeers.remove(clientTargetPeer);
    }
    public synchronized void abortModSyncClient() {
        ClientJoinAttempt attempt = currentJoinAttempt;
        if (attempt != null) attempt.cancelled = true;
        releaseClientJoin();
    }

    // Last-resort timeout for callers without a UI owner. Normal screen cancellation is
    // handled on the client tick. Each watchdog owns exactly one attempt, so an old timer
    // cannot release a newer join. Active mod sync gets a longer bounded deadline.
    private void armBusyWatchdog(ClientJoinAttempt attempt) {
        Thread t = new Thread(() -> {
            long start = System.currentTimeMillis();
            long idleLimitMillis = 120_000L;
            long absoluteCapMillis = 40 * 60_000L;
            while (true) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!attempt.isBusy() || currentClientSession != null) {
                    return; // resolved normally (connected, failed, or cleared elsewhere)
                }
                long elapsed = System.currentTimeMillis() - start;
                if (modSyncActive.get()) {
                    if (elapsed < absoluteCapMillis) {
                        continue; // mod sync still working — keep waiting
                    }
                    LOGGER.warn("[P2PBridge] Mod sync висит уже {} мин — принудительно снимаем флаг \"идёт подключение\".", elapsed / 60_000);
                } else if (elapsed < idleLimitMillis) {
                    continue;
                } else {
                    LOGGER.warn("[P2PBridge] Подключение так и не открыло локальную сессию за {} с — снимаем флаг \"идёт подключение\".", elapsed / 1000);
                }
                attempt.cancel();
                return;
            }
        }, "PeerCraft-Busy-Watchdog");
        t.setDaemon(true);
        t.start();
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
        net.peercraft.network.handoff.HandoffTransport transport = independentHandoff;
        if (transport != null && transport.preservesSourceStop()) return;
        cancelRendezvousUnconditionally();
    }

    private void cancelRendezvousUnconditionally() {
        setHostRendezvousClient(null);
        clearRendezvousListener();
        for (NetworkAttempt attempt : networkAttempts.values()) cancelAttempt(attempt);
        networkAttempts.clear();
        for (Map.Entry<PeerAddress, PeerTransport> entry : peerRoutes.entrySet()) {
            if (retainedPeerRoutes.get(entry.getKey()) != entry.getValue()) closeRoute(entry.getValue());
        }
        peerRoutes.clear();
        securedPeers.removeIf(peer -> !retainedPeerRoutes.containsKey(peer));
        for (PeerAddress addr : activePunches.keySet()) {
            PunchCoordinator punch = activePunches.remove(addr);
            if (punch != null) {
                punch.cancel();
            }
        }
        for (PeerAddress addr : modSyncHostSessions.keySet()) {
            ModSyncCoordinator c = modSyncHostSessions.remove(addr);
            if (c != null) {
                c.cancel();
            }
        }
        this.modSyncHostProvider = null;
        HandoffCoordinator handoff = this.handoffHostSession;
        this.handoffHostSession = null;
        if (handoff != null && !handoff.isTerminal()) {
            handoff.cancel("peercraft.handoff.abort.world_closed");
        }
        WorldTransfer wt = this.hostWorldTransfer;
        this.hostWorldTransfer = null;
        this.handoffSuccessorPeer = null;
        if (wt != null) {
            wt.stop();
        }
        authorizedPeers.clear();
        joinerAccountIdByAddress.clear();
        successorOptOutByAddress.clear();
    }

    // ================= host handoff (graceful "baton pass") =================

    /**
     * One currently-connected joiner, as material for the host's successor picker.
     * {@code localPort} is the loopback TCP port that joiner's relay connection uses to the
     * integrated server — the reliable key to correlate this candidate with a {@code ServerPlayer}
     * (their {@code connection.getRemoteAddress()} port), since a licensed player's profile UUID
     * is their Mojang UUID, not their PeerCraft {@code accountId}.
     *
     * <p>Plain class rather than a record: {@code P2PBridge} is shared into the Java-8 1.16.5
     * backport build, where records don't compile.
     */
    public static final class HandoffCandidate {
        private final PeerAddress peer;
        private final java.util.UUID accountId;
        private final long sessionId;
        private final int localPort;
        private final boolean declinedSuccessor;

        public HandoffCandidate(PeerAddress peer, java.util.UUID accountId, long sessionId, int localPort, boolean declinedSuccessor) {
            this.peer = peer;
            this.accountId = accountId;
            this.sessionId = sessionId;
            this.localPort = localPort;
            this.declinedSuccessor = declinedSuccessor;
        }

        public PeerAddress peer() {
            return peer;
        }

        public java.util.UUID accountId() {
            return accountId;
        }

        public long sessionId() {
            return sessionId;
        }

        public int localPort() {
            return localPort;
        }

        public boolean signedIn() {
            return accountId != null;
        }

        /** True if this joiner has opted out of being chosen as a handoff successor (their own setting). */
        public boolean declinedSuccessor() {
            return declinedSuccessor;
        }
    }

    /** HOST: the joiners connected right now (a snapshot), for the "hand off hosting" screen. */
    public java.util.List<HandoffCandidate> connectedJoiners() {
        java.util.List<HandoffCandidate> out = new java.util.ArrayList<>();
        for (Map.Entry<PeerAddress, HostConnection> e : hostConnectionsByAddress.entrySet()) {
            out.add(new HandoffCandidate(e.getKey(), joinerAccountIdByAddress.get(e.getKey()),
                    e.getValue().sessionId, e.getValue().localPort,
                    Boolean.TRUE.equals(successorOptOutByAddress.get(e.getKey()))));
        }
        return out;
    }

    public boolean handoffInProgress() {
        HandoffCoordinator s = this.handoffHostSession;
        return s != null && !s.isTerminal();
    }

    /** HOST: true while this instance is hosting a world over the rendezvous server (the only case handoff applies to). */
    public boolean isHostingViaRendezvous() {
        return this.isHost && this.hostRendezvousClient != null;
    }

    /**
     * HOST: begin a graceful handoff to {@code successor}. Sends the offer, and on ACCEPT runs
     * {@code transfer} (the M2 archive+ship step) then broadcasts MIGRATE to every joiner.
     * A {@code null} return means a handoff is already running.
     */
    public HandoffCoordinator beginHandoff(PeerAddress successor, HandoffProtocol.Offer offer,
                                           java.util.UUID successorAccountId,
                                           HandoffCoordinator.Transfer transfer,
                                           HandoffCoordinator.Callbacks callbacks) {
        if (!this.isHost || handoffInProgress()) {
            return null;
        }
        HandoffCoordinator.PeerSender peerSender =
                this::sendRawDatagram;
        java.util.function.Supplier<java.util.List<java.net.SocketAddress>> joiners = () -> {
            java.util.List<java.net.SocketAddress> list = new java.util.ArrayList<>();
            for (PeerAddress p : hostConnectionsByAddress.keySet()) {
                list.add(new java.net.InetSocketAddress(p.host(), p.port()));
            }
            return list;
        };
        java.util.Set<java.net.InetSocketAddress> participants = new java.util.HashSet<>();
        for (PeerAddress peer : hostConnectionsByAddress.keySet())
            participants.add(new java.net.InetSocketAddress(peer.host(), peer.port()));
        participants.add(new java.net.InetSocketAddress(successor.host(), successor.port()));
        retainHandoffTransport(offer.offerId(), participants);
        this.handoffSuccessorPeer = successor;
        HandoffCoordinator session = HandoffCoordinator.start(offer, successor.host(), successor.port(),
                successorAccountId, peerSender, joiners, transfer,
                wrapClearingCallbacks(callbacks, offer.offerId()),
                () -> awaitHandoffRetention(offer.offerId()));
        this.handoffHostSession = session;
        return session;
    }

    private HandoffCoordinator.Callbacks wrapClearingCallbacks(HandoffCoordinator.Callbacks inner, long offerId) {
        return new HandoffCoordinator.Callbacks() {
            @Override public void onAccepted() { inner.onAccepted(); }
            @Override public void onDeclined(String reasonKey) { clear(); inner.onDeclined(reasonKey); }
            @Override public void onSuccessorReady() { clear(); inner.onSuccessorReady(); }
            @Override public void onAborted(String reasonKey) { clear(); inner.onAborted(reasonKey); }
            @Override public void onStatus(String messageKey) { inner.onStatus(messageKey); }
            private void clear() {
                releaseHandoffTransport(offerId);
                handoffHostSession = null;
                handoffSuccessorPeer = null;
                WorldTransfer wt = hostWorldTransfer;
                hostWorldTransfer = null;
                if (wt != null) {
                    wt.stop();
                }
            }
        };
    }

    /** JOINER: install the agent that listens for handoff traffic from the host for this session. */
    public void installHandoffClientAgent(HandoffClientAgent agent) {
        if (agent != null) agent.setRetention(new HandoffClientAgent.Retention() {
            public void retain(long offer, InetAddress host, int port) throws IOException {
                synchronized (P2PBridge.this) {
                    if (independentHandoff == null || independentHandoff.isClosed())
                        retainHandoffTransport(offer, java.util.Collections.singleton(new InetSocketAddress(host, port)));
                    else if (independentHandoff.offerId != offer)
                        throw new IOException("Another handoff transport is active");
                }
                awaitHandoffRetention(offer);
            }
            public void release(long offer) { releaseHandoffTransport(offer); }
        });
        this.handoffClientAgent = agent;
    }

    /** HOST: register the world-archive sender so the 0xE4 demux can feed it ACK/DONE. */
    public void setHostWorldTransfer(WorldTransfer wt) {
        this.hostWorldTransfer = wt;
    }

    /** SUCCESSOR: register the world-archive receiver so the 0xE4 demux can feed it BEGIN/CHUNK. */
    public void setSuccessorWorldTransfer(WorldTransfer wt) {
        this.successorWorldTransfer = wt;
    }

    /** Sets the hook run once per successful join-via-rendezvous (see {@link #onClientConnected}). */
    public void setOnClientConnected(Runnable hook) {
        this.onClientConnected = hook;
    }

    public HandoffClientAgent handoffClientAgent() {
        return this.handoffClientAgent;
    }

    /** JOINER: address of the host we're currently relayed to — where handoff replies go. */
    public PeerAddress currentHostPeer() {
        return this.clientTargetPeer;
    }

    /** Sends a raw datagram on the shared socket — used by the handoff client agent to reply to the host. */
    public void sendRawDatagram(InetAddress ip, int port, byte[] data) {
        // Rendezvous and handoff authority remain server control traffic.
        if (data.length > 0 && (data[0] == RendezvousProtocol.MAGIC
                || data[0] == net.peercraft.network.handoff.HandoffAuthorityProtocol.MAGIC)) {
            if (sender != null) sender.sendData(data, ip.getHostAddress(), port);
            return;
        }
        sendEncoded(new PeerAddress(ip, port), data);
    }

    // Returns false if the UDP socket could not be bound (port already in use, etc.) — the
    // rendezvous callers surface that to the player instead of pressing on with a null sender.
    private boolean restartReceiver(int port) {
        // A retained direct route owns this socket across a handoff role change.
        if (retainingHandoffTransport() && receiver != null && receiver.getSocket() != null
                && !receiver.getSocket().isClosed()) return true;
        if (this.receiver != null) {
            this.receiver.stop();
        }
        this.receiver = new P2PReceiver();
        boolean started = this.receiver.start(port);
        // Recreates the sender on the same socket as the receiver — see the comment in
        // P2PSender. Without this, a punched hole-punching NAT mapping would be useless.
        // (getSocket() is null when the bind failed; the sender then no-ops every send, which
        // is why the callers above bail out on a false return rather than continuing.)
        this.sender = new P2PSender(this.receiver.getSocket());
        return started;
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
    public synchronized long beginClientSession(Socket socket) {
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
        PeerTransport route = retainedPeerRoutes.get(dest);
        // Handoff operation owns its original route until release; game uses current route.
        boolean handoffPacket = framed.length > 0 && (framed[0] == HandoffProtocol.MAGIC
                || framed[0] == WorldTransferProtocol.MAGIC
                || framed[0] == net.peercraft.network.handoff.HandoffControlProtocol.MAGIC
                || framed[0] == net.peercraft.network.handoff.HandoffCapabilities.MAGIC);
        if (route == null || !handoffPacket) route = peerRoutes.get(dest);
        if (route != null) {
            try { route.send(framed); }
            catch (IOException error) { LOGGER.debug("[P2PBridge] Peer transport send failed: {}", error.toString()); }
        } else if (!securedPeers.contains(dest)) sender.sendData(framed, dest.ip(), dest.port());
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
            if (SEND_PACING_MILLIS > 0 && offset < data.length) {
                try {
                    Thread.sleep(SEND_PACING_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
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
            LOGGER.debug("[P2PBridge] Повторно отправляем seq={} для сессии {} по NACK", seq, sessionId);
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
        if (length < 1 || length > data.length) return;
        byte[] packet = Arrays.copyOf(data, length);
        if (SecureDatagramChannel.isPacket(packet)) {
            DirectBinding binding = directBindings.get(SecureDatagramChannel.peekLinkId(packet));
            if (binding == null) return;
            byte[] decoded = binding.route.accept(packet, new InetSocketAddress(senderAddress, senderPort));
            if (decoded != null) dispatchDecoded(decoded, decoded.length, binding.peer.host(), binding.peer.port());
            return;
        }
        for (DirectConnectivityCoordinator checks : activeDirectChecks.values()) {
            if (checks.onDiscoveryPacket(packet, length, senderAddress, senderPort)) return;
        }
        if (length >= 2 && data[0] == RendezvousProtocol.MAGIC
                && (data[1] & 0xFF) == DirectConnectivityCoordinator.MESSAGE_TYPE) {
            for (DirectConnectivityCoordinator checks : activeDirectChecks.values()) checks.onPacket(packet, length, senderAddress, senderPort);
            return;
        }
        boolean control = data[0] == RendezvousProtocol.MAGIC
                || data[0] == net.peercraft.network.handoff.HandoffAuthorityProtocol.MAGIC;
        if (!control) {
            PeerAddress source = new PeerAddress(senderAddress, senderPort);
            if (securedPeers.contains(source) || peerRoutes.containsKey(source) || retainedPeerRoutes.containsKey(source)
                    || (!isHost && clientTargetPeer != null && peerRoutes.containsKey(clientTargetPeer))) return;
        }
        dispatchDecoded(packet, length, senderAddress, senderPort);
    }

    private synchronized void dispatchDecoded(byte[] data, int length, InetAddress senderAddress, int senderPort) {
        // Mod-sync control traffic has its own magic byte (0xE2), routed before the relay path
        // and separately from rendezvous (0xE1). On the joiner it goes to the single
        // rendezvousListener slot (which the ModSyncAgent binds its coordinator into via
        // ModSyncLink); on the host, one coordinator per already-punched joiner address.
        if (length >= 2 && data[0] == ModSyncProtocol.MAGIC) {
            if (this.isHost) {
                PeerAddress from = new PeerAddress(senderAddress, senderPort);
                ModSyncHostProvider provider = this.modSyncHostProvider;
                if (provider == null || !authorizedPeers.contains(from)) {
                    return;
                }
                // One coordinator per punched joiner. A previous attempt from this peer that
                // failed mid-transfer leaves its coordinator cancelled (the joiner sent T_ABORT);
                // it can serve nothing, so replace it — otherwise every retry's HELLO is swallowed
                // and the joiner just times out with no screen. handleIncomingPacket is
                // single-threaded (the P2PReceiver processing thread), so no lock is needed.
                ModSyncCoordinator coord = modSyncHostSessions.get(from);
                if (coord == null || coord.isCancelled()) {
                    coord = ModSyncCoordinator.host(bytes -> sendEncoded(from, bytes), provider, provider.servingTempDir());
                    modSyncHostSessions.put(from, coord);
                }
                coord.onPacket(data, length, senderAddress, senderPort);
            } else {
                RawPacketListener listener = this.rendezvousListener;
                if (listener != null) {
                    listener.onPacket(data, length, senderAddress, senderPort);
                }
            }
            return;
        }

        // Host-handoff control traffic (0xE3) — its own family alongside rendezvous (0xE1)
        // and mod-sync (0xE2). On the host it drives the in-progress handoff attempt; on a
        // joiner it feeds the always-installed HandoffClientAgent (offer / MIGRATE). Only an
        // already-authorized peer may reach the host session — same admission gate as the
        // relay and mod-sync paths — EXCEPT for MIGRATE_OK: by the time the successor sends
        // it, it has already left this host's session (loading its own world disconnects
        // first) and re-bound a fresh socket to go host itself, so it calls from a new local
        // port that was never punched against this host. Accept that one case by IP alone,
        // matched against the specific successor this handoff already vetted via ACCEPT.
        if (length >= 1 && data[0] == net.peercraft.network.handoff.HandoffControlProtocol.MAGIC) {
            HandoffControlReceiver handler = independentHandoffReceiver;
            if (handler != null) handler.onPacket(data, length, new java.net.InetSocketAddress(senderAddress, senderPort));
            return;
        }
        if (length >= 1 && data[0] == net.peercraft.network.handoff.HandoffCapabilities.MAGIC) {
            PeerAddress from = new PeerAddress(senderAddress, senderPort);
            net.peercraft.network.handoff.HandoffTransport transport = independentHandoff;
            if (authorizedPeers.contains(from) || from.equals(clientTargetPeer)
                    || (transport != null && transport.contains(new java.net.InetSocketAddress(senderAddress, senderPort))))
                handoffCapabilities.onPacket(data, length, new java.net.InetSocketAddress(senderAddress, senderPort));
            return;
        }
        if (length >= 1 && data[0] == net.peercraft.network.handoff.HandoffAuthorityProtocol.MAGIC) {
            net.peercraft.network.handoff.HandoffAuthorityClient authority = handoffAuthority;
            if (authority != null) authority.onPacket(data, length, senderAddress, senderPort);
            return;
        }

        if (length >= 2 && data[0] == HandoffProtocol.MAGIC) {
            if (this.isHost) {
                // Out-of-band preference, not part of any one offer's handshake — handled here
                // directly so it's tracked whether or not a handoff attempt is currently running.
                if ((data[1] & 0xFF) == (HandoffProtocol.T_SUCCESSOR_PREFERENCE & 0xFF)) {
                    PeerAddress from = new PeerAddress(senderAddress, senderPort);
                    if (authorizedPeers.contains(from)) {
                        successorOptOutByAddress.put(from, HandoffProtocol.decodeSuccessorPreference(data, length));
                    }
                    return;
                }
                HandoffCoordinator session = this.handoffHostSession;
                PeerAddress successor = this.handoffSuccessorPeer;
                boolean fromKnownSuccessor = successor != null && successor.host().equals(senderAddress);
                if (session != null && (authorizedPeers.contains(new PeerAddress(senderAddress, senderPort)) || fromKnownSuccessor)) {
                    session.onPacket(data, length, senderAddress, senderPort);
                }
            } else {
                HandoffClientAgent agent = this.handoffClientAgent;
                PeerAddress expectedHost = this.clientTargetPeer;
                if (agent != null && expectedHost != null && expectedHost.equals(new PeerAddress(senderAddress, senderPort))) {
                    agent.onPacket(data, length, senderAddress, senderPort);
                }
            }
            return;
        }

        // World-save archive transfer (0xE4) — host serves, successor receives. Same
        // authorized-peer gate on the host side.
        if (length >= 2 && data[0] == WorldTransferProtocol.MAGIC) {
            HandoffControlReceiver independent = independentHandoffReceiver;
            if (independent != null && independent.onWorldPacket(data, length, new java.net.InetSocketAddress(senderAddress, senderPort))) return;
            if (this.isHost) {
                WorldTransfer wt = this.hostWorldTransfer;
                if (wt != null && authorizedPeers.contains(new PeerAddress(senderAddress, senderPort))) {
                    wt.onPacket(data, length, senderAddress, senderPort);
                }
            } else {
                WorldTransfer wt = this.successorWorldTransfer;
                if (wt != null) {
                    wt.onPacket(data, length, senderAddress, senderPort);
                }
            }
            return;
        }

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

        // A guessed game session ID cannot change a negotiated participant's identity.
        if (conn != null && !sender.equals(conn.peerAddress)
                && (peerRoutes.containsKey(conn.peerAddress) || retainedPeerRoutes.containsKey(conn.peerAddress)
                || peerRoutes.containsKey(sender))) return;
        if (frame.type() == FramedPacket.TYPE_NACK) {
            if (conn != null) {
                resendIfBuffered(conn.peerAddress, conn.sessionId, frame.nackSeq(), conn.sentPackets);
            }
            return;
        }

        if (conn == null && frame.isFin()) {
            // FIN for a session we hold no connection for — already torn down, or we never
            // saw its data stream. Nothing to close.
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
            if (frame.isFin()) {
                // Joiner left the world — close our TCP link to the integrated server right
                // away so it removes their player entity now, instead of waiting out its
                // keep-alive timeout with a frozen, unkickable, still-damageable avatar.
                LOGGER.info("[P2PBridge] (Хост) Джойнер {}:{} закрыл сессию {} (FIN) — рвём соединение с локальным MC-сервером",
                        sender.ip(), sender.port(), frame.sessionId());
                closeHostConnection(conn);
            }
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
        if (handoffAdmissionClosed) return null;
        try {
            LOGGER.info("[P2PBridge] Подключаемся к локальному MC серверу 127.0.0.1:{} (сессия {}, пир {}:{})...", localMinecraftPort, sessionId, peerAddress.ip(), peerAddress.port());
            Socket mcSocket = new Socket("127.0.0.1", localMinecraftPort);
            HostConnection conn = new HostConnection(sessionId, peerAddress, mcSocket);
            hostConnectionsBySessionId.put(sessionId, conn);
            hostConnectionsByAddress.put(peerAddress, conn);

            // This joiner has moved on to the real relay stream — its mod-sync coordinator (if
            // any) has done its job; stop its threads and drop it.
            ModSyncCoordinator finishedModSync = modSyncHostSessions.remove(peerAddress);
            if (finishedModSync != null) {
                finishedModSync.cancel();
            }

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
                LOGGER.debug("[P2PBridge] Запросили повторную отправку seq={} для сессии {}", result.requestSeq, conn.sessionId);
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
                LOGGER.debug("[P2PBridge] Запросили повторную отправку seq={} для клиентской сессии {}", result.requestSeq, session.sessionId);
            }
            if (frame.isFin()) {
                // Host closed our session (they left / closed the world) — drop the local
                // MC client now so vanilla shows "connection lost" instead of hanging on
                // the socket until its 30s read timeout.
                LOGGER.info("[P2PBridge] Хост закрыл сессию {} (FIN) — отключаем локальный MC-клиент", session.sessionId);
                if (this.currentClientSession == session) endClientSession(session.sessionId);
                if (this.proxy != null) {
                    this.proxy.disconnectClient();
                }
            }
        } catch (ReorderBuffer.SessionBrokenException e) {
            LOGGER.error("[P2PBridge] Клиентская сессия {} повреждена: {} — закрываем соединение с локальным MC-клиентом", session.sessionId, e.getMessage());
            if (this.currentClientSession == session) endClientSession(session.sessionId);
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

                LOGGER.debug("[P2PBridge] Получен ответ от Minecraft-сервера ({} байт, сессия {}). Отправляем по UDP...", bytesRead, conn.sessionId);
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

        // If the player we're mid-handoff to drops BEFORE MIGRATE went out, the transfer can't
        // finish — abort so the host keeps hosting instead of stalling forever. Once MIGRATE has
        // gone out, this same disconnect is expected (the successor leaves to load its own
        // world) and must not be treated as a cancel — see HandoffCoordinator.migrationStarted().
        PeerAddress successor = this.handoffSuccessorPeer;
        if (!retainingHandoffTransport() && successor != null && successor.equals(conn.peerAddress)) {
            HandoffCoordinator session = this.handoffHostSession;
            WorldTransfer wt = this.hostWorldTransfer;
            if (wt != null) {
                wt.stop();
            }
            if (session != null && !session.isTerminal() && !session.migrationStarted()) {
                LOGGER.info("[P2PBridge] Преемник отключился во время передачи — отменяем хендофф");
                session.cancel("peercraft.handoff.abort.successor");
            } else if (session != null) {
                LOGGER.debug("[P2PBridge] Преемник отключился от старого хоста после MIGRATE — ожидаемо, хендофф продолжается");
            }
        }
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

    public synchronized void stop() {
        ClientJoinAttempt attempt = currentJoinAttempt;
        if (attempt != null) {
            attempt.cancelled = true;
            if (attempt.modSync != null) attempt.modSync.cancel();
        }
        rendezvousClientBusy.set(false);
        modSyncActive.set(false);
        net.peercraft.network.handoff.HandoffAuthorityClient authority = handoffAuthority;
        handoffAuthority = null;
        if (authority != null) authority.close();
        net.peercraft.network.handoff.HandoffTransport transport = independentHandoff;
        independentHandoff = null;
        if (transport != null) transport.close();
        cancelRendezvousUnconditionally();
        WorldTransfer incoming = successorWorldTransfer;
        successorWorldTransfer = null;
        if (incoming != null) incoming.stop();
        handoffClientAgent = null;
        independentHandoffReceiver = null;
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
