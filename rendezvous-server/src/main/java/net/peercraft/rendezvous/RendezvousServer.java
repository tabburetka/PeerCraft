package net.peercraft.rendezvous;

import net.peercraft.rendezvous.account.AccountService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Minimal UDP rendezvous server: lets a Minecraft host publish a short room code and
 * a joiner exchange it for the host's server-observed public address (and vice versa),
 * so both sides can attempt UDP hole punching directly with each other. The server's
 * job ends there — it never sees or relays any actual game traffic.
 */
public final class RendezvousServer {

    private static final int DEFAULT_PORT = 51000;
    // Every real INCOMING request here comfortably fits (the largest is TYPE_SEARCH_ACCOUNTS
    // with a max-length query, ~275 bytes) — keeping this bounded doubles as abuse protection
    // (nothing large enough to be useful for a UDP reflection/amplification attack could ever
    // be a valid request here). OUTGOING replies (e.g. a friends/search list with many
    // entries) are not bound by this — they're built and sent independently, see send(...).
    private static final int MAX_DATAGRAM_SIZE = 512;
    private static final long SWEEP_INTERVAL_SECONDS = 30;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        Path dataDir = Path.of(System.getProperty("peercraft.rendezvous.dataDir", "./data"));
        boolean fakeMojang = Boolean.getBoolean("peercraft.rendezvous.fakeMojang");
        new RendezvousServer(port, System::currentTimeMillis, dataDir, fakeMojang).run();
    }

    private final int port;
    private final RoomRegistry registry;
    private final HandoffRegistry handoffs;
    private final java.util.concurrent.ThreadPoolExecutor handoffIo = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1000),
            r -> { Thread t = new Thread(r, "handoff-journal"); t.setDaemon(true); return t; });
    private final AccountService accountService;
    private volatile DatagramSocket socket;
    // Anonymous, unauthenticated poll (Phase 7, TYPE_ROOM_LIST) — anyone can ask, so it needs
    // its own throttle independent of the account/friends rate limiters (which all key off a
    // validated session or account id this endpoint deliberately doesn't require). Keyed by
    // IP, not account — so it must have enough headroom for several players (or several game
    // instances) legitimately sharing one IP (household/NAT), each polling on their own timer
    // (PeerCraftMultiplayerScreen polls every 5s while the Games tab has ever loaded once) —
    // a tight limit here silently drops requests instead of rejecting them with a reason,
    // which surfaces client-side as a bare "account server did not respond" timeout with no
    // way to tell it apart from an actual network problem. This endpoint is a cheap in-memory
    // read, so being generous costs nothing.
    private static final int ROOM_LIST_RATE_LIMIT = 120;
    private static final long ROOM_LIST_RATE_WINDOW_MILLIS = 60_000L;
    private final RateLimiter<InetAddress> roomListRateLimiter;

    /**
     * Test-only convenience — an isolated temp-dir account store and {@code fakeMojang=true}
     * (no real network calls to Mojang from automated tests). Real deployment always goes
     * through {@link #main} via the 4-arg constructor below.
     */
    RendezvousServer(int port) {
        this(port, System::currentTimeMillis, tempDataDir(), true);
    }

    /** Package-private seam so tests can control TTL/rate-limit-window behavior deterministically. */
    RendezvousServer(int port, LongSupplier clock) {
        this(port, clock, tempDataDir(), true);
    }

    /** Package-private seam so tests can point at a real data dir and/or the real Mojang verifier. */
    RendezvousServer(int port, LongSupplier clock, Path dataDir, boolean fakeMojang) {
        this.port = port;
        this.registry = new RoomRegistry(clock);
        try { this.handoffs = new HandoffRegistry(dataDir.resolve("handoffs"), clock); }
        catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        this.accountService = new AccountService(dataDir.resolve("accounts.json"), fakeMojang, clock);
        this.roomListRateLimiter = new RateLimiter<>(ROOM_LIST_RATE_LIMIT, ROOM_LIST_RATE_WINDOW_MILLIS, clock);
    }

    private static Path tempDataDir() {
        try {
            return Files.createTempDirectory("peercraft-rendezvous-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Bound listening port, or 0 if not started yet — mainly useful for tests that bind to port 0. */
    int getBoundPort() {
        DatagramSocket s = socket;
        return (s != null && !s.isClosed()) ? s.getLocalPort() : 0;
    }

    void run() throws IOException {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            this.socket = socket;
            log("Listening on UDP port " + socket.getLocalPort());

            ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rendezvous-sweep");
                t.setDaemon(true);
                return t;
            });
            sweeper.scheduleAtFixedRate(registry::sweepExpired, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
            sweeper.scheduleAtFixedRate(() -> {
                try { handoffIo.execute(() -> { try { handoffs.maintenance(); } catch (IOException e) { logErr("Handoff retention failed"); } }); }
                catch (java.util.concurrent.RejectedExecutionException ignored) { }
            }, 1, 1, TimeUnit.DAYS);
            sweeper.scheduleAtFixedRate(accountService::maintenance, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);

            byte[] buffer = new byte[MAX_DATAGRAM_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while (true) {
                try {
                    packet.setLength(buffer.length);
                    socket.receive(packet);
                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
                    handle(socket, data, packet.getAddress(), packet.getPort());
                } catch (Exception e) {
                    InetAddress fromAddr = packet.getAddress();
                    String from = fromAddr != null ? fromAddr.getHostAddress() + ":" + packet.getPort() : "unknown sender";
                    logErr("Error handling packet from " + from + ": " + e);
                    e.printStackTrace();
                }
            }
        }
    }

    private void handle(DatagramSocket socket, byte[] data, InetAddress fromAddr, int fromPort) throws IOException {
        if (data.length > 0 && data[0] == HandoffAuthorityProtocol.MAGIC) {
            HandoffAuthorityProtocol.Message request = HandoffAuthorityProtocol.decode(data, data.length);
            RendezvousProtocol.Address from = new RendezvousProtocol.Address(fromAddr, fromPort);
            if (!roomListRateLimiter.allow(fromAddr)) return;
            try {
                handoffIo.execute(() -> {
                    try {
                        // Source address routes the challenge; only knowledge of its random
                        // secret proves control. Matching IP/port alone does not authorize BEGIN.
                        boolean ownsRoom = registry.authorizesHandoff(request.room, from,
                                java.util.Arrays.copyOf(request.digest, 32));
                        HandoffAuthorityProtocol.Message reply = handoffs.handle(request, ownsRoom);
                        if (request.type == HandoffAuthorityProtocol.CAPABILITIES)
                            reply.key = registry.handoffChallenge(request.room, from);
                        if (request.type == HandoffAuthorityProtocol.QUIESCE && reply.state == HandoffAuthorityProtocol.PENDING
                                && ownsRoom && request.room.equals(reply.room)) registry.suspendForHandoff(reply.room, request.sessionId + ":" + request.offerId);
                        if (request.type == HandoffAuthorityProtocol.ABORT && reply.state == HandoffAuthorityProtocol.ABORTED)
                            registry.resumeAfterHandoff(reply.room, request.sessionId + ":" + request.offerId);
                        send(socket, HandoffAuthorityProtocol.encode(reply), from);
                    } catch (IOException | RuntimeException e) { logErr("Handoff journal operation failed: " + e.getClass().getSimpleName()); }
                });
            } catch (java.util.concurrent.RejectedExecutionException busy) { }
            return;
        }
        int type = RendezvousProtocol.messageType(data, data.length);
        if (type < 0) {
            return; // not our magic byte, or too short — silently ignore
        }

        RendezvousProtocol.Address from = new RendezvousProtocol.Address(fromAddr, fromPort);

        switch (type) {
            case RendezvousProtocol.TYPE_REGISTER -> handleRegister(socket, data, from);
            case RendezvousProtocol.TYPE_JOIN -> handleJoin(socket, data, from);
            case RendezvousProtocol.TYPE_ROOM_LIST -> handleRoomList(socket, from);
            case RendezvousProtocol.TYPE_LOOKUP_HOST -> handleLookupHost(socket, data, from);
            case AccountProtocol.TYPE_AUTH_LICENSED_BEGIN -> handleAuthLicensedBegin(socket, data, from);
            case AccountProtocol.TYPE_AUTH_LICENSED_CONFIRM -> handleAuthLicensedConfirm(socket, data, from);
            case AccountProtocol.TYPE_ACCOUNT_REGISTER -> handleAccountRegister(socket, data, from);
            case AccountProtocol.TYPE_ACCOUNT_LOGIN_BEGIN -> handleAccountLoginBegin(socket, data, from);
            case AccountProtocol.TYPE_ACCOUNT_LOGIN_RESPONSE -> handleAccountLoginResponse(socket, data, from);
            case AccountProtocol.TYPE_ACCOUNT_LOGIN_REMEMBER -> handleAccountLoginRemember(socket, data, from);
            case AccountProtocol.TYPE_ACCOUNT_RENAME -> handleAccountRename(socket, data, from);
            case AccountProtocol.TYPE_FRIEND_CODE_LOOKUP -> handleFriendCodeLookup(socket, data, from);
            case AccountProtocol.TYPE_FRIEND_REQUEST_SEND -> handleFriendRequestSend(socket, data, from);
            case AccountProtocol.TYPE_FRIEND_REQUEST_LIST -> handleFriendRequestList(socket, data, from);
            case AccountProtocol.TYPE_FRIEND_REQUEST_RESPOND -> handleFriendRequestRespond(socket, data, from);
            case AccountProtocol.TYPE_FRIEND_REMOVE -> handleFriendRemove(socket, data, from);
            case AccountProtocol.TYPE_FRIEND_LIST -> handleFriendList(socket, data, from);
            case AccountProtocol.TYPE_SEARCH_ACCOUNTS -> handleSearchAccounts(socket, data, from);
            case AccountProtocol.TYPE_PRESENCE_HEARTBEAT -> handlePresenceHeartbeat(data);
            case AccountProtocol.TYPE_PRESENCE_STOP -> handlePresenceStop(data);
            default -> { /* PUNCH/PUNCH_ACK are peer-to-peer only; replies never arrive here — ignore anything else */ }
        }
    }

    private void handleRegister(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        RendezvousProtocol.Register register = RendezvousProtocol.decodeRegister(data, data.length);
        // A REGISTER's self-reported accountId is never trusted unchecked — only linked to
        // presence/friends if its attached sessionToken actually resolves to that exact
        // account, closing off "claim to be hosting as someone else's account" spoofing. The
        // same verified id is what RoomRegistry stores as the room's hostAccountId, so a
        // friends-only gate can never end up checking against a spoofed identity.
        java.util.Optional<java.util.UUID> verifiedAccountId = register.account().flatMap(ref ->
                accountService.resolveSession(ref.sessionToken()).filter(resolved -> resolved.equals(ref.accountId())));
        boolean friendsOnly = register.friendsOnly() && verifiedAccountId.isPresent();
        RoomRegistry.RegisterResult result = registry.register(from, register.maxPlayers(), register.currentPlayerCount(),
                verifiedAccountId, friendsOnly, register.publicRoom(), register.worldName(), register.mcVersion());
        if (result instanceof RoomRegistry.Registered registered) {
            send(socket, RendezvousProtocol.encodeRoomCreated(registered.code(), from), from);
            if (registered.reused()) {
                log("REGISTER from " + describe(from) + " -> existing room " + registered.code() + " (idempotent resend/keepalive)");
            } else {
                log("REGISTER from " + describe(from) + " -> new room " + registered.code());
            }
            verifiedAccountId.ifPresent(accountId -> accountService.setHosting(accountId, registered.code()));
        } else {
            RoomRegistry.RegisterRejected rejected = (RoomRegistry.RegisterRejected) result;
            send(socket, RendezvousProtocol.encodeJoinFail(rejected.reason()), from);
            log("REGISTER from " + describe(from) + " -> rejected (reason=" + rejected.reason() + ")");
        }
    }

    private void handleJoin(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        RendezvousProtocol.Join join = RendezvousProtocol.decodeJoin(data, data.length);
        // Resolving straight from the joiner's own sessionToken (never a client-supplied
        // accountId) is what makes this trustworthy — only the real session owner could have
        // that token. Reused both for the friends-only gate below and for PEER_FOUND's account
        // trailer to the host.
        java.util.Optional<java.util.UUID> joinerAccountId = join.sessionToken().flatMap(accountService::resolveSession);
        RoomRegistry.JoinResult result = registry.join(join.code(), from, joinerAccountId, accountService::isFriend);
        if (result instanceof RoomRegistry.Matched matched) {
            byte[] hostPayload = joinerAccountId
                    .map(id -> RendezvousProtocol.encodePeerFoundWithAccount(matched.joinerAddress(), matched.token(), id))
                    .orElseGet(() -> RendezvousProtocol.encodePeerFound(matched.joinerAddress(), matched.token()));
            send(socket, hostPayload, matched.hostAddress());
            send(socket, RendezvousProtocol.encodePeerFound(matched.hostAddress(), matched.token()), matched.joinerAddress());
            log("Room " + join.code() + " matched: " + describe(matched.hostAddress()) + " <-> " + describe(matched.joinerAddress()));
        } else {
            RoomRegistry.JoinRejected rejected = (RoomRegistry.JoinRejected) result;
            send(socket, RendezvousProtocol.encodeJoinFail(rejected.reason()), from);
            log("JOIN " + join.code() + " from " + describe(from) + " -> rejected (reason=" + rejected.reason() + ")");
            if (rejected.reason() == RendezvousProtocol.REASON_INVALID_CODE) {
                // Dump everything the server currently knows about — the most useful moment to
                // see this is exactly when a code that "should" exist doesn't, e.g. because the
                // host's NAT silently re-mapped its port between REGISTER and a later keepalive
                // and orphaned the original room's code (see RoomRegistry.register()'s comment).
                java.util.List<String> rooms = registry.describeAllRooms();
                log("  known rooms (" + rooms.size() + "): " + (rooms.isEmpty() ? "(none)" : String.join(", ", rooms)));
            }
        }
    }

    /**
     * Phase 7: anonymous public-game-browser poll — deliberately no session/account check
     * (see the class docs on why this feature exists), just an IP rate limit against
     * flooding/scraping.
     */
    private void handleRoomList(DatagramSocket socket, RendezvousProtocol.Address from) throws IOException {
        if (!roomListRateLimiter.allow(from.host())) {
            return; // silently drop — matches this being a low-stakes poll endpoint, same as friend list/search
        }
        java.util.List<RoomRegistry.PublicRoomInfo> rooms = registry.listPublicRooms();
        java.util.List<RendezvousProtocol.PublicRoom> wire = new java.util.ArrayList<>();
        for (RoomRegistry.PublicRoomInfo room : rooms) {
            String hostDisplayName = room.hostAccountId().flatMap(accountService::displayNameOf).orElse("");
            wire.add(new RendezvousProtocol.PublicRoom(room.code(), room.maxPlayers(), room.currentPlayerCount(), hostDisplayName, room.worldName(), room.mcVersion()));
        }
        send(socket, RendezvousProtocol.encodeRoomListReply(wire), from);
    }

    /**
     * Host handoff: "what room is account X hosting?" — used by a joiner to find its new host
     * after a handoff changed the room code. Anonymous, no session (the answer, a public room
     * code, is no more sensitive than the public browser); shares the room-list IP rate limit.
     */
    private void handleLookupHost(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        if (!roomListRateLimiter.allow(from.host())) {
            return;
        }
        java.util.UUID accountId = RendezvousProtocol.decodeLookupHost(data, data.length);
        String roomCode = accountService.hostingRoomCodeOf(accountId).orElse("");
        send(socket, RendezvousProtocol.encodeLookupHostReply(roomCode), from);
    }

    private void handleAuthLicensedBegin(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        AccountProtocol.LicensedBegin msg = AccountProtocol.decodeLicensedBegin(data, data.length);
        AccountService.Result<AccountService.ServerIdChallengeInfo> result = accountService.beginLicensedAuth(msg.username(), from.host());
        if (result instanceof AccountService.Result.Ok<AccountService.ServerIdChallengeInfo> ok) {
            send(socket, AccountProtocol.encodeServerIdChallenge(ok.value().requestId(), ok.value().serverId()), from);
        } else {
            sendAuthFail(socket, 0L, ((AccountService.Result.Fail<?>) result).reason(), from);
        }
    }

    private void handleAuthLicensedConfirm(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        AccountProtocol.LicensedConfirm msg = AccountProtocol.decodeLicensedConfirm(data, data.length);
        AccountService.Result<AccountService.AuthOkInfo> result = accountService.confirmLicensedAuth(msg.requestId());
        respondAuthOutcome(socket, result, msg.requestId(), from);
    }

    private void handleAccountRegister(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        AccountProtocol.AccountRegister msg = AccountProtocol.decodeAccountRegister(data, data.length);
        AccountService.Result<AccountService.AuthOkInfo> result =
                accountService.register(msg.username(), msg.salt(), msg.passwordHash(), from.host());
        respondAuthOutcome(socket, result, 0L, from);
    }

    private void handleAccountLoginBegin(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        AccountProtocol.LoginBegin msg = AccountProtocol.decodeLoginBegin(data, data.length);
        AccountService.Result<AccountService.LoginChallengeInfo> result =
                accountService.beginPasswordLogin(msg.byFriendCode(), msg.accountId(), msg.friendCode(), from.host());
        if (result instanceof AccountService.Result.Ok<AccountService.LoginChallengeInfo> ok) {
            send(socket, AccountProtocol.encodeLoginChallenge(ok.value().requestId(), ok.value().salt(), ok.value().challenge()), from);
        } else {
            sendAuthFail(socket, 0L, ((AccountService.Result.Fail<?>) result).reason(), from);
        }
    }

    private void handleAccountLoginResponse(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        AccountProtocol.LoginResponse msg = AccountProtocol.decodeLoginResponse(data, data.length);
        AccountService.Result<AccountService.AuthOkInfo> result = accountService.completePasswordLogin(msg.requestId(), msg.hmac());
        respondAuthOutcome(socket, result, msg.requestId(), from);
    }

    private void handleAccountLoginRemember(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        AccountProtocol.LoginRemember msg = AccountProtocol.decodeLoginRemember(data, data.length);
        AccountService.Result<AccountService.AuthOkInfo> result = accountService.loginRemembered(msg.accountId(), msg.rememberToken());
        respondAuthOutcome(socket, result, 0L, from);
    }

    private void handleAccountRename(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        AccountProtocol.AccountRename msg = AccountProtocol.decodeAccountRename(data, data.length);
        AccountService.Result<AccountService.RenameOutcomeInfo> result = accountService.rename(msg.sessionToken(), msg.newName());
        if (result instanceof AccountService.Result.Ok<AccountService.RenameOutcomeInfo> ok) {
            send(socket, AccountProtocol.encodeRenameAck(true, (byte) 0, ok.value().appliedName()), from);
        } else {
            send(socket, AccountProtocol.encodeRenameAck(false, ((AccountService.Result.Fail<?>) result).reason(), ""), from);
        }
    }

    private void handleFriendCodeLookup(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        AccountProtocol.FriendCodeLookup msg = AccountProtocol.decodeFriendCodeLookup(data, data.length);
        AccountService.Result<AccountService.FriendCodeLookupInfo> result = accountService.lookupFriendCode(msg.sessionToken(), msg.friendCode());
        if (result instanceof AccountService.Result.Ok<AccountService.FriendCodeLookupInfo> ok) {
            AccountService.FriendCodeLookupInfo info = ok.value();
            send(socket, AccountProtocol.encodeFriendCodeLookupReply(info.found(), info.accountId(), info.licensed(), info.displayName()), from);
        } else {
            // Invalid session — reply "not found" rather than adding a new failure path to
            // this query endpoint (see AccountService.lookupFriendCode's doc comment).
            send(socket, AccountProtocol.encodeFriendCodeLookupReply(false, new java.util.UUID(0, 0), false, ""), from);
        }
    }

    private void handleFriendRequestSend(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        AccountProtocol.FriendRequestSend msg = AccountProtocol.decodeFriendRequestSend(data, data.length);
        respondFriendAck(socket, accountService.sendFriendRequest(msg.sessionToken(), msg.targetAccountId()), from);
    }

    private void handleFriendRequestList(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        AccountProtocol.FriendRequestListQuery msg = AccountProtocol.decodeFriendRequestListQuery(data, data.length);
        java.util.List<AccountService.IncomingRequestInfo> requests = accountService.listIncomingRequests(msg.sessionToken());
        java.util.List<AccountProtocol.IncomingRequest> wire = new java.util.ArrayList<>();
        for (AccountService.IncomingRequestInfo r : requests) {
            wire.add(new AccountProtocol.IncomingRequest(r.fromAccountId(), r.licensed(), r.displayName()));
        }
        send(socket, AccountProtocol.encodeFriendRequestListReply(wire), from);
    }

    private void handleFriendRequestRespond(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        AccountProtocol.FriendRequestRespond msg = AccountProtocol.decodeFriendRequestRespond(data, data.length);
        respondFriendAck(socket, accountService.respondToRequest(msg.sessionToken(), msg.fromAccountId(), msg.accept()), from);
    }

    private void handleFriendRemove(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        AccountProtocol.FriendRemove msg = AccountProtocol.decodeFriendRemove(data, data.length);
        respondFriendAck(socket, accountService.removeFriend(msg.sessionToken(), msg.friendAccountId()), from);
    }

    private void handleFriendList(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        AccountProtocol.FriendListQuery msg = AccountProtocol.decodeFriendListQuery(data, data.length);
        java.util.List<AccountService.FriendInfo> friends = accountService.listFriends(msg.sessionToken());
        java.util.List<AccountProtocol.FriendEntry> wire = new java.util.ArrayList<>();
        for (AccountService.FriendInfo f : friends) {
            wire.add(new AccountProtocol.FriendEntry(f.accountId(), f.licensed(), f.displayName(), f.status(), f.roomCode()));
        }
        send(socket, AccountProtocol.encodeFriendListReply(wire), from);
    }

    private void handleSearchAccounts(DatagramSocket socket, byte[] data, RendezvousProtocol.Address from) throws IOException {
        AccountProtocol.SearchAccounts msg = AccountProtocol.decodeSearchAccounts(data, data.length);
        AccountService.Result<java.util.List<AccountService.SearchResultInfo>> result = accountService.search(msg.sessionToken(), msg.query());
        if (result instanceof AccountService.Result.Ok<java.util.List<AccountService.SearchResultInfo>> ok) {
            java.util.List<AccountProtocol.SearchResult> wire = new java.util.ArrayList<>();
            for (AccountService.SearchResultInfo r : ok.value()) {
                wire.add(new AccountProtocol.SearchResult(r.accountId(), r.licensed(), r.displayName()));
            }
            send(socket, AccountProtocol.encodeSearchAccountsReply(wire), from);
        } else {
            // Invalid session/rate-limited — empty list rather than a new failure path, same
            // reasoning as handleFriendList (poll-style endpoint).
            send(socket, AccountProtocol.encodeSearchAccountsReply(java.util.List.of()), from);
        }
    }

    private void handlePresenceHeartbeat(byte[] data) {
        AccountProtocol.PresenceHeartbeat msg = AccountProtocol.decodePresenceHeartbeat(data, data.length);
        accountService.heartbeat(msg.sessionToken());
    }

    private void handlePresenceStop(byte[] data) {
        AccountProtocol.PresenceStop msg = AccountProtocol.decodePresenceStop(data, data.length);
        accountService.stopPresence(msg.sessionToken());
    }

    private void respondFriendAck(DatagramSocket socket, AccountService.Result<AccountService.AckInfo> result, RendezvousProtocol.Address to) throws IOException {
        if (result instanceof AccountService.Result.Ok<AccountService.AckInfo>) {
            send(socket, AccountProtocol.encodeFriendRequestAck(true, (byte) 0), to);
        } else {
            send(socket, AccountProtocol.encodeFriendRequestAck(false, ((AccountService.Result.Fail<?>) result).reason()), to);
        }
    }

    private void respondAuthOutcome(DatagramSocket socket, AccountService.Result<AccountService.AuthOkInfo> result, long requestId, RendezvousProtocol.Address to) throws IOException {
        if (result instanceof AccountService.Result.Ok<AccountService.AuthOkInfo> ok) {
            AccountService.AuthOkInfo info = ok.value();
            send(socket, AccountProtocol.encodeAuthOk(info.accountId(), info.sessionToken(), info.rememberToken(),
                    info.licensed(), info.friendCode(), info.displayName()), to);
        } else {
            sendAuthFail(socket, requestId, ((AccountService.Result.Fail<?>) result).reason(), to);
        }
    }

    private void sendAuthFail(DatagramSocket socket, long requestId, byte reason, RendezvousProtocol.Address to) throws IOException {
        send(socket, AccountProtocol.encodeAuthFail(requestId, reason), to);
    }

    private static void send(DatagramSocket socket, byte[] data, RendezvousProtocol.Address to) throws IOException {
        socket.send(new DatagramPacket(data, data.length, to.host(), to.port()));
    }

    private static String describe(RendezvousProtocol.Address address) {
        return address.host().getHostAddress() + ":" + address.port();
    }

    private static void log(String message) {
        System.out.println("[" + LocalTime.now().format(TIME_FORMAT) + "] [RendezvousServer] " + message);
    }

    private static void logErr(String message) {
        System.err.println("[" + LocalTime.now().format(TIME_FORMAT) + "] [RendezvousServer] " + message);
    }
}
