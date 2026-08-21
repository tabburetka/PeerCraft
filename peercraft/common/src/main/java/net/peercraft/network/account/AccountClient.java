package net.peercraft.network.account;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import net.peercraft.network.rendezvous.AccountProtocol;
import net.peercraft.network.rendezvous.RendezvousProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Talks to the rendezvous server's account/friends/presence endpoints — deliberately its own
 * UDP socket, independent of {@code P2PBridge}/{@code P2PReceiver}: this needs to work from
 * the title screen and stay connected for the whole game session (presence heartbeat, friends
 * list polling — see later phases), regardless of whether a P2P hosting/joining session is
 * active at all.
 *
 * Every request/reply exchange here follows the same shape: send, retry on a timer until a
 * matching reply arrives or a timeout expires. Only one exchange is tracked at a time (see
 * {@link #pending}) — concurrent calls from the GUI aren't expected (each screen only has one
 * "in flight" action at a time), and a stray late timeout callback from an abandoned attempt
 * is a harmless no-op once a newer attempt has taken the pending slot.
 */
public final class AccountClient {

    public static final AccountClient INSTANCE = new AccountClient();

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final int MAX_UDP_PAYLOAD_SIZE = 65_507;
    private static final long RETRY_INTERVAL_MILLIS = 500;
    private static final long REQUEST_TIMEOUT_MILLIS = 10_000;

    public interface AuthCallback {
        void onSuccess(AccountSession session);

        void onFailed(String reason);
    }

    public interface RenameCallback {
        void onSuccess(String appliedName);

        void onFailed(String reason);
    }

    /** Used by the friend mutations (send request / respond / remove) — no payload beyond ok/reason. */
    public interface AckCallback {
        void onSuccess();

        void onFailed(String reason);
    }

    public interface FriendCodeLookupCallback {
        void onResult(boolean found, UUID accountId, boolean licensed, String displayName);

        void onTimeout();
    }

    public interface FriendRequestListCallback {
        void onResult(java.util.List<IncomingRequest> requests);

        void onTimeout();
    }

    public interface FriendListCallback {
        void onResult(java.util.List<FriendInfo> friends);

        void onTimeout();
    }

    public interface SearchCallback {
        void onResult(java.util.List<SearchResult> results);

        void onTimeout();
    }

    public record AccountSession(UUID accountId, byte[] sessionToken, byte[] rememberToken, boolean licensed,
                                  String friendCode, String displayName) {
    }

    public record IncomingRequest(UUID fromAccountId, boolean licensed, String displayName) {
    }

    /** {@code status} is one of {@link AccountProtocol#STATUS_OFFLINE}/{@link AccountProtocol#STATUS_ONLINE}/{@link AccountProtocol#STATUS_HOSTING}. */
    public record FriendInfo(UUID accountId, boolean licensed, String displayName, int status, String roomCode) {
    }

    public record SearchResult(UUID accountId, boolean licensed, String displayName) {
    }

    private static final long PRESENCE_HEARTBEAT_INTERVAL_MILLIS = 20_000;

    private volatile DatagramSocket socket;
    private volatile InetAddress rendezvousAddress;
    private volatile int rendezvousPort;
    private volatile AccountSession currentSession;
    private volatile Thread heartbeatThread;
    private final AtomicReference<PendingRequest> pending = new AtomicReference<>();

    /** Public (not a hard singleton) so tests can create isolated instances — see {@link #INSTANCE} for production use. */
    public AccountClient() {
    }

    public AccountSession getCurrentSession() {
        return currentSession;
    }

    /**
     * Local-only logout — there's no dedicated wire message for it (sessions just expire
     * server-side via {@code SessionRegistry}'s TTL), this just forgets the session on this
     * client so the GUI stops treating the player as logged in.
     */
    public void clearSession() {
        this.currentSession = null;
        stopPresenceHeartbeatThread();
    }

    /**
     * Starts (or restarts) the periodic "I'm online" ping — runs for as long as the game is
     * open, not just while a friends screen is visible, so friends actually see you online.
     * Idempotent: safe to call again (e.g. on relogin) without leaking a duplicate thread.
     */
    private void startPresenceHeartbeat() {
        stopPresenceHeartbeatThread();
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                byte[] sessionToken = currentSessionTokenOrNull();
                if (sessionToken != null) {
                    sendPresencePacket(AccountProtocol.encodePresenceHeartbeat(sessionToken));
                }
                try {
                    Thread.sleep(PRESENCE_HEARTBEAT_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "PeerCraft-AccountClient-Heartbeat");
        thread.setDaemon(true);
        thread.start();
        this.heartbeatThread = thread;
    }

    /** Best-effort — tells the server to immediately drop presence rather than waiting out the TTL. Called on game shutdown. */
    public void stopPresenceHeartbeat() {
        stopPresenceHeartbeatThread();
        byte[] sessionToken = currentSessionTokenOrNull();
        if (sessionToken != null) {
            sendPresencePacket(AccountProtocol.encodePresenceStop(sessionToken));
        }
    }

    private void stopPresenceHeartbeatThread() {
        Thread thread = this.heartbeatThread;
        this.heartbeatThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private byte[] currentSessionTokenOrNull() {
        AccountSession session = currentSession;
        return session != null ? session.sessionToken() : null;
    }

    private void sendPresencePacket(byte[] payload) {
        if (payload == null) {
            return;
        }
        DatagramSocket s = socket;
        InetAddress addr = rendezvousAddress;
        if (s == null || s.isClosed() || addr == null) {
            return;
        }
        try {
            s.send(new DatagramPacket(payload, payload.length, addr, rendezvousPort));
        } catch (Exception e) {
            LOGGER.warn("[AccountClient] Не удалось отправить presence-пакет", e);
        }
    }

    public void connect(String rendezvousHost, int rendezvousPort) {
        try {
            this.rendezvousAddress = InetAddress.getByName(rendezvousHost);
            this.rendezvousPort = rendezvousPort;
        } catch (UnknownHostException e) {
            LOGGER.error("[AccountClient] Не удалось разрешить адрес сервера аккаунтов {}", rendezvousHost, e);
            return;
        }
        ensureSocketStarted();
    }

    private synchronized void ensureSocketStarted() {
        if (socket != null && !socket.isClosed()) {
            return;
        }
        try {
            socket = new DatagramSocket();
            Thread listenThread = new Thread(this::listenLoop, "PeerCraft-AccountClient");
            listenThread.setDaemon(true);
            listenThread.start();
        } catch (SocketException e) {
            LOGGER.error("[AccountClient] Не удалось открыть UDP-сокет", e);
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[MAX_UDP_PAYLOAD_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        DatagramSocket s = socket;
        while (s != null && !s.isClosed()) {
            try {
                packet.setLength(buffer.length);
                s.receive(packet);
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
                onPacket(data, data.length, packet.getAddress(), packet.getPort());
            } catch (Exception e) {
                if (socket != null && !socket.isClosed()) {
                    LOGGER.error("[AccountClient] Ошибка чтения UDP пакета", e);
                }
            }
        }
    }

    private void onPacket(byte[] data, int length, InetAddress address, int port) {
        InetAddress expected = rendezvousAddress;
        if (expected == null || !address.equals(expected) || port != rendezvousPort) {
            LOGGER.debug("[AccountClient] Пакет от {}:{} — не от сервера аккаунтов, игнорируем", address.getHostAddress(), port);
            return;
        }
        int type = RendezvousProtocol.messageType(data, length);
        PendingRequest current = pending.get();
        if (current != null) {
            current.tryHandle(type, data, length);
        }
    }

    // ---- public API ----

    public void loginLicensed(GameProfile profile, String accessToken, MinecraftSessionService sessionService, AuthCallback cb) {
        ensureSocketStarted();
        byte[] beginPayload = AccountProtocol.encodeLicensedBegin(profile.getName());
        sendRequest(beginPayload, new ReplyHandler() {
            @Override
            public boolean handle(int type, byte[] data, int length) {
                if (type == AccountProtocol.TYPE_AUTH_SERVER_ID) {
                    AccountProtocol.ServerIdChallenge challenge = AccountProtocol.decodeServerIdChallenge(data, length);
                    confirmLicensed(profile, accessToken, sessionService, challenge, cb);
                    return true;
                } else if (type == AccountProtocol.TYPE_AUTH_FAIL) {
                    cb.onFailed(describeAuthFailReason(AccountProtocol.decodeAuthFail(data, length).reason()));
                    return true;
                }
                return false;
            }

            @Override
            public void onTimeout() {
                cb.onFailed("сервер аккаунтов не ответил");
            }
        });
    }

    private void confirmLicensed(GameProfile profile, String accessToken, MinecraftSessionService sessionService,
                                  AccountProtocol.ServerIdChallenge challenge, AuthCallback cb) {
        // joinServer() is a blocking HTTPS call to Mojang — must not run on the UDP receive thread.
        Thread joinThread = new Thread(() -> {
            try {
                sessionService.joinServer(profile.getId(), accessToken, challenge.serverId());
            } catch (AuthenticationException e) {
                cb.onFailed("не удалось подтвердить лицензию через Mojang: " + e.getMessage());
                return;
            }
            byte[] confirmPayload = AccountProtocol.encodeLicensedConfirm(challenge.requestId());
            sendRequest(confirmPayload, authOkOrFailHandler(cb));
        }, "PeerCraft-AccountClient-MojangJoin");
        joinThread.setDaemon(true);
        joinThread.start();
    }

    public void registerUnlicensed(String nickname, char[] password, AuthCallback cb) {
        ensureSocketStarted();
        byte[] salt = PasswordCrypto.randomSalt();
        byte[] hash = PasswordCrypto.hash(password, salt);
        byte[] payload = AccountProtocol.encodeAccountRegister(nickname, salt, hash);
        sendRequest(payload, authOkOrFailHandler(cb));
    }

    public void loginByFriendCode(String friendCode, char[] password, AuthCallback cb) {
        ensureSocketStarted();
        beginPasswordLogin(AccountProtocol.encodeLoginBeginByFriendCode(friendCode), password, cb);
    }

    public void loginByAccountId(UUID accountId, char[] password, AuthCallback cb) {
        ensureSocketStarted();
        beginPasswordLogin(AccountProtocol.encodeLoginBeginByAccountId(accountId), password, cb);
    }

    private void beginPasswordLogin(byte[] beginPayload, char[] password, AuthCallback cb) {
        sendRequest(beginPayload, new ReplyHandler() {
            @Override
            public boolean handle(int type, byte[] data, int length) {
                if (type == AccountProtocol.TYPE_ACCOUNT_LOGIN_CHALLENGE) {
                    AccountProtocol.LoginChallenge challenge = AccountProtocol.decodeLoginChallenge(data, length);
                    byte[] hash = PasswordCrypto.hash(password, challenge.salt());
                    byte[] response = PasswordCrypto.hmacChallenge(hash, challenge.challenge());
                    byte[] responsePayload = AccountProtocol.encodeLoginResponse(challenge.requestId(), response);
                    sendRequest(responsePayload, authOkOrFailHandler(cb));
                    return true;
                } else if (type == AccountProtocol.TYPE_AUTH_FAIL) {
                    cb.onFailed(describeAuthFailReason(AccountProtocol.decodeAuthFail(data, length).reason()));
                    return true;
                }
                return false;
            }

            @Override
            public void onTimeout() {
                cb.onFailed("сервер аккаунтов не ответил");
            }
        });
    }

    public void loginRemembered(UUID accountId, byte[] rememberToken, AuthCallback cb) {
        ensureSocketStarted();
        byte[] payload = AccountProtocol.encodeLoginRemember(accountId, rememberToken);
        sendRequest(payload, authOkOrFailHandler(cb));
    }

    public void renameDisplayName(String newName, RenameCallback cb) {
        AccountSession session = currentSession;
        if (session == null) {
            cb.onFailed("не авторизован");
            return;
        }
        ensureSocketStarted();
        byte[] payload = AccountProtocol.encodeAccountRename(session.sessionToken(), newName);
        sendRequest(payload, new ReplyHandler() {
            @Override
            public boolean handle(int type, byte[] data, int length) {
                if (type != AccountProtocol.TYPE_ACCOUNT_RENAME_ACK) {
                    return false;
                }
                AccountProtocol.RenameAck ack = AccountProtocol.decodeRenameAck(data, length);
                if (ack.ok()) {
                    currentSession = new AccountSession(session.accountId(), session.sessionToken(), session.rememberToken(),
                            session.licensed(), session.friendCode(), ack.appliedName());
                    cb.onSuccess(ack.appliedName());
                } else {
                    cb.onFailed(describeRenameFailReason(ack.reason()));
                }
                return true;
            }

            @Override
            public void onTimeout() {
                cb.onFailed("сервер аккаунтов не ответил");
            }
        });
    }

    // ---- friends (Phase 3) ----

    public void lookupFriendCode(String friendCode, FriendCodeLookupCallback cb) {
        AccountSession session = currentSession;
        if (session == null) {
            cb.onResult(false, new UUID(0, 0), false, "");
            return;
        }
        ensureSocketStarted();
        byte[] payload = AccountProtocol.encodeFriendCodeLookup(session.sessionToken(), friendCode);
        sendRequest(payload, new ReplyHandler() {
            @Override
            public boolean handle(int type, byte[] data, int length) {
                if (type != AccountProtocol.TYPE_FRIEND_CODE_LOOKUP_REPLY) {
                    return false;
                }
                AccountProtocol.FriendCodeLookupReply reply = AccountProtocol.decodeFriendCodeLookupReply(data, length);
                cb.onResult(reply.found(), reply.accountId(), reply.licensed(), reply.displayName());
                return true;
            }

            @Override
            public void onTimeout() {
                cb.onTimeout();
            }
        });
    }

    public void sendFriendRequest(UUID targetAccountId, AckCallback cb) {
        AccountSession session = currentSession;
        if (session == null) {
            cb.onFailed("не авторизован");
            return;
        }
        ensureSocketStarted();
        byte[] payload = AccountProtocol.encodeFriendRequestSend(session.sessionToken(), targetAccountId);
        sendRequest(payload, friendAckHandler(cb));
    }

    public void listIncomingRequests(FriendRequestListCallback cb) {
        AccountSession session = currentSession;
        if (session == null) {
            cb.onResult(java.util.List.of());
            return;
        }
        ensureSocketStarted();
        byte[] payload = AccountProtocol.encodeFriendRequestListQuery(session.sessionToken());
        sendRequest(payload, new ReplyHandler() {
            @Override
            public boolean handle(int type, byte[] data, int length) {
                if (type != AccountProtocol.TYPE_FRIEND_REQUEST_LIST_REPLY) {
                    return false;
                }
                AccountProtocol.FriendRequestListReply reply = AccountProtocol.decodeFriendRequestListReply(data, length);
                java.util.List<IncomingRequest> requests = new java.util.ArrayList<>();
                for (AccountProtocol.IncomingRequest r : reply.requests()) {
                    requests.add(new IncomingRequest(r.fromAccountId(), r.licensed(), r.displayName()));
                }
                cb.onResult(requests);
                return true;
            }

            @Override
            public void onTimeout() {
                cb.onTimeout();
            }
        });
    }

    public void respondToRequest(UUID fromAccountId, boolean accept, AckCallback cb) {
        AccountSession session = currentSession;
        if (session == null) {
            cb.onFailed("не авторизован");
            return;
        }
        ensureSocketStarted();
        byte[] payload = AccountProtocol.encodeFriendRequestRespond(session.sessionToken(), fromAccountId, accept);
        sendRequest(payload, friendAckHandler(cb));
    }

    public void removeFriend(UUID friendAccountId, AckCallback cb) {
        AccountSession session = currentSession;
        if (session == null) {
            cb.onFailed("не авторизован");
            return;
        }
        ensureSocketStarted();
        byte[] payload = AccountProtocol.encodeFriendRemove(session.sessionToken(), friendAccountId);
        sendRequest(payload, friendAckHandler(cb));
    }

    public void listFriends(FriendListCallback cb) {
        AccountSession session = currentSession;
        if (session == null) {
            cb.onResult(java.util.List.of());
            return;
        }
        ensureSocketStarted();
        byte[] payload = AccountProtocol.encodeFriendListQuery(session.sessionToken());
        sendRequest(payload, new ReplyHandler() {
            @Override
            public boolean handle(int type, byte[] data, int length) {
                if (type != AccountProtocol.TYPE_FRIEND_LIST_REPLY) {
                    return false;
                }
                AccountProtocol.FriendListReply reply = AccountProtocol.decodeFriendListReply(data, length);
                java.util.List<FriendInfo> friends = new java.util.ArrayList<>();
                for (AccountProtocol.FriendEntry f : reply.friends()) {
                    friends.add(new FriendInfo(f.accountId(), f.licensed(), f.displayName(), f.status(), f.roomCode()));
                }
                cb.onResult(friends);
                return true;
            }

            @Override
            public void onTimeout() {
                cb.onTimeout();
            }
        });
    }

    public void searchAccounts(String query, SearchCallback cb) {
        AccountSession session = currentSession;
        if (session == null) {
            cb.onResult(java.util.List.of());
            return;
        }
        ensureSocketStarted();
        byte[] payload = AccountProtocol.encodeSearchAccounts(session.sessionToken(), query);
        sendRequest(payload, new ReplyHandler() {
            @Override
            public boolean handle(int type, byte[] data, int length) {
                if (type != AccountProtocol.TYPE_SEARCH_ACCOUNTS_REPLY) {
                    return false;
                }
                AccountProtocol.SearchAccountsReply reply = AccountProtocol.decodeSearchAccountsReply(data, length);
                java.util.List<SearchResult> results = new java.util.ArrayList<>();
                for (AccountProtocol.SearchResult r : reply.results()) {
                    results.add(new SearchResult(r.accountId(), r.licensed(), r.displayName()));
                }
                cb.onResult(results);
                return true;
            }

            @Override
            public void onTimeout() {
                cb.onTimeout();
            }
        });
    }

    private ReplyHandler friendAckHandler(AckCallback cb) {
        return new ReplyHandler() {
            @Override
            public boolean handle(int type, byte[] data, int length) {
                if (type != AccountProtocol.TYPE_FRIEND_REQUEST_ACK) {
                    return false;
                }
                AccountProtocol.FriendRequestAck ack = AccountProtocol.decodeFriendRequestAck(data, length);
                if (ack.ok()) {
                    cb.onSuccess();
                } else {
                    cb.onFailed(describeFriendAckFailReason(ack.reason()));
                }
                return true;
            }

            @Override
            public void onTimeout() {
                cb.onFailed("сервер аккаунтов не ответил");
            }
        };
    }

    private static String describeFriendAckFailReason(byte reason) {
        return switch (reason) {
            case AccountProtocol.REASON_ALREADY_FRIENDS -> "вы уже друзья";
            case AccountProtocol.REASON_INVALID_TARGET -> "нельзя добавить в друзья самого себя";
            case AccountProtocol.REASON_REQUEST_NOT_FOUND -> "заявка не найдена (возможно, уже обработана)";
            case AccountProtocol.REASON_RATE_LIMITED -> "слишком много попыток, попробуйте позже";
            case AccountProtocol.REASON_UNKNOWN_ACCOUNT -> "аккаунт не найден или сессия истекла";
            default -> "неизвестная ошибка сервера аккаунтов (" + reason + ")";
        };
    }

    // ---- shared reply handling ----

    private ReplyHandler authOkOrFailHandler(AuthCallback cb) {
        return new ReplyHandler() {
            @Override
            public boolean handle(int type, byte[] data, int length) {
                if (type == AccountProtocol.TYPE_AUTH_OK) {
                    AccountProtocol.AuthOk ok = AccountProtocol.decodeAuthOk(data, length);
                    AccountSession session = new AccountSession(ok.accountId(), ok.sessionToken(), ok.rememberToken(),
                            ok.licensed(), ok.friendCode(), ok.displayName());
                    currentSession = session;
                    // Every login path (register/password-login/remembered-login/licensed
                    // confirm) funnels through here, so this is the one place presence needs
                    // to start — see the class docs on why it runs for the whole session, not
                    // just while a friends screen is open.
                    startPresenceHeartbeat();
                    cb.onSuccess(session);
                    return true;
                } else if (type == AccountProtocol.TYPE_AUTH_FAIL) {
                    cb.onFailed(describeAuthFailReason(AccountProtocol.decodeAuthFail(data, length).reason()));
                    return true;
                }
                return false;
            }

            @Override
            public void onTimeout() {
                cb.onFailed("сервер аккаунтов не ответил");
            }
        };
    }

    private static String describeAuthFailReason(byte reason) {
        return switch (reason) {
            case AccountProtocol.REASON_MOJANG_VERIFICATION_FAILED -> "не удалось подтвердить лицензию через Mojang";
            case AccountProtocol.REASON_USERNAME_INVALID -> "недопустимый ник (3-16 символов, без пробелов)";
            case AccountProtocol.REASON_BAD_CREDENTIALS -> "неверный код дружбы или пароль";
            case AccountProtocol.REASON_UNKNOWN_ACCOUNT -> "аккаунт не найден";
            case AccountProtocol.REASON_RATE_LIMITED -> "слишком много попыток, попробуйте позже";
            case AccountProtocol.REASON_UNKNOWN_REQUEST_ID -> "попытка входа устарела, начните заново";
            case AccountProtocol.REASON_MOJANG_UNAVAILABLE -> "сервис Mojang недоступен, попробуйте позже";
            default -> "неизвестная ошибка сервера аккаунтов (" + reason + ")";
        };
    }

    private static String describeRenameFailReason(byte reason) {
        return switch (reason) {
            case AccountProtocol.REASON_NAME_INVALID -> "недопустимый ник (3-16 символов, без пробелов)";
            case AccountProtocol.REASON_LICENSED_CANNOT_RENAME -> "у лицензионного аккаунта ник берётся из Mojang и не меняется вручную";
            case AccountProtocol.REASON_RATE_LIMITED -> "ник можно менять не так часто, попробуйте позже";
            case AccountProtocol.REASON_UNKNOWN_ACCOUNT -> "сессия истекла, войдите заново";
            default -> "неизвестная ошибка сервера аккаунтов (" + reason + ")";
        };
    }

    // ---- request/retry plumbing ----

    private interface ReplyHandler {
        /** @return true if this reply was for us (consumes the pending request); false to keep waiting. */
        boolean handle(int type, byte[] data, int length);

        void onTimeout();
    }

    private void sendRequest(byte[] payload, ReplyHandler handler) {
        new PendingRequest(payload, handler).start();
    }

    private final class PendingRequest {
        private final byte[] payload;
        private final ReplyHandler handler;
        private final AtomicBoolean done = new AtomicBoolean(false);

        PendingRequest(byte[] payload, ReplyHandler handler) {
            this.payload = payload;
            this.handler = handler;
        }

        void start() {
            pending.set(this);
            Thread retryThread = new Thread(this::retryLoop, "PeerCraft-AccountClient-Retry");
            retryThread.setDaemon(true);
            retryThread.start();
        }

        void tryHandle(int type, byte[] data, int length) {
            if (done.get()) {
                return;
            }
            if (handler.handle(type, data, length)) {
                finish();
            }
        }

        private void retryLoop() {
            long deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MILLIS;
            while (!done.get() && System.currentTimeMillis() < deadline) {
                DatagramSocket s = socket;
                InetAddress addr = rendezvousAddress;
                int port = rendezvousPort;
                if (s != null && !s.isClosed() && addr != null) {
                    try {
                        s.send(new DatagramPacket(payload, payload.length, addr, port));
                    } catch (Exception e) {
                        LOGGER.warn("[AccountClient] Не удалось отправить пакет серверу аккаунтов", e);
                    }
                }
                try {
                    Thread.sleep(RETRY_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (done.compareAndSet(false, true)) {
                pending.compareAndSet(this, null);
                handler.onTimeout();
            }
        }

        private void finish() {
            if (done.compareAndSet(false, true)) {
                pending.compareAndSet(this, null);
            }
        }
    }
}
