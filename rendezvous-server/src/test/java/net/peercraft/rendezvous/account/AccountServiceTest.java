package net.peercraft.rendezvous.account;

import net.peercraft.rendezvous.AccountProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AccountServiceTest {

    private static InetAddress ip(String s) throws UnknownHostException {
        return InetAddress.getByName(s);
    }

    private static AccountService newService(Path dataFile, AtomicLong clock, MojangVerifier verifier) {
        return new AccountService(new AccountStore(dataFile), new SessionRegistry(clock::get),
                new PendingAuthRegistry(clock::get), verifier, clock::get);
    }

    private static AccountService newService(Path dataFile, AtomicLong clock) {
        return newService(dataFile, clock, new FakeMojangVerifier());
    }

    // ---- registration ----

    @Test
    void registerCreatesNewUnlicensedAccountAndIssuesSession(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));

        AccountService.Result<AccountService.AuthOkInfo> result =
                service.register("Pirate1", new byte[16], new byte[32], ip("10.0.0.1"));

        assertInstanceOf(AccountService.Result.Ok.class, result);
        AccountService.AuthOkInfo info = ((AccountService.Result.Ok<AccountService.AuthOkInfo>) result).value();
        assertFalse(info.licensed());
        assertEquals("Pirate1", info.displayName());
        assertEquals(6, info.friendCode().length());
        assertEquals(16, info.sessionToken().length);
        assertEquals(16, info.rememberToken().length);
    }

    @Test
    void registerRejectsTooShortUsername(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));

        AccountService.Result<AccountService.AuthOkInfo> result =
                service.register("ab", new byte[16], new byte[32], ip("10.0.0.1"));

        assertEquals(AccountProtocol.REASON_USERNAME_INVALID, ((AccountService.Result.Fail<?>) result).reason());
    }

    @Test
    void registerRejectsUsernameWithWhitespace(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));

        AccountService.Result<AccountService.AuthOkInfo> result =
                service.register("a b", new byte[16], new byte[32], ip("10.0.0.1"));

        assertEquals(AccountProtocol.REASON_USERNAME_INVALID, ((AccountService.Result.Fail<?>) result).reason());
    }

    @Test
    void registerIsRateLimitedPerSourceIp(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        InetAddress from = ip("10.0.0.1");

        for (int i = 0; i < 5; i++) {
            assertInstanceOf(AccountService.Result.Ok.class, service.register("Name" + i, new byte[16], new byte[32], from));
        }
        AccountService.Result<AccountService.AuthOkInfo> sixth = service.register("Name5", new byte[16], new byte[32], from);

        assertEquals(AccountProtocol.REASON_RATE_LIMITED, ((AccountService.Result.Fail<?>) sixth).reason());
    }

    // ---- licensed login ----

    @Test
    void licensedLoginCreatesAccountUsingMojangUuidAndName(@TempDir Path tempDir) throws UnknownHostException {
        UUID mojangId = UUID.randomUUID();
        MojangVerifier verifier = (username, serverId) -> Optional.of(new MojangVerifier.MojangProfile(mojangId, "RealSteve"));
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0), verifier);

        var begin = (AccountService.Result.Ok<AccountService.ServerIdChallengeInfo>) service.beginLicensedAuth("RealSteve", ip("10.0.0.1"));
        var confirm = service.confirmLicensedAuth(begin.value().requestId());

        assertInstanceOf(AccountService.Result.Ok.class, confirm);
        AccountService.AuthOkInfo info = ((AccountService.Result.Ok<AccountService.AuthOkInfo>) confirm).value();
        assertTrue(info.licensed());
        assertEquals(mojangId, info.accountId());
        assertEquals("RealSteve", info.displayName());
    }

    @Test
    void licensedLoginResyncsDisplayNameOnRename(@TempDir Path tempDir) throws UnknownHostException {
        UUID mojangId = UUID.randomUUID();
        String[] currentMojangName = {"OldName"};
        MojangVerifier verifier = (username, serverId) -> Optional.of(new MojangVerifier.MojangProfile(mojangId, currentMojangName[0]));
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0), verifier);

        var begin1 = (AccountService.Result.Ok<AccountService.ServerIdChallengeInfo>) service.beginLicensedAuth("OldName", ip("10.0.0.1"));
        service.confirmLicensedAuth(begin1.value().requestId());

        currentMojangName[0] = "NewName"; // player renamed on Mojang's side between logins
        var begin2 = (AccountService.Result.Ok<AccountService.ServerIdChallengeInfo>) service.beginLicensedAuth("NewName", ip("10.0.0.1"));
        var confirm2 = (AccountService.Result.Ok<AccountService.AuthOkInfo>) service.confirmLicensedAuth(begin2.value().requestId());

        assertEquals(mojangId, confirm2.value().accountId()); // same account, not a duplicate
        assertEquals("NewName", confirm2.value().displayName());
    }

    @Test
    void confirmLicensedAuthFailsForUnknownRequestId(@TempDir Path tempDir) {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));

        var result = service.confirmLicensedAuth(123456789L);

        assertEquals(AccountProtocol.REASON_UNKNOWN_REQUEST_ID, ((AccountService.Result.Fail<?>) result).reason());
    }

    @Test
    void confirmLicensedAuthFailsWhenMojangDoesNotConfirm(@TempDir Path tempDir) throws UnknownHostException {
        MojangVerifier alwaysRejects = (username, serverId) -> Optional.empty();
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0), alwaysRejects);

        var begin = (AccountService.Result.Ok<AccountService.ServerIdChallengeInfo>) service.beginLicensedAuth("Steve", ip("10.0.0.1"));
        var confirm = service.confirmLicensedAuth(begin.value().requestId());

        assertEquals(AccountProtocol.REASON_MOJANG_VERIFICATION_FAILED, ((AccountService.Result.Fail<?>) confirm).reason());
    }

    @Test
    void pendingLicensedAttemptExpiresAfterTtl(@TempDir Path tempDir) throws UnknownHostException {
        AtomicLong clock = new AtomicLong(0);
        AccountService service = newService(tempDir.resolve("a.json"), clock);

        var begin = (AccountService.Result.Ok<AccountService.ServerIdChallengeInfo>) service.beginLicensedAuth("Steve", ip("10.0.0.1"));
        clock.set(PendingAuthRegistry.ATTEMPT_TTL_MILLIS + 1);
        var confirm = service.confirmLicensedAuth(begin.value().requestId());

        assertEquals(AccountProtocol.REASON_UNKNOWN_REQUEST_ID, ((AccountService.Result.Fail<?>) confirm).reason());
    }

    // ---- unlicensed password login ----

    private static final java.util.concurrent.atomic.AtomicInteger REGISTER_IP_COUNTER = new java.util.concurrent.atomic.AtomicInteger(0);

    private AccountService.AuthOkInfo registerAndGetInfo(AccountService service, String name) throws UnknownHostException {
        // A fresh IP per call — tests that register many accounts in one AccountService
        // instance (e.g. friend-request rate-limit tests) would otherwise trip the unrelated
        // per-IP REGISTER rate limiter, since the test clock never advances to slide its window.
        String ip = "10.0." + (REGISTER_IP_COUNTER.incrementAndGet() % 250) + ".2";
        var result = (AccountService.Result.Ok<AccountService.AuthOkInfo>) service.register(name, PasswordHasher.randomSalt(), new byte[32], ip(ip));
        return result.value();
    }

    @Test
    void passwordLoginByFriendCodeSucceedsWithCorrectResponse(@TempDir Path tempDir) throws UnknownHostException {
        AtomicLong clock = new AtomicLong(0);
        AccountService service = newService(tempDir.resolve("a.json"), clock);
        byte[] salt = PasswordHasher.randomSalt();
        byte[] hash = PasswordHasher.hash("hunter2".toCharArray(), salt);
        var registered = (AccountService.Result.Ok<AccountService.AuthOkInfo>) service.register("Pirate1", salt, hash, ip("10.0.0.2"));
        String friendCode = registered.value().friendCode();

        var begin = (AccountService.Result.Ok<AccountService.LoginChallengeInfo>) service.beginPasswordLogin(true, null, friendCode, ip("10.0.0.3"));
        byte[] rederivedHash = PasswordHasher.hash("hunter2".toCharArray(), begin.value().salt());
        byte[] response = PasswordHasher.hmacChallenge(rederivedHash, begin.value().challenge());
        var complete = service.completePasswordLogin(begin.value().requestId(), response);

        assertInstanceOf(AccountService.Result.Ok.class, complete);
        AccountService.AuthOkInfo info = ((AccountService.Result.Ok<AccountService.AuthOkInfo>) complete).value();
        assertEquals(registered.value().accountId(), info.accountId());
    }

    @Test
    void passwordLoginByAccountIdSucceeds(@TempDir Path tempDir) throws UnknownHostException {
        AtomicLong clock = new AtomicLong(0);
        AccountService service = newService(tempDir.resolve("a.json"), clock);
        byte[] salt = PasswordHasher.randomSalt();
        byte[] hash = PasswordHasher.hash("hunter2".toCharArray(), salt);
        var registered = (AccountService.Result.Ok<AccountService.AuthOkInfo>) service.register("Pirate1", salt, hash, ip("10.0.0.2"));

        var begin = (AccountService.Result.Ok<AccountService.LoginChallengeInfo>) service.beginPasswordLogin(false, registered.value().accountId(), null, ip("10.0.0.3"));
        byte[] rederivedHash = PasswordHasher.hash("hunter2".toCharArray(), begin.value().salt());
        byte[] response = PasswordHasher.hmacChallenge(rederivedHash, begin.value().challenge());
        var complete = service.completePasswordLogin(begin.value().requestId(), response);

        assertInstanceOf(AccountService.Result.Ok.class, complete);
    }

    @Test
    void passwordLoginFailsWithWrongPassword(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        byte[] salt = PasswordHasher.randomSalt();
        byte[] hash = PasswordHasher.hash("hunter2".toCharArray(), salt);
        var registered = (AccountService.Result.Ok<AccountService.AuthOkInfo>) service.register("Pirate1", salt, hash, ip("10.0.0.2"));

        var begin = (AccountService.Result.Ok<AccountService.LoginChallengeInfo>) service.beginPasswordLogin(true, null, registered.value().friendCode(), ip("10.0.0.3"));
        byte[] wrongHash = PasswordHasher.hash("wrongpassword".toCharArray(), begin.value().salt());
        byte[] wrongResponse = PasswordHasher.hmacChallenge(wrongHash, begin.value().challenge());
        var complete = service.completePasswordLogin(begin.value().requestId(), wrongResponse);

        assertEquals(AccountProtocol.REASON_BAD_CREDENTIALS, ((AccountService.Result.Fail<?>) complete).reason());
    }

    @Test
    void passwordLoginRejectsLicensedAccountsTargetedByFriendCode(@TempDir Path tempDir) throws UnknownHostException {
        UUID mojangId = UUID.randomUUID();
        MojangVerifier verifier = (username, serverId) -> Optional.of(new MojangVerifier.MojangProfile(mojangId, "RealSteve"));
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0), verifier);
        var begin1 = (AccountService.Result.Ok<AccountService.ServerIdChallengeInfo>) service.beginLicensedAuth("RealSteve", ip("10.0.0.1"));
        var licensedInfo = (AccountService.Result.Ok<AccountService.AuthOkInfo>) service.confirmLicensedAuth(begin1.value().requestId());

        var attempt = service.beginPasswordLogin(true, null, licensedInfo.value().friendCode(), ip("10.0.0.3"));

        assertEquals(AccountProtocol.REASON_UNKNOWN_ACCOUNT, ((AccountService.Result.Fail<?>) attempt).reason());
    }

    @Test
    void beginPasswordLoginFailsForUnknownFriendCode(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));

        var result = service.beginPasswordLogin(true, null, "ZZZZZZ", ip("10.0.0.3"));

        assertEquals(AccountProtocol.REASON_UNKNOWN_ACCOUNT, ((AccountService.Result.Fail<?>) result).reason());
    }

    // ---- remembered (silent) login ----

    @Test
    void loginRememberedSucceedsWithCorrectTokenAndRotatesIt(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo initial = registerAndGetInfo(service, "Pirate1");

        var first = (AccountService.Result.Ok<AccountService.AuthOkInfo>) service.loginRemembered(initial.accountId(), initial.rememberToken());
        assertEquals(initial.accountId(), first.value().accountId());
        assertFalse(java.util.Arrays.equals(initial.rememberToken(), first.value().rememberToken()), "remember token must rotate on use");

        // The OLD token must no longer work — otherwise a sniffed token could be reused forever.
        var replay = service.loginRemembered(initial.accountId(), initial.rememberToken());
        assertEquals(AccountProtocol.REASON_BAD_CREDENTIALS, ((AccountService.Result.Fail<?>) replay).reason());
    }

    @Test
    void loginRememberedFailsWithWrongToken(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo initial = registerAndGetInfo(service, "Pirate1");

        var result = service.loginRemembered(initial.accountId(), new byte[16]);

        assertEquals(AccountProtocol.REASON_BAD_CREDENTIALS, ((AccountService.Result.Fail<?>) result).reason());
    }

    @Test
    void loginRememberedFailsForUnknownAccount(@TempDir Path tempDir) {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));

        var result = service.loginRemembered(UUID.randomUUID(), new byte[16]);

        assertEquals(AccountProtocol.REASON_BAD_CREDENTIALS, ((AccountService.Result.Fail<?>) result).reason());
    }

    // ---- rename ----

    @Test
    void renameSucceedsForUnlicensedAccount(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo info = registerAndGetInfo(service, "OldNick");

        var result = service.rename(info.sessionToken(), "NewNick");

        assertInstanceOf(AccountService.Result.Ok.class, result);
        assertEquals("NewNick", ((AccountService.Result.Ok<AccountService.RenameOutcomeInfo>) result).value().appliedName());
    }

    @Test
    void renameRejectedForLicensedAccount(@TempDir Path tempDir) throws UnknownHostException {
        UUID mojangId = UUID.randomUUID();
        MojangVerifier verifier = (username, serverId) -> Optional.of(new MojangVerifier.MojangProfile(mojangId, "RealSteve"));
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0), verifier);
        var begin = (AccountService.Result.Ok<AccountService.ServerIdChallengeInfo>) service.beginLicensedAuth("RealSteve", ip("10.0.0.1"));
        var info = (AccountService.Result.Ok<AccountService.AuthOkInfo>) service.confirmLicensedAuth(begin.value().requestId());

        var result = service.rename(info.value().sessionToken(), "NewNick");

        assertEquals(AccountProtocol.REASON_LICENSED_CANNOT_RENAME, ((AccountService.Result.Fail<?>) result).reason());
    }

    @Test
    void renameRejectedForTooShortName(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo info = registerAndGetInfo(service, "OldNick");

        var result = service.rename(info.sessionToken(), "ab");

        assertEquals(AccountProtocol.REASON_NAME_INVALID, ((AccountService.Result.Fail<?>) result).reason());
    }

    @Test
    void renameRejectedForUnknownSession(@TempDir Path tempDir) {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));

        var result = service.rename(new byte[16], "NewNick");

        assertEquals(AccountProtocol.REASON_UNKNOWN_ACCOUNT, ((AccountService.Result.Fail<?>) result).reason());
    }

    @Test
    void renameIsRateLimitedPerAccount(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo info = registerAndGetInfo(service, "OldNick");

        assertInstanceOf(AccountService.Result.Ok.class, service.rename(info.sessionToken(), "NewNick"));
        var second = service.rename(info.sessionToken(), "NewNick2");

        assertEquals(AccountProtocol.REASON_RATE_LIMITED, ((AccountService.Result.Fail<?>) second).reason());
    }

    // ---- friends (Phase 3) ----

    @Test
    void lookupFriendCodeFindsExistingAccount(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo viewer = registerAndGetInfo(service, "Viewer");
        AccountService.AuthOkInfo target = registerAndGetInfo(service, "Target");

        var result = (AccountService.Result.Ok<AccountService.FriendCodeLookupInfo>) service.lookupFriendCode(viewer.sessionToken(), target.friendCode());

        assertTrue(result.value().found());
        assertEquals(target.accountId(), result.value().accountId());
        assertEquals("Target", result.value().displayName());
    }

    @Test
    void lookupFriendCodeReportsNotFoundForUnknownCode(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo viewer = registerAndGetInfo(service, "Viewer");

        var result = (AccountService.Result.Ok<AccountService.FriendCodeLookupInfo>) service.lookupFriendCode(viewer.sessionToken(), "ZZZZZZ");

        assertFalse(result.value().found());
    }

    @Test
    void sendFriendRequestThenAcceptMakesBothAccountsMutualFriends(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo alice = registerAndGetInfo(service, "Alice");
        AccountService.AuthOkInfo bob = registerAndGetInfo(service, "Bob");

        assertInstanceOf(AccountService.Result.Ok.class, service.sendFriendRequest(alice.sessionToken(), bob.accountId()));

        var incoming = service.listIncomingRequests(bob.sessionToken());
        assertEquals(1, incoming.size());
        assertEquals(alice.accountId(), incoming.get(0).fromAccountId());
        assertEquals("Alice", incoming.get(0).displayName());

        assertInstanceOf(AccountService.Result.Ok.class, service.respondToRequest(bob.sessionToken(), alice.accountId(), true));

        var aliceFriends = service.listFriends(alice.sessionToken());
        var bobFriends = service.listFriends(bob.sessionToken());
        assertEquals(1, aliceFriends.size());
        assertEquals(bob.accountId(), aliceFriends.get(0).accountId());
        assertEquals(1, bobFriends.size());
        assertEquals(alice.accountId(), bobFriends.get(0).accountId());
        // The request must be cleared from both sides' pending lists once resolved.
        assertTrue(service.listIncomingRequests(bob.sessionToken()).isEmpty());
    }

    @Test
    void sendFriendRequestThenDeclineLeavesNoFriendshipOrPendingRequest(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo alice = registerAndGetInfo(service, "Alice");
        AccountService.AuthOkInfo bob = registerAndGetInfo(service, "Bob");

        service.sendFriendRequest(alice.sessionToken(), bob.accountId());
        assertInstanceOf(AccountService.Result.Ok.class, service.respondToRequest(bob.sessionToken(), alice.accountId(), false));

        assertTrue(service.listFriends(alice.sessionToken()).isEmpty());
        assertTrue(service.listFriends(bob.sessionToken()).isEmpty());
        assertTrue(service.listIncomingRequests(bob.sessionToken()).isEmpty());
    }

    @Test
    void sendFriendRequestIsIdempotentForARepeatedRequest(@TempDir Path tempDir) throws UnknownHostException {
        // Simulates a UDP retry of the same SEND — must not error just because a request is
        // already pending.
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo alice = registerAndGetInfo(service, "Alice");
        AccountService.AuthOkInfo bob = registerAndGetInfo(service, "Bob");

        assertInstanceOf(AccountService.Result.Ok.class, service.sendFriendRequest(alice.sessionToken(), bob.accountId()));
        assertInstanceOf(AccountService.Result.Ok.class, service.sendFriendRequest(alice.sessionToken(), bob.accountId()));

        assertEquals(1, service.listIncomingRequests(bob.sessionToken()).size());
    }

    @Test
    void sendFriendRequestRejectsSelfTarget(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo alice = registerAndGetInfo(service, "Alice");

        var result = service.sendFriendRequest(alice.sessionToken(), alice.accountId());

        assertEquals(AccountProtocol.REASON_INVALID_TARGET, ((AccountService.Result.Fail<?>) result).reason());
    }

    @Test
    void sendFriendRequestRejectsWhenAlreadyFriends(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo alice = registerAndGetInfo(service, "Alice");
        AccountService.AuthOkInfo bob = registerAndGetInfo(service, "Bob");
        service.sendFriendRequest(alice.sessionToken(), bob.accountId());
        service.respondToRequest(bob.sessionToken(), alice.accountId(), true);

        var result = service.sendFriendRequest(alice.sessionToken(), bob.accountId());

        assertEquals(AccountProtocol.REASON_ALREADY_FRIENDS, ((AccountService.Result.Fail<?>) result).reason());
    }

    @Test
    void respondToRequestFailsWhenNoSuchRequestExists(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo alice = registerAndGetInfo(service, "Alice");
        AccountService.AuthOkInfo bob = registerAndGetInfo(service, "Bob");

        var result = service.respondToRequest(bob.sessionToken(), alice.accountId(), true);

        assertEquals(AccountProtocol.REASON_REQUEST_NOT_FOUND, ((AccountService.Result.Fail<?>) result).reason());
    }

    @Test
    void removeFriendMakesBothSidesForgetEachOther(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo alice = registerAndGetInfo(service, "Alice");
        AccountService.AuthOkInfo bob = registerAndGetInfo(service, "Bob");
        service.sendFriendRequest(alice.sessionToken(), bob.accountId());
        service.respondToRequest(bob.sessionToken(), alice.accountId(), true);

        assertInstanceOf(AccountService.Result.Ok.class, service.removeFriend(alice.sessionToken(), bob.accountId()));

        assertTrue(service.listFriends(alice.sessionToken()).isEmpty());
        assertTrue(service.listFriends(bob.sessionToken()).isEmpty());
    }

    @Test
    void removeFriendOnNonFriendIsIdempotentNoOp(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo alice = registerAndGetInfo(service, "Alice");
        AccountService.AuthOkInfo bob = registerAndGetInfo(service, "Bob");

        assertInstanceOf(AccountService.Result.Ok.class, service.removeFriend(alice.sessionToken(), bob.accountId()));
    }

    @Test
    void listIncomingRequestsAndListFriendsReturnEmptyForInvalidSessionRatherThanFailing(@TempDir Path tempDir) {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));

        assertTrue(service.listIncomingRequests(new byte[16]).isEmpty());
        assertTrue(service.listFriends(new byte[16]).isEmpty());
    }

    @Test
    void friendRequestIsRateLimitedPerAccount(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo alice = registerAndGetInfo(service, "Alice");
        for (int i = 0; i < 10; i++) {
            AccountService.AuthOkInfo target = registerAndGetInfo(service, "Target" + i);
            assertInstanceOf(AccountService.Result.Ok.class, service.sendFriendRequest(alice.sessionToken(), target.accountId()));
        }
        AccountService.AuthOkInfo eleventh = registerAndGetInfo(service, "Target10");

        var result = service.sendFriendRequest(alice.sessionToken(), eleventh.accountId());

        assertEquals(AccountProtocol.REASON_RATE_LIMITED, ((AccountService.Result.Fail<?>) result).reason());
    }

    @Test
    void searchSortsLicensedAccountsBeforeUnlicensedRegardlessOfNameOrder(@TempDir Path tempDir) throws UnknownHostException {
        UUID mojangId = UUID.randomUUID();
        MojangVerifier verifier = (username, serverId) -> Optional.of(new MojangVerifier.MojangProfile(mojangId, "SteveReal"));
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0), verifier);
        AccountService.AuthOkInfo viewer = registerAndGetInfo(service, "Viewer");
        registerAndGetInfo(service, "SteveFake");
        var begin = (AccountService.Result.Ok<AccountService.ServerIdChallengeInfo>) service.beginLicensedAuth("SteveReal", ip("10.0.0.9"));
        service.confirmLicensedAuth(begin.value().requestId());

        var result = (AccountService.Result.Ok<java.util.List<AccountService.SearchResultInfo>>) service.search(viewer.sessionToken(), "Steve");

        assertEquals(2, result.value().size());
        assertTrue(result.value().get(0).licensed());
        assertEquals("SteveReal", result.value().get(0).displayName());
        assertFalse(result.value().get(1).licensed());
        assertEquals("SteveFake", result.value().get(1).displayName());
    }

    @Test
    void searchExcludesTheSearchingAccountItself(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo viewer = registerAndGetInfo(service, "ViewerName");

        var result = (AccountService.Result.Ok<java.util.List<AccountService.SearchResultInfo>>) service.search(viewer.sessionToken(), "Viewer");

        assertTrue(result.value().isEmpty());
    }

    @Test
    void searchIsRateLimitedPerAccount(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo viewer = registerAndGetInfo(service, "Viewer");

        assertInstanceOf(AccountService.Result.Ok.class, service.search(viewer.sessionToken(), "a"));
        var second = service.search(viewer.sessionToken(), "a");

        assertEquals(AccountProtocol.REASON_RATE_LIMITED, ((AccountService.Result.Fail<?>) second).reason());
    }

    // ---- presence (Phase 4) ----

    @Test
    void resolveSessionReturnsAccountIdForValidToken(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo info = registerAndGetInfo(service, "Steve");

        assertEquals(java.util.Optional.of(info.accountId()), service.resolveSession(info.sessionToken()));
    }

    @Test
    void resolveSessionReturnsEmptyForUnknownToken(@TempDir Path tempDir) {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));

        assertTrue(service.resolveSession(new byte[16]).isEmpty());
    }

    @Test
    void listFriendsReflectsHostingStatusAfterSetHosting(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo alice = registerAndGetInfo(service, "Alice");
        AccountService.AuthOkInfo bob = registerAndGetInfo(service, "Bob");
        service.sendFriendRequest(alice.sessionToken(), bob.accountId());
        service.respondToRequest(bob.sessionToken(), alice.accountId(), true);

        service.setHosting(bob.accountId(), "ABCDEF");

        var aliceFriends = service.listFriends(alice.sessionToken());
        assertEquals(1, aliceFriends.size());
        assertEquals(AccountProtocol.STATUS_HOSTING, aliceFriends.get(0).status());
        assertEquals("ABCDEF", aliceFriends.get(0).roomCode());
    }

    @Test
    void listFriendsShowsOfflineByDefault(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo alice = registerAndGetInfo(service, "Alice");
        AccountService.AuthOkInfo bob = registerAndGetInfo(service, "Bob");
        service.sendFriendRequest(alice.sessionToken(), bob.accountId());
        service.respondToRequest(bob.sessionToken(), alice.accountId(), true);

        var aliceFriends = service.listFriends(alice.sessionToken());
        assertEquals(AccountProtocol.STATUS_OFFLINE, aliceFriends.get(0).status());
    }

    @Test
    void heartbeatMakesFriendShowAsOnline(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo alice = registerAndGetInfo(service, "Alice");
        AccountService.AuthOkInfo bob = registerAndGetInfo(service, "Bob");
        service.sendFriendRequest(alice.sessionToken(), bob.accountId());
        service.respondToRequest(bob.sessionToken(), alice.accountId(), true);

        service.heartbeat(bob.sessionToken());

        assertEquals(AccountProtocol.STATUS_ONLINE, service.listFriends(alice.sessionToken()).get(0).status());
    }

    @Test
    void stopPresenceMakesFriendShowAsOfflineImmediately(@TempDir Path tempDir) throws UnknownHostException {
        AccountService service = newService(tempDir.resolve("a.json"), new AtomicLong(0));
        AccountService.AuthOkInfo alice = registerAndGetInfo(service, "Alice");
        AccountService.AuthOkInfo bob = registerAndGetInfo(service, "Bob");
        service.sendFriendRequest(alice.sessionToken(), bob.accountId());
        service.respondToRequest(bob.sessionToken(), alice.accountId(), true);
        service.setHosting(bob.accountId(), "ABCDEF");

        service.stopPresence(bob.sessionToken());

        assertEquals(AccountProtocol.STATUS_OFFLINE, service.listFriends(alice.sessionToken()).get(0).status());
    }
}
