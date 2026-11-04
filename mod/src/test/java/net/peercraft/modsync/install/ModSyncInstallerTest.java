package net.peercraft.modsync.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ModSyncInstallerTest {
    @TempDir Path dir;
    Path mods() throws Exception { return Files.createDirectories(dir.resolve("mods")); }
    Path staged(String name, String text) throws Exception { return Files.writeString(dir.resolve(name), text); }
    ModSyncInstaller.Entry entry(String id, Path old, Path next, String file) throws Exception {
        return new ModSyncInstaller.Entry(id, "host", file, ModSyncInstaller.hash(next), old == null ? "" : old.getFileName().toString(), "old",
                old == null ? "" : ModSyncInstaller.hash(old), "p2p");
    }
    Path prepare(List<ModSyncInstaller.Entry> entries, List<Path> files) throws Exception {
        return ModSyncInstaller.prepare(dir.resolve("operations"), mods(), entries, files, "ROOM", "en");
    }
    String state(Path tx) throws Exception { return ModSyncInstaller.read(tx.resolve(ModSyncInstaller.PLAN)).getProperty("state"); }
    @Test void replacesSameFilenameOnlyAfterApplyAndKeepsBackup() throws Exception {
        Path old = Files.writeString(mods().resolve("mod.jar"), "original"); Path next = staged("download", "host");
        Path tx = prepare(List.of(entry("mod", old, next, "mod.jar")), List.of(next));
        assertEquals("original", Files.readString(old));
        ModSyncInstaller.apply(tx);
        assertEquals("host", Files.readString(old));
        assertEquals("original", Files.readString(tx.resolve("backups/mod.jar")));
        assertEquals("APPLIED", state(tx));
    }
    @Test void rejectsChangedOriginalOrStagedFileBeforeTouchingAnyMod() throws Exception {
        Path old = Files.writeString(mods().resolve("old.jar"), "original"); Path next = staged("download", "host");
        Path tx = prepare(List.of(entry("mod", old, next, "new.jar")), List.of(next));
        Files.writeString(old, "user edit");
        assertThrows(java.io.IOException.class, () -> ModSyncInstaller.apply(tx));
        assertEquals("user edit", Files.readString(old)); assertFalse(Files.exists(mods().resolve("new.jar")));
        assertEquals("FAILED", state(tx));
    }
    @Test void rejectsAdditionalJarAppearingAfterConfirmation() throws Exception {
        Path old = Files.writeString(mods().resolve("old.jar"), "original"), next = staged("download", "host");
        Path tx = prepare(List.of(entry("mod", old, next, "new.jar")), List.of(next));
        Files.writeString(mods().resolve("another-version.jar"), "externally added");
        assertThrows(java.io.IOException.class, () -> ModSyncInstaller.apply(tx));
        assertEquals("original", Files.readString(old)); assertEquals("FAILED", state(tx));
        assertFalse(Files.exists(mods().resolve("new.jar")));
    }

    @Test void refusesPartialDownloadAndTargetCollision() throws Exception {
        Path next = staged("download", "host"); ModSyncInstaller.Entry e = entry("mod", null, next, "new.jar");
        Files.writeString(next, "corrupted");
        assertThrows(java.io.IOException.class, () -> prepare(List.of(e), List.of(next)));
        assertEquals(0, Files.list(mods()).count());
        Files.writeString(next, "host"); Files.writeString(mods().resolve("new.jar"), "unrelated");
        assertThrows(java.io.IOException.class, () -> prepare(List.of(e), List.of(next)));
        assertEquals("unrelated", Files.readString(mods().resolve("new.jar")));
    }
    @Test void rollsBackEntireBatchAfterSecondInstallFails() throws Exception {
        Path old = Files.writeString(mods().resolve("old.jar"), "original");
        Path a = staged("a", "first"), b = staged("b", "second");
        Path tx = prepare(List.of(entry("mod", old, a, "new.jar"), entry("extra", null, b, "extra.jar")), List.of(a, b));
        Files.createDirectory(mods().resolve(".peercraft-" + tx.getFileName() + "-1.tmp"));
        assertThrows(java.io.IOException.class, () -> ModSyncInstaller.apply(tx));
        assertEquals("ROLLED_BACK", state(tx)); assertEquals("original", Files.readString(old));
        assertFalse(Files.exists(mods().resolve("new.jar"))); assertFalse(Files.exists(mods().resolve("extra.jar")));
    }
    @Test void interruptedJournalRestoresBackupAndPreservesUnexpectedEdits() throws Exception {
        Path old = Files.writeString(mods().resolve("old.jar"), "original"); Path next = staged("download", "host");
        Path tx = prepare(List.of(entry("mod", old, next, "new.jar")), List.of(next));
        Files.copy(old, tx.resolve("backups/old.jar")); Files.delete(old);
        Properties p = ModSyncInstaller.read(tx.resolve(ModSyncInstaller.PLAN));
        p.setProperty("state", "APPLYING"); p.setProperty("0.remove", "intent"); p.setProperty("0.install", "intent");
        ModSyncInstaller.save(tx.resolve(ModSyncInstaller.PLAN), p);
        Files.writeString(mods().resolve("new.jar"), "unexpected user edit");
        assertThrows(java.io.IOException.class, () -> ModSyncInstaller.apply(tx));
        assertEquals("unexpected user edit", Files.readString(mods().resolve("new.jar")));
        assertEquals("RECOVERY_REQUIRED", state(tx));
        Files.delete(mods().resolve("new.jar")); ModSyncInstaller.apply(tx);
        assertEquals("original", Files.readString(old)); assertEquals("ROLLED_BACK", state(tx));
    }
    ModSyncInstaller.Entry removal(String id, Path old) throws Exception {
        return ModSyncInstaller.Entry.removal(id, old.getFileName().toString(), "old", ModSyncInstaller.hash(old));
    }
    @Test void removesExtraJarAndKeepsBackup() throws Exception {
        Path extra = Files.writeString(mods().resolve("extra.jar"), "client only");
        Path tx = prepare(List.of(removal("extra", extra)), List.of());
        assertEquals("2", ModSyncInstaller.read(tx.resolve(ModSyncInstaller.PLAN)).getProperty("format"));
        assertTrue(Files.exists(extra));
        ModSyncInstaller.apply(tx);
        assertFalse(Files.exists(extra)); assertEquals("client only", Files.readString(tx.resolve("backups/extra.jar")));
        assertEquals("APPLIED", state(tx));
    }
    @Test void mixesInstallsAndRemovals() throws Exception {
        Path extra = Files.writeString(mods().resolve("extra.jar"), "extra"), next = staged("download", "host");
        Path tx = prepare(List.of(removal("extra", extra), entry("mod", null, next, "new.jar")), List.of(next));
        ModSyncInstaller.apply(tx);
        assertFalse(Files.exists(extra)); assertEquals("host", Files.readString(mods().resolve("new.jar")));
    }
    @Test void refusesRemovalOfChangedJar() throws Exception {
        Path extra = Files.writeString(mods().resolve("extra.jar"), "extra");
        Path tx = prepare(List.of(removal("extra", extra)), List.of());
        Files.writeString(extra, "user edit");
        assertThrows(java.io.IOException.class, () -> ModSyncInstaller.apply(tx));
        assertEquals("user edit", Files.readString(extra)); assertEquals("FAILED", state(tx));
    }
    @Test void rollbackRestoresRemovedJar() throws Exception {
        Path extra = Files.writeString(mods().resolve("extra.jar"), "extra"), next = staged("download", "host");
        Path tx = prepare(List.of(removal("extra", extra), entry("mod", null, next, "new.jar")), List.of(next));
        Files.createDirectory(mods().resolve(".peercraft-" + tx.getFileName() + "-1.tmp"));
        assertThrows(java.io.IOException.class, () -> ModSyncInstaller.apply(tx));
        assertEquals("ROLLED_BACK", state(tx)); assertEquals("extra", Files.readString(extra));
        assertFalse(Files.exists(mods().resolve("new.jar")));
    }
    @Test void rejectsRemovalWithoutSourceJar() {
        assertThrows(java.io.IOException.class, () -> prepare(List.of(ModSyncInstaller.Entry.removal("x", "", "", "")), List.of()));
    }
    @Test void helperWaitsForGameLockAndHonorsCancellation() throws Exception { runHelper(true); }
    @Test void helperAppliesAfterGameLockIsReleased() throws Exception { runHelper(false); }
    void runHelper(boolean cancel) throws Exception {
        Path old = Files.writeString(mods().resolve("old.jar"), "original"), next = staged("download", "host");
        Path tx = prepare(List.of(entry("mod", old, next, "new.jar")), List.of(next));
        Properties p = ModSyncInstaller.read(tx.resolve(ModSyncInstaller.PLAN)); p.setProperty("state", "ARMED"); p.setProperty("nonce", "test"); ModSyncInstaller.save(tx.resolve(ModSyncInstaller.PLAN), p);
        Process process;
        try (FileChannel channel = FileChannel.open(dir.resolve("operations/game.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
            String java = Paths.get(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            String configuredJava = System.getenv("PEERCRAFT_TEST_HELPER_JAVA");
            if (configuredJava != null && !configuredJava.isEmpty()) java = configuredJava;
            Path bundled = tx.resolve("bundled-helper.jar");
            try (java.io.InputStream in = ModSyncInstallerTest.class.getResourceAsStream("/peercraft/modsync-helper.jar")) {
                assertNotNull(in); Files.copy(in, bundled);
            }
            process = new ProcessBuilder(java, "-Djava.awt.headless=true", "-jar", bundled.toString(), tx.toString(), "test").redirectErrorStream(true).redirectOutput(tx.resolve("test-helper.log").toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!Files.exists(tx.resolve("ready.properties")) && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue(Files.exists(tx.resolve("ready.properties"))); assertTrue(process.isAlive());
            assertEquals("original", Files.readString(old)); assertFalse(Files.exists(mods().resolve("new.jar")));
            if (cancel) { p.setProperty("state", "CANCELLED"); ModSyncInstaller.save(tx.resolve(ModSyncInstaller.PLAN), p); }
        }
        try {
            assertTrue(process.waitFor(8, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), Files.readString(tx.resolve("test-helper.log")));
            if (cancel) { assertEquals("original", Files.readString(old)); assertEquals("CANCELLED", state(tx)); }
            else { assertEquals("host", Files.readString(mods().resolve("new.jar"))); assertFalse(Files.exists(old)); assertEquals("APPLIED", state(tx)); }
        } finally { process.destroyForcibly(); }
    }
}
