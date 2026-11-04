package net.peercraft.client.modsync;

import net.peercraft.modsync.install.ModSyncInstaller;
import net.peercraft.network.modsync.ModSyncPlan;
import net.peercraft.platform.Services;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Starts the bundled helper while the game still owns a JVM-lifetime lock. */
public final class ModSyncPendingInstaller {
    private static final Logger LOGGER = LogManager.getLogger("peercraft");
    // Strong references are intentional: never close these from a shutdown hook.
    private static FileChannel gameChannel;
    private static FileLock gameLock;
    private static boolean initialized;
    private static String startupIssue = "";
    private static Path pending;
    private static Process helper;
    private ModSyncPendingInstaller() { }
    public static Path operations() { return Services.PLATFORM.getConfigDir().resolve("peercraft/modsync/operations"); }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        try {
            Files.createDirectories(operations());
            gameChannel = FileChannel.open(operations().resolve("game.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            gameLock = gameChannel.tryLock();
            if (gameLock == null) throw new IOException("Another game or installer is using this instance. Close Minecraft and wait for Mod Sync.");
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(operations())) {
                for (Path dir : dirs) if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(dir.resolve(ModSyncInstaller.PLAN))) {
                    Properties p = ModSyncInstaller.read(dir.resolve(ModSyncInstaller.PLAN));
                    String state = p.getProperty("state", "");
                    if (Arrays.asList("APPLIED", "FAILED", "ROLLED_BACK", "CANCELLED").contains(state) && !"true".equals(p.getProperty("historyRecorded"))) recordHistory(dir, p);
                    else if ("ARMED".equals(state) || "APPLYING".equals(state) || "RECOVERY_REQUIRED".equals(state)) {
                        startupIssue = dir.toString();
                        LOGGER.warn("[ModSync] Unfinished transaction; close the game and inspect {}", dir);
                        // Resume only an already-authorized operation; APPLYING recovers by rollback.
                        launch(dir, p);
                    }
                }
            }
        } catch (Exception e) { startupIssue = e.toString(); LOGGER.warn("[ModSync] Installer unavailable: {}", e.toString()); }
    }
    public static String startupIssue() { initialize(); return startupIssue; }
    public static boolean hasPending() { initialize(); return pending != null || !startupIssue.isEmpty(); }

    public static void installNow(ModSyncPlan plan, List<Path> files, String room, Map<String, String> inventory) throws IOException {
        initialize();
        if (hasPending() || gameLock == null) throw new IOException("Another Mod Sync transaction is pending");
        List<ModSyncInstaller.Entry> entries = new ArrayList<ModSyncInstaller.Entry>();
        if (!plan.removals().isEmpty()) throw new IOException("Removal requires closing Minecraft");
        for (ModSyncPlan.PlannedMod pm : plan.mods()) {
            if (pm.replacement() != null) throw new IOException("Replacement requires closing Minecraft");
            entries.add(new ModSyncInstaller.Entry(pm.entry().id(), pm.entry().version(), pm.entry().fileName(), pm.entry().sha512Hex(), "", "", "", pm.source().name().toLowerCase(Locale.ROOT)));
        }
        Path dir = ModSyncInstaller.prepare(operations(), Services.PLATFORM.getModsDir(), entries, files, room, Locale.getDefault().getLanguage(), inventory);
        ModSyncInstaller.apply(dir);
        try { recordHistory(dir, ModSyncInstaller.read(dir.resolve(ModSyncInstaller.PLAN))); }
        catch (IOException e) { LOGGER.warn("[ModSync] Installation applied; history will be retried at next startup: {}", e.toString()); }
    }

    public static Path arm(ModSyncPlan plan, List<Path> files, String room, Map<String, String> inventory, BooleanSupplier cancelled) throws IOException {
        initialize();
        if (hasPending() || gameLock == null) throw new IOException("Pending Mod Sync operation: " + startupIssue);
        List<ModSyncInstaller.Entry> entries = new ArrayList<ModSyncInstaller.Entry>();
        for (ModSyncPlan.PlannedMod pm : plan.mods()) {
            ModSyncPlan.Replacement r = pm.replacement();
            entries.add(new ModSyncInstaller.Entry(pm.entry().id(), pm.entry().version(), pm.entry().fileName(), pm.entry().sha512Hex(),
                    r == null ? "" : r.fileName(), r == null ? "" : r.version(), r == null ? "" : r.sha512(), pm.source().name().toLowerCase(Locale.ROOT)));
        }
        for (ModSyncPlan.Removal r : plan.removals()) entries.add(ModSyncInstaller.Entry.removal(r.id(), r.fileName(), r.version(), r.sha512()));
        Path dir = ModSyncInstaller.prepare(operations(), Services.PLATFORM.getModsDir(), entries, files, room, Locale.getDefault().getLanguage(), inventory);
        Properties p = ModSyncInstaller.read(dir.resolve(ModSyncInstaller.PLAN));
        synchronized (ModSyncPendingInstaller.class) {
            if (pending != null || cancelled.getAsBoolean()) throw new IOException("Cancelled or another operation was prepared");
            pending = dir;
        }
        try {
            launch(dir, p);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!Files.isRegularFile(dir.resolve("ready.properties"))) {
                if (cancelled.getAsBoolean() || !helper.isAlive() || System.nanoTime() > deadline) throw new IOException("Helper did not become ready");
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
            if (!p.getProperty("nonce").equals(ModSyncInstaller.read(dir.resolve("ready.properties")).getProperty("nonce"))) throw new IOException("Helper readiness mismatch");
            synchronized (ModSyncPendingInstaller.class) {
                if (cancelled.getAsBoolean() || pending == null) throw new IOException("Cancelled");
                p.setProperty("state", "ARMED"); ModSyncInstaller.save(dir.resolve(ModSyncInstaller.PLAN), p);
            }
            return dir;
        } catch (IOException e) { cancelPending(); throw e; }
    }
    private static void launch(Path dir, Properties p) throws IOException {
        String nonce = UUID.randomUUID().toString(); p.setProperty("nonce", nonce);
        ModSyncInstaller.save(dir.resolve(ModSyncInstaller.PLAN), p);
        Path runtime = dir.resolve("helper.jar");
        if (!Files.exists(runtime)) try (InputStream in = ModSyncPendingInstaller.class.getResourceAsStream("/peercraft/modsync-helper.jar")) {
            if (in == null) throw new IOException("Bundled Mod Sync helper is missing");
            Files.copy(in, runtime);
        }
        String binary = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "java.exe" : "java";
        Path java = Paths.get(System.getProperty("java.home"), "bin", binary);
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-jar", runtime.toString(), dir.toAbsolutePath().toString(), nonce);
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("helper.log").toFile()));
        helper = builder.start();
    }
    public static synchronized void cancelPending() {
        if (pending == null) return;
        try {
            Properties p = ModSyncInstaller.read(pending.resolve(ModSyncInstaller.PLAN));
            p.setProperty("state", "CANCELLED"); ModSyncInstaller.save(pending.resolve(ModSyncInstaller.PLAN), p);
            if (helper != null) helper.destroy();
            pending = null;
        } catch (IOException e) { LOGGER.warn("[ModSync] Could not cancel prepared transaction: {}", e.toString()); }
    }
    private static void recordHistory(Path dir, Properties p) throws IOException {
        ModSyncManifest manifest = ModSyncManifestStore.load();
        ModSyncManifest.Operation operation = new ModSyncManifest.Operation();
        operation.transactionId = dir.getFileName().toString(); operation.result = p.getProperty("state");
        operation.backupDirectory = dir.resolve("backups").toString(); operation.error = p.getProperty("error", "");
        operation.completedAt = Long.parseLong(p.getProperty("completedAt", "0")); manifest.recordOperation(operation);
        int n = "APPLIED".equals(operation.result) ? Integer.parseInt(p.getProperty("count")) : 0;
        for (int i = 0; i < n; i++) {
            String prefix = i + ".";
            boolean removed = p.getProperty(prefix + "file", "").isEmpty();
            ModSyncManifest.Install rec = new ModSyncManifest.Install();
            rec.modId = p.getProperty(prefix + "id"); rec.modVersion = p.getProperty(prefix + "version");
            rec.fileName = p.getProperty(prefix + "file"); rec.sha512 = p.getProperty(prefix + "sha512");
            rec.source = p.getProperty(prefix + "source"); rec.sizeBytes = Long.parseLong(p.getProperty(prefix + "sizeBytes", "0")); rec.installedAt = Long.parseLong(p.getProperty("completedAt", "0"));
            rec.hostRoomCode = p.getProperty("room"); rec.transactionId = dir.getFileName().toString(); rec.result = removed ? "REMOVED" : "APPLIED";
            rec.oldVersion = p.getProperty(prefix + "oldVersion");
            String old = p.getProperty(prefix + "oldFile", "");
            rec.supersededPath = old.isEmpty() ? "" : dir.resolve("backups").resolve(ModSyncInstaller.name(old)).toString();
            manifest.record(rec);
        }
        if (!ModSyncManifestStore.saveChecked(manifest)) throw new IOException("Could not save installation history");
        p.setProperty("historyRecorded", "true"); ModSyncInstaller.save(dir.resolve(ModSyncInstaller.PLAN), p);
    }
}
