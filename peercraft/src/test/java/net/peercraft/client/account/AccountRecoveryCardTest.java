package net.peercraft.client.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AccountRecoveryCardTest {
    @Test
    void backupSurvivesLogoutAndContainsNoAuthenticationTokens(@TempDir Path directory) throws Exception {
        UUID id = UUID.randomUUID();
        AccountState state = new AccountState(id, false, "ABC123", "Name", new byte[] {11, 22, 33, 44});
        Path session = directory.resolve("account.json");
        AccountStorage.save(session, state);
        Path backup = AccountRecoveryCard.save(directory.resolve("account-backups"), state);
        AccountStorage.clear(session);
        String text = Files.readString(backup);
        assertFalse(Files.exists(session));
        assertTrue(text.contains(id.toString()));
        assertTrue(text.contains("ABC123"));
        assertFalse(text.contains("rememberToken"));
        assertFalse(text.contains("11,22,33,44"));
        assertFalse(text.contains("sessionToken"));
        assertTrue(text.contains("existing password"));
    }

    @Test
    void switchingAccountsPreservesBothCards(@TempDir Path directory) throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        Path one = AccountRecoveryCard.save(directory, new AccountState(first, false, "ABC123", "A", new byte[16]));
        Path two = AccountRecoveryCard.save(directory, new AccountState(second, true, "DEF456", "B", new byte[16]));
        assertNotEquals(one, two);
        assertTrue(Files.readString(one).contains(first.toString()));
        assertTrue(Files.readString(two).contains("same Mojang account"));
        try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
    }
}
