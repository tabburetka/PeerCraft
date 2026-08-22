package net.peercraft.client.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccountStorageTest {

    @Test
    void savedStateRoundTripsThroughLoad(@TempDir Path tempDir) {
        Path file = tempDir.resolve("nested").resolve("account.json");
        AccountState state = new AccountState(UUID.randomUUID(), true, "ABCDEF", "Steve", new byte[]{1, 2, 3, 4});

        AccountStorage.save(file, state);
        Optional<AccountState> loaded = AccountStorage.load(file);

        assertTrue(loaded.isPresent());
        assertEquals(state.accountId(), loaded.get().accountId());
        assertTrue(loaded.get().licensed());
        assertEquals("ABCDEF", loaded.get().friendCode());
        assertEquals("Steve", loaded.get().displayName());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, loaded.get().rememberToken());
    }

    @Test
    void loadReturnsEmptyWhenFileDoesNotExist(@TempDir Path tempDir) {
        assertTrue(AccountStorage.load(tempDir.resolve("nope.json")).isEmpty());
    }

    @Test
    void loadToleratesCorruptFileInsteadOfThrowing(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("account.json");
        Files.writeString(file, "{ this is not valid json ][");

        assertDoesNotThrow(() -> assertTrue(AccountStorage.load(file).isEmpty()));
    }

    @Test
    void saveCreatesParentDirectoriesAsNeeded(@TempDir Path tempDir) {
        Path file = tempDir.resolve("a").resolve("b").resolve("c").resolve("account.json");
        AccountState state = new AccountState(UUID.randomUUID(), false, "ZZZZZZ", "Pirate1", new byte[16]);

        assertDoesNotThrow(() -> AccountStorage.save(file, state));

        assertTrue(Files.exists(file));
    }

    @Test
    void clearDeletesTheFile(@TempDir Path tempDir) {
        Path file = tempDir.resolve("account.json");
        AccountStorage.save(file, new AccountState(UUID.randomUUID(), false, "ZZZZZZ", "Pirate1", new byte[16]));
        assertTrue(Files.exists(file));

        AccountStorage.clear(file);

        assertFalse(Files.exists(file));
    }

    @Test
    void clearOnMissingFileDoesNotThrow(@TempDir Path tempDir) {
        assertDoesNotThrow(() -> AccountStorage.clear(tempDir.resolve("nope.json")));
    }

    @Test
    void saveOverwritesPreviousState(@TempDir Path tempDir) {
        Path file = tempDir.resolve("account.json");
        UUID accountId = UUID.randomUUID();
        AccountStorage.save(file, new AccountState(accountId, false, "AAAAAA", "OldName", new byte[16]));
        AccountStorage.save(file, new AccountState(accountId, false, "AAAAAA", "NewName", new byte[16]));

        Optional<AccountState> loaded = AccountStorage.load(file);

        assertTrue(loaded.isPresent());
        assertEquals("NewName", loaded.get().displayName());
    }
}
