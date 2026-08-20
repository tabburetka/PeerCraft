package net.peercraft.rendezvous.account;

import net.peercraft.rendezvous.AccountProtocol;
import net.peercraft.rendezvous.CodeGenerator;
import net.peercraft.rendezvous.RateLimiter;

import java.net.InetAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Business logic for accounts/login/rename — the only class in this package
 * {@code RendezvousServer} talks to. Wire-protocol concerns (encode/decode) stay in
 * {@code RendezvousServer}/{@link AccountProtocol}; this class only deals in plain values.
 */
public final class AccountService {

    /** Generic Ok/Fail result carrier so every operation doesn't need its own Fail-shaped record. */
    public sealed interface Result<T> {
        record Ok<T>(T value) implements Result<T> {
        }

        record Fail<T>(byte reason) implements Result<T> {
        }
    }

    public record ServerIdChallengeInfo(long requestId, String serverId) {
    }

    public record LoginChallengeInfo(long requestId, byte[] salt, byte[] challenge) {
    }

    public record AuthOkInfo(UUID accountId, byte[] sessionToken, byte[] rememberToken, boolean licensed,
                              String friendCode, String displayName) {
    }

    public record RenameOutcomeInfo(String appliedName) {
    }

    /** No payload beyond ok/reason — used for the friend mutations (send/respond/remove). */
    public record AckInfo() {
    }

    public record FriendCodeLookupInfo(boolean found, UUID accountId, boolean licensed, String displayName) {
    }

    public record IncomingRequestInfo(UUID fromAccountId, boolean licensed, String displayName) {
    }

    public record FriendInfo(UUID accountId, boolean licensed, String displayName, int status, String roomCode) {
    }

    public record SearchResultInfo(UUID accountId, boolean licensed, String displayName) {
    }

    private static final int FRIEND_CODE_LENGTH = 6;
    private static final int REGISTER_RATE_LIMIT = 5;
    private static final long REGISTER_RATE_WINDOW_MILLIS = 60_000L;
    private static final int LOGIN_RATE_LIMIT = 10;
    private static final long LOGIN_RATE_WINDOW_MILLIS = 60_000L;
    private static final int RENAME_RATE_LIMIT = 1;
    private static final long RENAME_RATE_WINDOW_MILLIS = 5 * 60_000L;
    private static final int SEARCH_RATE_LIMIT = 1;
    private static final long SEARCH_RATE_WINDOW_MILLIS = 1_000L;
    private static final int FRIEND_REQUEST_RATE_LIMIT = 10;
    private static final long FRIEND_REQUEST_RATE_WINDOW_MILLIS = 60 * 60_000L;
    private static final int MAX_SEARCH_RESULTS = 20;

    private final AccountStore store;
    private final SessionRegistry sessions;
    private final PendingAuthRegistry pendingAuth;
    private final PresenceRegistry presence;
    private final MojangVerifier mojangVerifier;
    private final CodeGenerator friendCodeGenerator = new CodeGenerator(FRIEND_CODE_LENGTH);
    private final RateLimiter<InetAddress> registerRateLimiter;
    private final RateLimiter<InetAddress> loginRateLimiter;
    private final RateLimiter<UUID> renameRateLimiter;
    private final RateLimiter<UUID> searchRateLimiter;
    private final RateLimiter<UUID> friendRequestRateLimiter;

    /** Production entry point — RendezvousServer wires this up directly from its main(). */
    public AccountService(Path dataFile, boolean fakeMojang, LongSupplier clock) {
        this(new AccountStore(dataFile), new SessionRegistry(clock), new PendingAuthRegistry(clock),
                fakeMojang ? new FakeMojangVerifier() : new HttpMojangVerifier(), clock);
        Runtime.getRuntime().addShutdownHook(new Thread(store::saveIfDirty, "peercraft-account-store-shutdown-save"));
    }

    /** Test seam — lets tests hand-build every collaborator (fake clock, fake Mojang verifier, temp-dir store). */
    AccountService(AccountStore store, SessionRegistry sessions, PendingAuthRegistry pendingAuth,
                   MojangVerifier mojangVerifier, LongSupplier clock) {
        this.store = store;
        this.sessions = sessions;
        this.pendingAuth = pendingAuth;
        this.presence = new PresenceRegistry(clock);
        this.mojangVerifier = mojangVerifier;
        this.registerRateLimiter = new RateLimiter<>(REGISTER_RATE_LIMIT, REGISTER_RATE_WINDOW_MILLIS, clock);
        this.loginRateLimiter = new RateLimiter<>(LOGIN_RATE_LIMIT, LOGIN_RATE_WINDOW_MILLIS, clock);
        this.renameRateLimiter = new RateLimiter<>(RENAME_RATE_LIMIT, RENAME_RATE_WINDOW_MILLIS, clock);
        this.searchRateLimiter = new RateLimiter<>(SEARCH_RATE_LIMIT, SEARCH_RATE_WINDOW_MILLIS, clock);
        this.friendRequestRateLimiter = new RateLimiter<>(FRIEND_REQUEST_RATE_LIMIT, FRIEND_REQUEST_RATE_WINDOW_MILLIS, clock);
    }

    /** Called periodically (see RendezvousServer's existing sweeper) — persistence flush + TTL cleanup, not on the request path. */
    public void maintenance() {
        store.saveIfDirty();
        sessions.sweepExpired();
        pendingAuth.sweepExpired();
        presence.sweepExpired();
    }

    // ---- Licensed (Mojang) login ----

    public Result<ServerIdChallengeInfo> beginLicensedAuth(String username, InetAddress fromIp) {
        if (!isValidUsername(username)) {
            return new Result.Fail<>(AccountProtocol.REASON_USERNAME_INVALID);
        }
        if (!loginRateLimiter.allow(fromIp)) {
            return new Result.Fail<>(AccountProtocol.REASON_RATE_LIMITED);
        }
        String serverId = randomServerId();
        long requestId = pendingAuth.beginLicensed(username, serverId);
        return new Result.Ok<>(new ServerIdChallengeInfo(requestId, serverId));
    }

    public Result<AuthOkInfo> confirmLicensedAuth(long requestId) {
        Optional<PendingAuthRegistry.LicensedAttempt> attempt = pendingAuth.takeLicensed(requestId);
        if (attempt.isEmpty()) {
            return new Result.Fail<>(AccountProtocol.REASON_UNKNOWN_REQUEST_ID);
        }
        PendingAuthRegistry.LicensedAttempt pending = attempt.get();
        Optional<MojangVerifier.MojangProfile> profile = mojangVerifier.hasJoined(pending.username(), pending.serverId());
        if (profile.isEmpty()) {
            return new Result.Fail<>(AccountProtocol.REASON_MOJANG_VERIFICATION_FAILED);
        }

        UUID accountId = profile.get().id();
        String canonicalName = profile.get().name();
        Account account = store.byId(accountId).orElse(null);
        if (account == null) {
            account = new Account(accountId, canonicalName, true, generateFriendCode());
            store.add(account);
        } else if (!canonicalName.equals(account.displayName)) {
            // Mojang usernames can change — resync on every login rather than making the
            // player manually update it (they can't rename a licensed account manually anyway).
            account.displayName = canonicalName;
            store.markDirty();
        }
        return new Result.Ok<>(issueAuthOk(account));
    }

    // ---- Unlicensed registration ----

    public Result<AuthOkInfo> register(String username, byte[] salt, byte[] passwordHash, InetAddress fromIp) {
        if (!isValidUsername(username)) {
            return new Result.Fail<>(AccountProtocol.REASON_USERNAME_INVALID);
        }
        if (!registerRateLimiter.allow(fromIp)) {
            return new Result.Fail<>(AccountProtocol.REASON_RATE_LIMITED);
        }
        Account account = new Account(UUID.randomUUID(), username, false, generateFriendCode());
        account.passwordSalt = salt;
        account.passwordHash = passwordHash;
        store.add(account);
        return new Result.Ok<>(issueAuthOk(account));
    }

    // ---- Unlicensed password login (friend code or cached accountId) ----

    public Result<LoginChallengeInfo> beginPasswordLogin(boolean byFriendCode, UUID accountId, String friendCode, InetAddress fromIp) {
        if (!loginRateLimiter.allow(fromIp)) {
            return new Result.Fail<>(AccountProtocol.REASON_RATE_LIMITED);
        }
        Optional<Account> account = byFriendCode ? store.byFriendCode(friendCode) : store.byId(accountId);
        if (account.isEmpty() || account.get().licensed) {
            // Licensed accounts have no password to challenge — same failure as "doesn't
            // exist" so this endpoint doesn't leak which friend codes/ids are licensed.
            return new Result.Fail<>(AccountProtocol.REASON_UNKNOWN_ACCOUNT);
        }
        byte[] challenge = randomChallenge();
        long requestId = pendingAuth.beginPasswordLogin(account.get().accountId, challenge);
        return new Result.Ok<>(new LoginChallengeInfo(requestId, account.get().passwordSalt, challenge));
    }

    public Result<AuthOkInfo> completePasswordLogin(long requestId, byte[] hmac) {
        Optional<PendingAuthRegistry.PasswordLoginAttempt> attempt = pendingAuth.takePasswordLogin(requestId);
        if (attempt.isEmpty()) {
            return new Result.Fail<>(AccountProtocol.REASON_UNKNOWN_REQUEST_ID);
        }
        PendingAuthRegistry.PasswordLoginAttempt pending = attempt.get();
        Account account = store.byId(pending.accountId()).orElse(null);
        if (account == null) {
            return new Result.Fail<>(AccountProtocol.REASON_UNKNOWN_ACCOUNT);
        }
        byte[] expected = PasswordHasher.hmacChallenge(account.passwordHash, pending.challenge());
        if (!PasswordHasher.constantTimeEquals(expected, hmac)) {
            return new Result.Fail<>(AccountProtocol.REASON_BAD_CREDENTIALS);
        }
        return new Result.Ok<>(issueAuthOk(account));
    }

    // ---- Silent relogin on the same device ----

    public Result<AuthOkInfo> loginRemembered(UUID accountId, byte[] rememberToken) {
        Optional<Account> account = store.byId(accountId);
        if (account.isEmpty() || account.get().rememberToken == null
                || !PasswordHasher.constantTimeEquals(account.get().rememberToken, rememberToken)) {
            return new Result.Fail<>(AccountProtocol.REASON_BAD_CREDENTIALS);
        }
        return new Result.Ok<>(issueAuthOk(account.get()));
    }

    // ---- Rename (unlicensed only) ----

    public Result<RenameOutcomeInfo> rename(byte[] sessionToken, String newName) {
        Optional<UUID> accountId = sessions.validate(sessionToken);
        if (accountId.isEmpty()) {
            return new Result.Fail<>(AccountProtocol.REASON_UNKNOWN_ACCOUNT);
        }
        Account account = store.byId(accountId.get()).orElse(null);
        if (account == null) {
            return new Result.Fail<>(AccountProtocol.REASON_UNKNOWN_ACCOUNT);
        }
        if (account.licensed) {
            return new Result.Fail<>(AccountProtocol.REASON_LICENSED_CANNOT_RENAME);
        }
        if (!isValidUsername(newName)) {
            return new Result.Fail<>(AccountProtocol.REASON_NAME_INVALID);
        }
        if (!renameRateLimiter.allow(account.accountId)) {
            return new Result.Fail<>(AccountProtocol.REASON_RATE_LIMITED);
        }
        account.displayName = newName;
        store.markDirty();
        return new Result.Ok<>(new RenameOutcomeInfo(newName));
    }

    // ---- Friends (Фаза 3) ----

    public Result<FriendCodeLookupInfo> lookupFriendCode(byte[] sessionToken, String friendCode) {
        if (sessions.validate(sessionToken).isEmpty()) {
            return new Result.Fail<>(AccountProtocol.REASON_UNKNOWN_ACCOUNT);
        }
        Optional<Account> target = store.byFriendCode(friendCode);
        if (target.isEmpty()) {
            // found=false — accountId/licensed/displayName are meaningless placeholders the
            // client must not use (see AccountProtocol.FriendCodeLookupReply's doc comment).
            return new Result.Ok<>(new FriendCodeLookupInfo(false, new UUID(0, 0), false, ""));
        }
        Account t = target.get();
        return new Result.Ok<>(new FriendCodeLookupInfo(true, t.accountId, t.licensed, t.displayName));
    }

    public Result<AckInfo> sendFriendRequest(byte[] sessionToken, UUID targetAccountId) {
        Optional<UUID> requesterId = sessions.validate(sessionToken);
        if (requesterId.isEmpty()) {
            return new Result.Fail<>(AccountProtocol.REASON_UNKNOWN_ACCOUNT);
        }
        if (requesterId.get().equals(targetAccountId)) {
            return new Result.Fail<>(AccountProtocol.REASON_INVALID_TARGET);
        }
        if (!friendRequestRateLimiter.allow(requesterId.get())) {
            return new Result.Fail<>(AccountProtocol.REASON_RATE_LIMITED);
        }
        Account requester = store.byId(requesterId.get()).orElse(null);
        Account target = store.byId(targetAccountId).orElse(null);
        if (requester == null || target == null) {
            return new Result.Fail<>(AccountProtocol.REASON_UNKNOWN_ACCOUNT);
        }
        if (requester.friends.contains(targetAccountId)) {
            return new Result.Fail<>(AccountProtocol.REASON_ALREADY_FRIENDS);
        }
        // Idempotent by design — a UDP retry of the same SEND (or the target simply not
        // having responded yet) must not error out just because a request is already pending.
        requester.outgoingRequests.add(targetAccountId);
        target.incomingRequests.add(requesterId.get());
        store.markDirty();
        return new Result.Ok<>(new AckInfo());
    }

    public Result<AckInfo> respondToRequest(byte[] sessionToken, UUID fromAccountId, boolean accept) {
        Optional<UUID> responderId = sessions.validate(sessionToken);
        if (responderId.isEmpty()) {
            return new Result.Fail<>(AccountProtocol.REASON_UNKNOWN_ACCOUNT);
        }
        Account responder = store.byId(responderId.get()).orElse(null);
        Account requester = store.byId(fromAccountId).orElse(null);
        if (responder == null || requester == null) {
            return new Result.Fail<>(AccountProtocol.REASON_UNKNOWN_ACCOUNT);
        }
        if (!responder.incomingRequests.contains(fromAccountId)) {
            return new Result.Fail<>(AccountProtocol.REASON_REQUEST_NOT_FOUND);
        }
        responder.incomingRequests.remove(fromAccountId);
        requester.outgoingRequests.remove(responderId.get());
        if (accept) {
            responder.friends.add(fromAccountId);
            requester.friends.add(responderId.get());
        }
        store.markDirty();
        return new Result.Ok<>(new AckInfo());
    }

    /** Idempotent — removing someone who isn't (or is no longer) a friend is a no-op success, not an error. */
    public Result<AckInfo> removeFriend(byte[] sessionToken, UUID friendAccountId) {
        Optional<UUID> accountId = sessions.validate(sessionToken);
        if (accountId.isEmpty()) {
            return new Result.Fail<>(AccountProtocol.REASON_UNKNOWN_ACCOUNT);
        }
        Account account = store.byId(accountId.get()).orElse(null);
        if (account == null) {
            return new Result.Fail<>(AccountProtocol.REASON_UNKNOWN_ACCOUNT);
        }
        account.friends.remove(friendAccountId);
        store.byId(friendAccountId).ifPresent(friend -> friend.friends.remove(accountId.get()));
        store.markDirty();
        return new Result.Ok<>(new AckInfo());
    }

    /** Never fails outright — an invalid/expired session just yields an empty list, matching this being a low-stakes poll endpoint. */
    public List<IncomingRequestInfo> listIncomingRequests(byte[] sessionToken) {
        Optional<Account> account = sessions.validate(sessionToken).flatMap(store::byId);
        if (account.isEmpty()) {
            return List.of();
        }
        List<IncomingRequestInfo> result = new ArrayList<>();
        for (UUID fromId : account.get().incomingRequests) {
            store.byId(fromId).ifPresent(from -> result.add(new IncomingRequestInfo(fromId, from.licensed, from.displayName)));
        }
        return result;
    }

    /**
     * Never fails outright, same as {@link #listIncomingRequests}. {@code status}/{@code roomCode}
     * are always OFFLINE/empty until Фаза 4 wires in {@code PresenceRegistry}.
     */
    public List<FriendInfo> listFriends(byte[] sessionToken) {
        Optional<Account> account = sessions.validate(sessionToken).flatMap(store::byId);
        if (account.isEmpty()) {
            return List.of();
        }
        List<FriendInfo> result = new ArrayList<>();
        for (UUID friendId : account.get().friends) {
            store.byId(friendId).ifPresent(f -> {
                PresenceRegistry.Status status = presence.statusOf(friendId);
                result.add(new FriendInfo(friendId, f.licensed, f.displayName, status.statusCode(), status.roomCode()));
            });
        }
        return result;
    }

    // ---- Presence (Фаза 4) ----

    /**
     * Used by RendezvousServer.handleRegister to verify a REGISTER's self-reported accountId
     * actually belongs to the session that sent it — a REGISTER must never be trusted to
     * claim an arbitrary accountId unchecked (that would let anyone impersonate any account's
     * "hosting" status to their friends).
     */
    public Optional<UUID> resolveSession(byte[] sessionToken) {
        return sessions.validate(sessionToken);
    }

    /** Called by RendezvousServer.handleRegister once a REGISTER's account has been verified via resolveSession. */
    public void setHosting(UUID accountId, String roomCode) {
        presence.setHosting(accountId, roomCode);
    }

    public void heartbeat(byte[] sessionToken) {
        sessions.validate(sessionToken).ifPresent(presence::heartbeat);
    }

    public void stopPresence(byte[] sessionToken) {
        sessions.validate(sessionToken).ifPresent(presence::stop);
    }

    /** Substring match on display name, licensed accounts sorted first — see the accounts+friends plan for why. */
    public Result<List<SearchResultInfo>> search(byte[] sessionToken, String query) {
        Optional<UUID> accountId = sessions.validate(sessionToken);
        if (accountId.isEmpty()) {
            return new Result.Fail<>(AccountProtocol.REASON_UNKNOWN_ACCOUNT);
        }
        if (!searchRateLimiter.allow(accountId.get())) {
            return new Result.Fail<>(AccountProtocol.REASON_RATE_LIMITED);
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<SearchResultInfo> licensedMatches = new ArrayList<>();
        List<SearchResultInfo> unlicensedMatches = new ArrayList<>();
        for (Account candidate : store.all()) {
            if (candidate.accountId.equals(accountId.get())) {
                continue;
            }
            if (!candidate.displayName.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            SearchResultInfo info = new SearchResultInfo(candidate.accountId, candidate.licensed, candidate.displayName);
            (candidate.licensed ? licensedMatches : unlicensedMatches).add(info);
        }
        List<SearchResultInfo> combined = new ArrayList<>(licensedMatches);
        combined.addAll(unlicensedMatches);
        if (combined.size() > MAX_SEARCH_RESULTS) {
            combined = combined.subList(0, MAX_SEARCH_RESULTS);
        }
        return new Result.Ok<>(combined);
    }

    // ---- shared helpers ----

    private AuthOkInfo issueAuthOk(Account account) {
        byte[] sessionToken = sessions.issue(account.accountId);
        byte[] rememberToken = randomToken();
        account.rememberToken = rememberToken;
        store.markDirty();
        return new AuthOkInfo(account.accountId, sessionToken, rememberToken, account.licensed, account.friendCode, account.displayName);
    }

    private String generateFriendCode() {
        return friendCodeGenerator.generateUnique(store::friendCodeTaken);
    }

    private static boolean isValidUsername(String name) {
        if (name == null || name.length() < 3 || name.length() > 16) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x21 || c > 0x7E) { // printable ASCII, no whitespace
                return false;
            }
        }
        return true;
    }

    private static String randomServerId() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] randomChallenge() {
        byte[] challenge = new byte[AccountProtocol.CHALLENGE_LENGTH];
        new SecureRandom().nextBytes(challenge);
        return challenge;
    }

    private static byte[] randomToken() {
        byte[] token = new byte[AccountProtocol.TOKEN_LENGTH];
        new SecureRandom().nextBytes(token);
        return token;
    }
}
