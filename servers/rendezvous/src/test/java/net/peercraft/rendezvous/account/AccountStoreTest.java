package net.peercraft.rendezvous.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AccountStoreTest {

    @Test
    void addedAccountIsRetrievableByIdAndByFriendCode(@TempDir Path tempDir) {
        AccountStore store = new AccountStore(tempDir.resolve("accounts.json"));
        Account account = new Account(UUID.randomUUID(), "Steve", false, "ABCDEF");

        store.add(account);

        assertTrue(store.byId(account.accountId).isPresent());
        assertTrue(store.byFriendCode("ABCDEF").isPresent());
        assertEquals("Steve", store.byFriendCode("ABCDEF").get().displayName);
    }

    @Test
    void friendCodeTakenReflectsExistingAccounts() {
        AccountStore store = new AccountStore(Path.of(System.getProperty("java.io.tmpdir"), "peercraft-test-" + UUID.randomUUID() + ".json"));
        assertFalse(store.friendCodeTaken("ABCDEF"));

        store.add(new Account(UUID.randomUUID(), "Steve", false, "ABCDEF"));

        assertTrue(store.friendCodeTaken("ABCDEF"));
    }

    @Test
    void survivesServerRestartByReloadingFromDisk(@TempDir Path tempDir) {
        // The whole point of persistence: a second AccountStore pointed at the same file
        // (simulating a server restart) must see accounts added by the first one, once saved.
        Path dataFile = tempDir.resolve("accounts.json");
        UUID accountId = UUID.randomUUID();

        AccountStore first = new AccountStore(dataFile);
        Account account = new Account(accountId, "Steve", true, "ZZZZZZ");
        account.passwordSalt = new byte[]{1, 2, 3};
        account.passwordHash = new byte[]{4, 5, 6};
        account.rememberToken = new byte[]{7, 8, 9};
        first.add(account);
        first.saveIfDirty();

        AccountStore second = new AccountStore(dataFile);

        assertTrue(second.byId(accountId).isPresent());
        Account reloaded = second.byId(accountId).get();
        assertEquals("Steve", reloaded.displayName);
        assertTrue(reloaded.licensed);
        assertEquals("ZZZZZZ", reloaded.friendCode);
        assertArrayEquals(new byte[]{1, 2, 3}, reloaded.passwordSalt);
        assertArrayEquals(new byte[]{4, 5, 6}, reloaded.passwordHash);
        assertArrayEquals(new byte[]{7, 8, 9}, reloaded.rememberToken);
    }

    @Test
    void saveIfDirtyIsANoOpWhenNothingChanged(@TempDir Path tempDir) {
        Path dataFile = tempDir.resolve("accounts.json");
        AccountStore store = new AccountStore(dataFile);

        store.saveIfDirty(); // nothing added yet — must not throw or create a file with garbage

        assertFalse(java.nio.file.Files.exists(dataFile));
    }

    @Test
    void toleratesMissingDataFileOnFirstBoot(@TempDir Path tempDir) {
        Path neverCreated = tempDir.resolve("does-not-exist").resolve("accounts.json");

        assertDoesNotThrow(() -> new AccountStore(neverCreated));
    }

    @Test
    void friendsSetSurvivesReloadAsAConcurrencySafeCollection(@TempDir Path tempDir) throws InterruptedException {
        // Regression test: Gson deserializes Account via Unsafe field injection (no no-arg
        // constructor), which bypasses `= ConcurrentHashMap.newKeySet()` field initializers
        // entirely and hands back its own (non-concurrent) Set implementation instead. Without
        // Account.normalizeCollectionsAfterDeserialization() (called from AccountStore.load()),
        // a reloaded account's `friends` set would throw ConcurrentModificationException the
        // moment the background save-sweeper thread serializes it while the main packet-handling
        // thread concurrently adds/removes a friend — silently killing all future persistence
        // (see AccountStore.save()'s RuntimeException handling).
        Path dataFile = tempDir.resolve("accounts.json");
        UUID accountId = UUID.randomUUID();
        Account account = new Account(accountId, "Steve", false, "AAAAAA");
        account.friends.add(UUID.randomUUID());
        AccountStore first = new AccountStore(dataFile);
        first.add(account);
        first.saveIfDirty();

        AccountStore second = new AccountStore(dataFile);
        Account reloaded = second.byId(accountId).get();

        AtomicBoolean sawConcurrentModification = new AtomicBoolean(false);
        Thread mutator = new Thread(() -> {
            for (int i = 0; i < 5000; i++) {
                reloaded.friends.add(UUID.randomUUID());
            }
        });
        Thread iterator = new Thread(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    for (UUID ignored : reloaded.friends) {
                        // just iterate, like Gson would while serializing
                    }
                }
            } catch (ConcurrentModificationException e) {
                sawConcurrentModification.set(true);
            }
        });
        mutator.start();
        iterator.start();
        mutator.join();
        iterator.join();

        assertFalse(sawConcurrentModification.get(), "friends set must remain concurrency-safe after reload from disk");
    }
}
