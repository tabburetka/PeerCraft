package net.peercraft.rendezvous.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AccountStoreRecoveryTest {
    @TempDir Path directory;
    @Test void corruptDatabaseStopsStartupAndPreservesOriginalBytes() throws Exception {
        for (String content : new String[]{"null", "[{}]", "[{", "[] trailing garbage"}) {
            Path file = directory.resolve("accounts.json"); Files.writeString(file, content);
            assertThrows(IllegalStateException.class, () -> new AccountStore(file));
            assertEquals(content, Files.readString(file));
        }
    }
    @Test void acknowledgedRegistrationSurvivesImmediateRestartBeforePeriodicSave() throws Exception {
        Path file = directory.resolve("accounts.json");
        var service = new AccountService(file, true, System::currentTimeMillis);
        var user = ((AccountService.Result.Ok<AccountService.AuthOkInfo>) service.register("Player", new byte[16], new byte[32],
                java.net.InetAddress.getLoopbackAddress())).value();
        var account = new AccountStore(file).byId(user.accountId()).orElseThrow();
        assertEquals(user.friendCode(), account.friendCode); assertArrayEquals(new byte[32], account.passwordHash);
    }
    @Test void failedRegistrationDoesNotIssueAnUnstoredIdentity() throws Exception {
        Path parent = directory.resolve("blocked");
        var store = new AccountStore(parent.resolve("accounts.json"));
        Files.writeString(parent, "keep");
        var service = new AccountService(store, new SessionRegistry(System::currentTimeMillis), new PendingAuthRegistry(System::currentTimeMillis),
                new FakeMojangVerifier(), System::currentTimeMillis);
        var result = service.register("Player", new byte[16], new byte[32], java.net.InetAddress.getLoopbackAddress());
        assertInstanceOf(AccountService.Result.Fail.class, result); assertEquals(0, store.accountCount());
        assertEquals("keep", Files.readString(parent));
    }
    @Test void duplicateRecoveryEmailsCannotSelectAnArbitraryAccount() throws Exception {
        AccountStore store = new AccountStore(directory.resolve("accounts.json"));
        Account first = new Account(UUID.randomUUID(), "First", false, "AAAAAA"); first.verifiedEmail = "player@example.org";
        Account second = new Account(UUID.randomUUID(), "Second", false, "BBBBBB"); second.verifiedEmail = "PLAYER@example.org";
        store.add(first); store.add(second); store.saveNow();
        assertThrows(IllegalStateException.class, () -> new AccountStore(directory.resolve("accounts.json")));
    }
}
