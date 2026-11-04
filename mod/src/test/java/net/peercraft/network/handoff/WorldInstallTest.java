package net.peercraft.network.handoff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.Properties;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldInstallTest {
    @TempDir Path root;
    Path world(String name, String content) throws IOException {
        Path p = Files.createDirectory(root.resolve(name)); Files.write(p.resolve("level.dat"), content.getBytes()); return p;
    }
    @Test void replacementRetainsExplicitBackup() throws Exception {
        Path target = world("world", "old"), staging = world("staging", "new"), backup = root.resolve("backup");
        WorldInstall.replace(staging, target, backup, root.resolve("journal"), true, true, () -> { });
        assertEquals("new", Files.readString(target.resolve("level.dat")));
        assertEquals("old", Files.readString(backup.resolve("level.dat"))); assertTrue(Files.isRegularFile(backup.resolve(".peercraft-backup"))); assertFalse(Files.exists(root.resolve("journal")));
    }
    @Test void restartBetweenMovesCompletesPlacement() throws Exception {
        Path target = world("world", "old"), staging = world("staging", "new"), backup = root.resolve("backup");
        Properties p = new Properties(); p.setProperty("target", "world"); p.setProperty("staging", "staging");
        p.setProperty("backup", "backup"); p.setProperty("lock", "filelock"); p.setProperty("keep", "false"); p.setProperty("installation", "test");
        Files.write(staging.resolve(".peercraft-handoff-install"), "test".getBytes());
        Path journal = root.resolve("journal"); try (OutputStream out = Files.newOutputStream(journal)) { p.store(out, ""); }
        Files.move(target, backup); WorldInstall.recover(root, journal);
        assertEquals("new", Files.readString(target.resolve("level.dat"))); assertFalse(Files.exists(backup));
    }
    @Test void noBackupStillPreservesOldWorldIfPlacementCannotStart() throws Exception {
        Path target = world("world", "old"), backup = world("backup", "other");
        assertThrows(IOException.class, () -> WorldInstall.replace(world("staging", "new"), target, backup, root.resolve("journal"), false, true, () -> { }));
        assertEquals("old", Files.readString(target.resolve("level.dat")));
    }
    Path zip(String entry, byte[] data) throws IOException {
        Path zip = root.resolve("world.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry(entry)); out.write(data); out.closeEntry();
        } return zip;
    }
    @Test void rejectsZipSlipAndRemovesOnlyItsStaging() throws Exception {
        Path original = world("original", "keep"); Path staging = root.resolve("staging");
        Path archive = zip("../escaped", new byte[1]);
        assertThrows(IOException.class, () -> WorldInstall.unpack(archive, staging, 100));
        assertFalse(Files.exists(staging)); assertFalse(Files.exists(root.resolve("escaped")));
        assertEquals("keep", Files.readString(original.resolve("level.dat")));
    }
    @Test void refusesExpansionPastQuota() throws Exception {
        Path archive = zip("level.dat", new byte[1000]); Path staging = root.resolve("staging");
        assertThrows(IOException.class, () -> WorldInstall.unpack(archive, staging, 10)); assertFalse(Files.exists(staging));
    }
    @Test void recoveryNeverMistakesRestoredOldCopyForTransferredWorld() throws Exception {
        Path backup = world("backup", "old");
        Properties p = new Properties(); p.setProperty("target", "world"); p.setProperty("staging", "staging");
        p.setProperty("backup", "backup"); p.setProperty("lock", "filelock"); p.setProperty("keep", "false"); p.setProperty("installation", "lost-new-world");
        Path journal = root.resolve("journal"); try (OutputStream out = Files.newOutputStream(journal)) { p.store(out, ""); }
        assertThrows(IOException.class, () -> WorldInstall.recover(root, journal));
        assertEquals("old", Files.readString(root.resolve("world/level.dat")));
        assertThrows(IOException.class, () -> WorldInstall.recover(root, journal));
        assertTrue(Files.exists(journal));
    }


    @Test void aliasedPlacementCannotRenameOrDeleteTheOriginal() throws Exception {
        Path original = world("world", "old");
        assertThrows(IOException.class, () -> WorldInstall.replace(original, original,
                root.resolve("backup"), root.resolve("journal"), false, true, () -> { }));
        assertEquals("old", Files.readString(original.resolve("level.dat")));
        assertFalse(Files.exists(original.resolve(".peercraft-handoff-install")));
    }
    @Test void corruptJournalCannotUseTheTargetAsRollback() throws Exception {
        Path target = world("world", "keep");
        Files.writeString(target.resolve(".peercraft-handoff-install"), "id");
        Properties p = new Properties(); p.setProperty("target", "world"); p.setProperty("staging", "staging");
        p.setProperty("backup", "world"); p.setProperty("lock", "filelock"); p.setProperty("keep", "false"); p.setProperty("installation", "id");
        Path journal = root.resolve("journal"); try (OutputStream out = Files.newOutputStream(journal)) { p.store(out, ""); }
        assertThrows(IOException.class, () -> WorldInstall.recover(root, journal));
        assertEquals("keep", Files.readString(target.resolve("level.dat")));
        assertTrue(Files.exists(journal));
    }
    @Test void interruptionClosesAndRemovesOnlyIncompleteStaging() throws Exception {
        Path original = world("original", "keep"), archive = zip("level.dat", new byte[1000]);
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedIOException.class, () -> WorldInstall.unpack(archive, root.resolve("staging"), 2000)); }
        finally { Thread.interrupted(); }
        assertFalse(Files.exists(root.resolve("staging")));
        assertEquals("keep", Files.readString(original.resolve("level.dat")));
    }
    @Test void anotherMinecraftProcessKeepsWorldUntouchedDuringReplacement() throws Exception {
        Path target = world("world", "old"), staging = world("staging", "new");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", WorldLockProcess.class.getProtectionDomain().getCodeSource().getLocation().getPath(),
                WorldLockProcess.class.getName(), target.resolve("session.lock").toString()).redirectErrorStream(true).start();
        try {
            assertEquals("LOCKED", new BufferedReader(new InputStreamReader(child.getInputStream())).readLine());
            assertThrows(IOException.class, () -> WorldInstall.replace(staging, target, root.resolve("backup"), root.resolve("journal"), false, true, () -> {}));
            assertEquals("old", Files.readString(target.resolve("level.dat"))); assertTrue(Files.exists(staging));
            assertFalse(Files.exists(root.resolve("journal")));
        } finally { child.getOutputStream().close(); if (!child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly(); }
    }
    @Test void timestampLockWorldNeverReplacedWithoutProofOfExclusivity() throws Exception {
        Path target = world("world", "old"), staging = world("staging", "new");
        assertThrows(IOException.class, () -> WorldInstall.replace(staging, target, root.resolve("backup"), root.resolve("journal"), false));
        assertEquals("old", Files.readString(target.resolve("level.dat"))); assertTrue(Files.exists(staging));
    }
    @Test void invalidBackupMarkerRetainsJournalAndBothWorldsForRecovery() throws Exception {
        Path target = world("world", "old"), staging = world("staging", "new");
        Files.writeString(target.resolve(".peercraft-backup"), "invalid"); Path journal = root.resolve("journal");
        assertThrows(IOException.class, () -> WorldInstall.replace(staging, target, root.resolve("backup"), journal, true, true, () -> {}));
        assertTrue(Files.exists(journal)); assertEquals("new", Files.readString(target.resolve("level.dat")));
        assertEquals("old", Files.readString(root.resolve("backup/level.dat")));
        Files.write(root.resolve("backup/.peercraft-backup"), new byte[0]); WorldInstall.recover(root, journal);
        assertFalse(Files.exists(journal));
    }
}
