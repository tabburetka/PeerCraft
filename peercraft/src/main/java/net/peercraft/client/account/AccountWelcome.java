package net.peercraft.client.account;

import net.peercraft.platform.Services;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

/** First-run prompt: async authentication settles before the main-menu tick claims it. */
public final class AccountWelcome {
    private static Path marker;
    private static boolean eligible;
    private static volatile boolean authenticationFinished;
    private AccountWelcome() {}

    public static void begin(boolean hasSavedAccount) {
        begin(Services.PLATFORM.getConfigDir().resolve("peercraft").resolve("welcome-seen"), hasSavedAccount);
    }

    static void begin(Path path, boolean hasSavedAccount) {
        marker = path;
        eligible = !hasSavedAccount && !Files.exists(path);
        authenticationFinished = false;
    }

    public static void authenticationFinished() {
        authenticationFinished = true;
    }

    /** Called only on the client thread, only while the main menu is visible. */
    public static boolean claim(boolean loggedIn) {
        if (!eligible || !authenticationFinished) return false;
        eligible = false;
        try {
            Files.createDirectories(marker.getParent());
            Files.write(marker, new byte[0]);
        } catch (IOException | RuntimeException failure) {
            LoggerFactory.getLogger("peercraft").warn("[PeerCraft] Could not save welcome marker: {}", failure.toString());
        }
        return !loggedIn;
    }
}
