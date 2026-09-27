package net.peercraft.network.handoff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Properties;
import java.io.OutputStream;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldInstallRecoveryTest {
    @TempDir Path root;
    @Test void startupCompletesPlacementBeforeAllowingWorldOpen() throws Exception {
        Path staging = Files.createDirectory(root.resolve(".peercraft-handoff-staging-test"));
        Files.writeString(staging.resolve("level.dat"), "new");
        Files.writeString(staging.resolve(".peercraft-handoff-install"), "id");
        Path backup = Files.createDirectory(root.resolve("backup")); Files.writeString(backup.resolve("level.dat"), "old");
        Properties p = new Properties(); p.setProperty("target", "world"); p.setProperty("staging", staging.getFileName().toString());
        p.setProperty("backup", "backup"); p.setProperty("keep", "true"); p.setProperty("installation", "id");
        try (OutputStream out = Files.newOutputStream(root.resolve(".peercraft-handoff-staging-test.install"))) { p.store(out, ""); }
        CompletableFuture<Void> first = WorldInstallRecovery.start(root);
        assertSame(first, WorldInstallRecovery.start(root.resolve(".")));
        first.get(5, TimeUnit.SECONDS);
        assertEquals("new", Files.readString(root.resolve("world/level.dat")));
        assertEquals("old", Files.readString(backup.resolve("level.dat")));
        assertFalse(WorldInstallRecovery.deferOpen(root, Runnable::run, () -> fail("Unnecessary retry"), failure -> fail(failure)));
    }
    @Test void unknownPlacementKeepsFilesAndReportsFiniteError() throws Exception {
        Path journal = root.resolve(".peercraft-handoff-staging-broken.install"); Files.writeString(journal, "target=world\n");
        assertThrows(ExecutionException.class, () -> WorldInstallRecovery.start(root).get(5, TimeUnit.SECONDS));
        CompletableFuture<Throwable> error = new CompletableFuture<>();
        assertTrue(WorldInstallRecovery.deferOpen(root, Runnable::run, () -> fail("Cannot open unresolved placement"), error::complete));
        assertNotNull(error.get(1, TimeUnit.SECONDS)); assertTrue(Files.exists(journal));
    }
}
