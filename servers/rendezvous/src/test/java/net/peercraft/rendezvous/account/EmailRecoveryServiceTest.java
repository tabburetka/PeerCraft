package net.peercraft.rendezvous.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class EmailRecoveryServiceTest {
    @TempDir Path directory;
    private final AtomicLong clock = new AtomicLong(1000);
    private final List<EmailRecoveryService.Mail> mail = new ArrayList<>();
    private AccountService accounts;
    private EmailRecoveryService recovery;
    private AccountService.AuthOkInfo user;
    private byte[] hash = new byte[32];
    private void setup() throws Exception {
        hash[0] = 42;
        accounts = new AccountService(new AccountStore(directory.resolve("accounts.json")),
                new SessionRegistry(clock::get), new PendingAuthRegistry(clock::get),
                new FakeMojangVerifier(), clock::get);
        recovery = accounts.emailRecovery(letter -> { mail.add(letter); return true; }, clock::get);
        user = ((AccountService.Result.Ok<AccountService.AuthOkInfo>) accounts.register("Player", new byte[16], hash,
                InetAddress.getLoopbackAddress())).value();
    }
    private String code() { return mail.getLast().body().split("\n")[0].substring("Code / Код: ".length()); }
    private void bind() {
        var challenge = recovery.beginBinding(user.sessionToken(), hash, "PLAYER@Example.org", "127.0.0.1");
        recovery.confirmBinding(user.sessionToken(), challenge.requestId(), code());
    }
    private void failure(String expected, Runnable action) {
        assertEquals(expected, assertThrows(EmailRecoveryService.Failure.class, action::run).code);
    }
    @Test void bindRequiresCurrentPasswordAndMailboxProofAndPersists() throws Exception {
        setup();
        failure("bad_credentials", () -> recovery.beginBinding(user.sessionToken(), new byte[32], "player@example.org", "ip"));
        var challenge = recovery.beginBinding(user.sessionToken(), hash, "PLAYER@Example.org", "ip");
        assertNull(recovery.verifiedEmail(user.sessionToken()));
        String code = code();
        failure("invalid_code", () -> recovery.confirmBinding(user.sessionToken(), challenge.requestId(), "wrong"));
        recovery.confirmBinding(user.sessionToken(), challenge.requestId(), code);
        assertEquals("player@example.org", recovery.verifiedEmail(user.sessionToken()));
        assertEquals("player@example.org", new AccountStore(directory.resolve("accounts.json")).byId(user.accountId()).orElseThrow().verifiedEmail);
        failure("invalid_code", () -> recovery.confirmBinding(user.sessionToken(), challenge.requestId(), code));
    }
    @Test void resetPreservesUuidFriendsAndRevokesAllOldAccessAndPendingProofs() throws Exception {
        setup(); bind();
        var oldChallenge = ((AccountService.Result.Ok<AccountService.LoginChallengeInfo>) accounts.beginPasswordLogin(false,
                user.accountId(), "", InetAddress.getLoopbackAddress())).value();
        byte[] oldProof = PasswordHasher.hmacChallenge(hash, oldChallenge.challenge());
        var challenge = recovery.beginReset("player@example.org", "other-ip");
        String resetCode = code(); byte[] newHash = new byte[32]; newHash[0] = 99;
        byte[] newSalt = new byte[16]; newSalt[0] = 7;
        recovery.finishReset(challenge.requestId(), resetCode, newSalt, newHash);
        assertTrue(accounts.resolveSession(user.sessionToken()).isEmpty());
        assertInstanceOf(AccountService.Result.Fail.class, accounts.loginRemembered(user.accountId(), user.rememberToken()));
        assertInstanceOf(AccountService.Result.Fail.class, accounts.completePasswordLogin(oldChallenge.requestId(), oldProof));
        var login = ((AccountService.Result.Ok<AccountService.LoginChallengeInfo>) accounts.beginPasswordLogin(false,
                user.accountId(), "", InetAddress.getLoopbackAddress())).value();
        var result = ((AccountService.Result.Ok<AccountService.AuthOkInfo>) accounts.completePasswordLogin(login.requestId(),
                PasswordHasher.hmacChallenge(newHash, login.challenge()))).value();
        assertEquals(user.accountId(), result.accountId()); assertEquals(user.friendCode(), result.friendCode());
        Account disk = new AccountStore(directory.resolve("accounts.json")).byId(user.accountId()).orElseThrow();
        assertArrayEquals(newSalt, disk.passwordSalt); assertArrayEquals(newHash, disk.passwordHash);
        assertNull(disk.rememberToken); // no new session is automatically persisted by reset
        failure("invalid_code", () -> recovery.finishReset(challenge.requestId(), resetCode, newSalt, newHash));
    }
    @Test void unknownAddressHasSamePublicResponseButCannotReset() throws Exception {
        setup(); bind();
        var known = recovery.beginReset("player@example.org", "ip-a"); int letters = mail.size();
        var unknown = recovery.beginReset("unknown@example.org", "ip-b");
        assertEquals(known.expiresInSeconds(), unknown.expiresInSeconds());
        assertEquals(known.requestId().length(), unknown.requestId().length()); assertEquals(letters, mail.size());
        failure("invalid_code", () -> recovery.finishReset(unknown.requestId(), code(), new byte[16], new byte[32]));
    }
    @Test void expiresAndLimitsGuesses() throws Exception {
        setup(); bind();
        var challenge = recovery.beginReset("player@example.org", "ip"); String code = code();
        for (int i = 0; i < 5; i++) failure("invalid_code", () -> recovery.finishReset(challenge.requestId(), "wrong", new byte[16], hash));
        failure("invalid_code", () -> recovery.finishReset(challenge.requestId(), code, new byte[16], hash));
        var next = recovery.beginReset("player@example.org", "ip"); String nextCode = code();
        clock.addAndGet(EmailRecoveryService.TTL);
        failure("invalid_code", () -> recovery.finishReset(next.requestId(), nextCode, new byte[16], hash));
    }
    @Test void addressAndIpThrottlesAlsoApplyToUnknownAccounts() throws Exception {
        setup();
        for (int i = 0; i < 3; i++) recovery.beginReset("unknown@example.org", "ip-" + i);
        failure("rate_limited", () -> recovery.beginReset("unknown@example.org", "ip-x"));
        clock.addAndGet(EmailRecoveryService.TTL); recovery.beginReset("unknown@example.org", "ip-x");
        for (int i = 0; i < 5; i++) recovery.beginReset("unknown" + i + "@example.org", "single-ip");
        failure("rate_limited", () -> recovery.beginReset("another@example.org", "single-ip"));
    }
    @Test void oneEmailCannotBeAssignedToTwoAccounts() throws Exception {
        setup(); bind();
        var other = ((AccountService.Result.Ok<AccountService.AuthOkInfo>) accounts.register("Other", new byte[16], hash,
                InetAddress.getLoopbackAddress())).value();
        failure("email_unavailable", () -> recovery.beginBinding(other.sessionToken(), hash, "player@example.org", "ip"));
    }
    @Test void confirmationBoundToAccountAndPurpose() throws Exception {
        setup();
        var bind = recovery.beginBinding(user.sessionToken(), hash, "player@example.org", "ip"); String bindCode = code();
        var other = ((AccountService.Result.Ok<AccountService.AuthOkInfo>) accounts.register("Other", new byte[16], hash,
                InetAddress.getLoopbackAddress())).value();
        failure("invalid_code", () -> recovery.confirmBinding(other.sessionToken(), bind.requestId(), bindCode));
        failure("invalid_code", () -> recovery.finishReset(bind.requestId(), bindCode, new byte[16], hash));
        recovery.confirmBinding(user.sessionToken(), bind.requestId(), bindCode);
    }
    @Test void storageFailureCannotChangeCredentialsOrReportSuccess() throws Exception {
        setup(); bind();
        var challenge = recovery.beginReset("player@example.org", "ip"); String code = code();
        Files.delete(directory.resolve("accounts.json")); Files.createDirectory(directory.resolve("accounts.json"));
        Files.writeString(directory.resolve("accounts.json/keep"), "block replacement");
        failure("storage_unavailable", () -> recovery.finishReset(challenge.requestId(), code, new byte[16], new byte[32]));
        assertTrue(accounts.resolveSession(user.sessionToken()).isPresent());
        assertInstanceOf(AccountService.Result.Ok.class, accounts.loginRemembered(user.accountId(), user.rememberToken()));
    }
    @Test void refusesHeaderInjectionAndOversizedAddresses() throws Exception {
        setup();
        for (String address : List.of("player@example.org\r\nBcc: attacker@example.org", "x".repeat(255) + "@example.org", "invalid"))
            failure("invalid_email", () -> recovery.beginReset(address, "ip"));
    }
    @Test void mailQueueFailureHasSameResponseAndNoWorkingChallenge() throws Exception {
        setup(); bind();
        // A new service test seam emulates an unavailable provider without replacing production singleton.
        AccountStore disk = new AccountStore(directory.resolve("accounts.json"));
        var unavailable = new EmailRecoveryService(accounts, disk, new SessionRegistry(clock::get),
                new PendingAuthRegistry(clock::get), letter -> false, clock::get);
        var challenge = unavailable.beginReset("player@example.org", "ip");
        assertEquals(900, challenge.expiresInSeconds());
        failure("invalid_code", () -> unavailable.finishReset(challenge.requestId(), "00000000", new byte[16], hash));
    }

    @Test void changingMailboxInvalidatesOldResetWithoutChangingIdentity() throws Exception {
        setup(); bind();
        var oldReset = recovery.beginReset("player@example.org", "reset-ip");
        String oldCode = code();
        var replacement = recovery.beginBinding(user.sessionToken(), hash, "new@example.org", "bind-ip");
        String replacementCode = code();
        recovery.confirmBinding(user.sessionToken(), replacement.requestId(), replacementCode);
        failure("invalid_code", () -> recovery.finishReset(oldReset.requestId(), oldCode, new byte[16], hash));
        assertTrue(accounts.resolveSession(user.sessionToken()).isPresent());
        AccountStore restarted = new AccountStore(directory.resolve("accounts.json"));
        assertEquals(1, restarted.accountCount());
        Account persisted = restarted.byId(user.accountId()).orElseThrow();
        assertEquals(user.friendCode(), persisted.friendCode);
        assertEquals("new@example.org", persisted.verifiedEmail);
        assertArrayEquals(hash, persisted.passwordHash);
        int letters = mail.size();
        recovery.beginReset("player@example.org", "old-address-ip");
        assertEquals(letters, mail.size(), "Former mailbox must not receive new recovery codes");
        assertTrue(mail.stream().anyMatch(letter -> letter.recipient().equals("player@example.org")
                && letter.subject().equals("PeerCraft: recovery email changed")));
    }
}
