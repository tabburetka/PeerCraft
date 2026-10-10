package net.peercraft.client.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class AccountWelcomeTest {
    @TempDir Path directory;

    @Test void waitsForAuthenticationAndShowsOnlyOnceAcrossLaunches() {
        Path marker = directory.resolve("config/welcome-seen");
        AccountWelcome.begin(marker, false);
        assertFalse(AccountWelcome.claim(false));
        assertFalse(Files.exists(marker));
        AccountWelcome.authenticationFinished();
        assertTrue(AccountWelcome.claim(false));
        assertTrue(Files.exists(marker));
        assertFalse(AccountWelcome.claim(false));
        AccountWelcome.begin(marker, false);
        AccountWelcome.authenticationFinished();
        assertFalse(AccountWelcome.claim(false));
    }

    @Test void successfulAutomaticLoginSuppressesPromptEvenAfterLogout() {
        AccountWelcome.begin(directory.resolve("welcome-seen"), false);
        AccountWelcome.authenticationFinished();
        assertFalse(AccountWelcome.claim(true));
        assertFalse(AccountWelcome.claim(false));
    }

    @Test void existingAccountDoesNotReceiveFirstRunPromptWhenReloginFails() {
        AccountWelcome.begin(directory.resolve("welcome-seen"), true);
        AccountWelcome.authenticationFinished();
        assertFalse(AccountWelcome.claim(false));
    }

    @Test void markerWriteFailureDoesNotCrashOrRepeatWithinLaunch() throws Exception {
        Path blocker = directory.resolve("file");
        Files.write(blocker, new byte[0]);
        AccountWelcome.begin(blocker.resolve("welcome-seen"), false);
        AccountWelcome.authenticationFinished();
        assertTrue(AccountWelcome.claim(false));
        assertFalse(AccountWelcome.claim(false));
    }
}
