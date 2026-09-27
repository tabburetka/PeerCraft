package net.peercraft.client.handoff;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;
import net.peercraft.client.PeerCraftHostOptions;
import net.peercraft.client.gui.HandoffReclaimConfirmScreen;
import net.peercraft.client.gui.PeerCraftUi;
import net.peercraft.network.handoff.HandoffProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minecraft 1.16.5 backport of {@code src/main/.../client/handoff/SuccessorLauncher.java}.
 *
 * <p>Deltas from the modern original, all confirmed against 1.16.5's official-mappings jar:
 * <ul>
 *   <li>No {@code Minecraft.createWorldOpenFlows()}/{@code WorldOpenFlows} (that abstraction is
 *       1.20.2+) — {@code Minecraft.loadLevel(String)} loads a world directly, with no
 *       completion callback; the poll loop below is the only failure signal (same 60s deadline
 *       fallback the modern version already relies on).</li>
 *   <li>{@code IntegratedServer.publishServer(GameType, boolean, int)} — unchanged signature
 *       (see the already-ported {@code OpenToLanMixin}).</li>
 * </ul>
 */
public final class SuccessorLauncher {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    public interface Done {
        void serverPublished();

        void failed(String reasonKey);

        default boolean active() { return true; }
        default net.peercraft.network.handoff.HandoffLaunchContext context() { return null; }
    }

    /** Cancellation prevents queued open/publish actions; the caller must also stop a server already opened. */
    public static final class Launch implements Done {
        private final net.peercraft.network.handoff.HandoffLaunchGuard guard =
                new net.peercraft.network.handoff.HandoffLaunchGuard();
        private final Done callback;
        private volatile net.peercraft.network.handoff.HandoffLaunchContext nativeContext;
        public boolean ownsServer(Object server, Path path) { return nativeContext != null && nativeContext.belongs(server, path); }
        private Launch(Done callback) { this.callback = callback; }
        public boolean active() { return guard.active() && callback.active(); }
        public void cancel() { guard.finish(); }
        public void serverPublished() { if (callback.active() && guard.finish()) callback.serverPublished(); }
        public void failed(String reason) { if (callback.active() && guard.finish()) callback.failed(reason); }
    }

    private SuccessorLauncher() {
    }

    public static Path savesDirectory() { return Minecraft.getInstance().getLevelSource().getBaseDir(); }
    public static final class TargetChoice {
        public final Path directory;
        public final boolean keepBackup;
        public final String backupName;
        public TargetChoice(Path directory, boolean keepBackup) {
            this(directory, keepBackup, WorldTargetPlan.backupName(directory, keepBackup));
        }
        public TargetChoice(Path directory, boolean keepBackup, String backupName) {
            this.directory = directory; this.keepBackup = keepBackup; this.backupName = backupName;
        }
    }
    public interface SelectionUi {
        java.util.concurrent.CompletableFuture<TargetChoice> choose(java.util.List<Path> copies);
    }
    /** Called during PREFLIGHT, before any player is prepared/frozen. */
    public static java.util.concurrent.CompletableFuture<WorldTargetPlan> preflightTarget(HandoffProtocol.Offer offer, SelectionUi ui) {
        java.util.concurrent.CompletableFuture<WorldTargetPlan> result = new java.util.concurrent.CompletableFuture<>();
        background(() -> {
            try {
                Path root = savesDirectory(); Files.createDirectories(root);
                net.peercraft.network.handoff.WorldInstall.recoverAll(root);
                java.util.List<Path> copies = WorldTargetPlan.candidates(root, offer.worldId());
                if (copies.isEmpty() || System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                    WorldTargetPlan plan = WorldTargetPlan.fresh(root, offer.worldLabel(), offer.worldId());
                    plan.requireSpace(offer.estArchiveBytes()); result.complete(plan);
                } else {
                    Minecraft.getInstance().execute(() -> {
                        try {
                            ui.choose(copies).whenComplete((choice, failure) -> {
                                if (failure != null) { result.completeExceptionally(failure); return; }
                                background(() -> {
                                    try {
                                        if (choice == null || !copies.contains(choice.directory)) throw new IOException("Unknown return target");
                                        WorldTargetPlan plan = WorldTargetPlan.selected(root, choice.directory, root.resolve(choice.backupName), choice.keepBackup, offer.worldId());
                                        plan.requireSpace(offer.estArchiveBytes()); result.complete(plan);
                                    } catch (IOException | RuntimeException e) { result.completeExceptionally(e); }
                                });
                            });
                        } catch (RuntimeException e) { result.completeExceptionally(e); }
                    });
                }
            } catch (IOException | RuntimeException e) { result.completeExceptionally(e); }
        });
        return result;
    }
    /** Stage only. The target remains untouched until the operation proves COMMIT. */
    public static Path verifyStaging(HandoffProtocol.Offer offer, Path archive, WorldTargetPlan plan, String attemptId) throws IOException {
        if (!attemptId.matches("[a-zA-Z0-9_-]{1,64}")) throw new IOException("Invalid staging attempt id");
        Path staging = plan.root.resolve(".peercraft-handoff-staging-" + attemptId);
        net.peercraft.network.handoff.WorldInstall.unpack(archive, staging, WorldTargetPlan.UNPACK_LIMIT);
        net.peercraft.network.handoff.SnapshotValidation.validate(staging);
        PeercraftWorldMeta meta = PeercraftWorldMeta.loadOrNull(staging);
        if (meta == null || !offer.worldId().equals(meta.worldId())) throw new IOException("Snapshot belongs to another world");
        return staging;
    }
    public static Launch restoreClosed(Path path, HandoffProtocol.Offer offer, java.util.UUID sessionId, Done callback) {
        Launch done = new Launch(callback);
        background(() -> finish(savesDirectory().toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString(), offer, done, sessionId));
        return done;
    }
    public static Launch startPlaced(WorldTargetPlan plan, HandoffProtocol.Offer offer,
            net.peercraft.network.handoff.HandoffOperation operation, java.util.UUID sessionId, Done callback) {
        Launch done = new Launch(callback);
        background(() -> {
            if (!done.active()) return;
            try { operation.requireCommitted(); finish(plan.root.relativize(plan.target).toString(), offer, done, sessionId); }
            catch (IOException | RuntimeException e) { done.failed("peercraft.handoff.abort.transfer_failed"); }
        });
        return done;
    }

    public static Launch launch(HandoffProtocol.Offer offer, Path worldZip, Done callback) {
        Launch done = new Launch(callback);
        Minecraft mc = Minecraft.getInstance();
        background(() -> {
            Path staging = null;
            try {
                Path savesDir = mc.getLevelSource().getBaseDir();
                Files.createDirectories(savesDir);
                net.peercraft.network.handoff.WorldInstall.recoverAll(savesDir);
                staging = savesDir.resolve(".peercraft-handoff-staging-" + offer.offerId());
                deleteRecursive(staging);
                unzipInto(worldZip, staging);
                Files.deleteIfExists(staging.resolve("session.lock"));

                String incomingId = readWorldId(staging);
                Path existing = incomingId.isEmpty() ? null : findWorldById(savesDir, incomingId, staging);

                if (existing != null) {
                    String existingName = savesDir.relativize(existing).toString();
                    String backupName = existingName + " (до возврата " + timestamp() + ")";
                    Path st = staging;
                    mc.execute(() -> PeerCraftUi.setScreen(mc, new HandoffReclaimConfirmScreen(existingName, backupName,
                            () -> background(() -> finish(reclaimInPlace(savesDir, existing, st, backupName), offer, done)),
                            () -> background(() -> finish(overwriteInPlace(savesDir, existing, st), offer, done)))));
                } else {
                    finish(freshFolder(savesDir, staging, offer.worldLabel()), offer, done);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[Handoff] Не удалось запустить мир как новый хост: {}", e.toString());
                if (staging != null && !Files.exists(staging.resolveSibling(staging.getFileName().toString() + ".install"))) {
                    deleteRecursive(staging);
                }
                done.failed("peercraft.handoff.abort.transfer_failed");
            }
        });
        return done;
    }

    private static void finish(String levelId, HandoffProtocol.Offer offer, Done done) {
        finish(levelId, offer, done, new java.util.UUID(0, 0));
    }
    private static void finish(String levelId, HandoffProtocol.Offer offer, Done done, java.util.UUID sessionId) {
        if (!done.active()) return;
        if (levelId == null) {
            done.failed("peercraft.handoff.abort.transfer_failed");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        applyHostOptions(offer);
        try {
            PeercraftWorldMeta.markBecameHost(mc.getLevelSource().getBaseDir().resolve(levelId));
        } catch (RuntimeException e) {
            LOGGER.debug("[Handoff] markBecameHost: {}", e.toString());
        }
        net.peercraft.network.p2p.P2PBridge.INSTANCE.prepareHandoffRoom(sessionId, offer.offerId());
        final net.peercraft.network.handoff.HandoffLaunchContext context = net.peercraft.network.handoff.HandoffLaunchContext.begin(savesDirectory().resolve(levelId), done::active);
        if (done instanceof Launch) ((Launch) done).nativeContext = context;
        mc.execute(() -> openThenPublish(mc, levelId, new Done() {
            private final java.util.concurrent.atomic.AtomicBoolean terminal = new java.util.concurrent.atomic.AtomicBoolean();
            public boolean active() { return !terminal.get() && done.active(); }
            public net.peercraft.network.handoff.HandoffLaunchContext context() { return context; }
            public void serverPublished() {
                net.peercraft.network.p2p.P2PBridge.INSTANCE.awaitHandoffRoom(sessionId, offer.offerId(),
                        () -> { if (terminal.compareAndSet(false, true)) done.serverPublished(); },
                        reason -> { if (terminal.compareAndSet(false, true)) done.failed(reason); });
            }
            public void failed(String reason) { if (terminal.compareAndSet(false, true)) done.failed(reason); }
        }));
    }

    private static void background(Runnable task) {
        Thread worker = new Thread(task, "PeerCraft-Handoff-Install");
        worker.setDaemon(true); worker.start();
    }

    private static void applyHostOptions(HandoffProtocol.Offer offer) {
        PeerCraftHostOptions.internetPlayRequested = true;
        PeerCraftHostOptions.maxPlayers = Math.max(1, offer.maxPlayers());
        PeerCraftHostOptions.allowUnlicensedPlayers = offer.allowUnlicensed();
        PeerCraftHostOptions.friendsOnly = offer.friendsOnly();
        PeerCraftHostOptions.publicRoom = offer.publicRoom();
        PeerCraftHostOptions.worldName = offer.worldLabel() == null ? "" : offer.worldLabel();
    }

    // ---- folder resolution ----

    private static String reclaimInPlace(Path savesDir, Path existing, Path staging, String backupName) {
        return place(savesDir, existing, staging, savesDir.resolve(backupName), true);
    }

    private static String overwriteInPlace(Path savesDir, Path existing, Path staging) {
        return place(savesDir, existing, staging,
                savesDir.resolve(".peercraft-handoff-backup-" + java.util.UUID.randomUUID()), false);
    }

    private static String place(Path savesDir, Path existing, Path staging, Path backup, boolean keep) {
        try {
            net.peercraft.network.handoff.WorldInstall.replace(staging, existing, backup,
                    savesDir.resolve(staging.getFileName().toString() + ".install"), keep, true, () -> { });
            return savesDir.relativize(existing).toString();
        } catch (IOException e) {
            LOGGER.warn("[Handoff] Placement failed; retaining journal and world copies: {}", e.toString());
            return null;
        }
    }

    private static String freshFolder(Path savesDir, Path staging, String label) {
        String base = sanitize(label == null || label.trim().isEmpty() ? "world" : label);
        String name = base + "-peercraft";
        Path target = savesDir.resolve(name);
        int n = 2;
        while (Files.exists(target)) {
            name = base + "-peercraft-" + n++;
            target = savesDir.resolve(name);
        }
        try {
            Files.move(staging, target);
            LOGGER.info("[Handoff] Мир распакован в saves/{}", name);
            return name;
        } catch (IOException e) {
            LOGGER.warn("[Handoff] Не удалось разместить мир: {}", e.toString());
            deleteRecursive(staging);
            return null;
        }
    }

    private static Path findWorldById(Path savesDir, String worldId, Path staging) throws IOException {
        try (Stream<Path> dirs = Files.list(savesDir)) {
            java.util.List<Path> matches = dirs.filter(Files::isDirectory)
                    .filter(p -> !p.equals(staging) && !p.getFileName().toString().startsWith(".peercraft-")
                            && !p.getFileName().toString().contains(" (до возврата ")
                            && !Files.exists(p.resolve(".peercraft-backup")))
                    .filter(p -> {
                        PeercraftWorldMeta m = PeercraftWorldMeta.loadOrNull(p);
                        return m != null && worldId.equals(m.worldId());
                    }).collect(java.util.stream.Collectors.toList());
            if (matches.size() > 1) throw new IOException("Multiple independent world copies require explicit selection");
            return matches.isEmpty() ? null : matches.get(0);
        }
    }

    private static String readWorldId(Path worldDir) {
        PeercraftWorldMeta m = PeercraftWorldMeta.loadOrNull(worldDir);
        return m == null || m.worldId() == null ? "" : m.worldId();
    }

    // ---- zip ----

    private static void unzipInto(Path worldZip, Path target) throws IOException {
        net.peercraft.network.handoff.WorldInstall.unpack(worldZip, target, 32L * 1024 * 1024 * 1024);
    }

    private static void deleteRecursive(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static String sanitize(String s) {
        String out = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        if (out.length() > 40) {
            out = out.substring(0, 40);
        }
        return out.isEmpty() ? "world" : out;
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT).format(new Date());
    }

    // ---- world open + publish ----

    private static void openThenPublish(Minecraft mc, String levelId, Done done) {
        if (!done.active()) return;
        net.peercraft.network.handoff.HandoffLaunchContext.enter(done.context(), () -> mc.loadLevel(levelId));

        Thread wait = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 180_000L;
            while (System.currentTimeMillis() < deadline && done.active()) {
                IntegratedServer server = mc.getSingleplayerServer();
                if (server != null && server.isRunning() && mc.player != null && done.context() != null && done.context().owns(server, WorldArchiver.worldDir(server))) {
                    mc.execute(() -> publish(server, done));
                    return;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            done.failed("peercraft.handoff.abort.transfer_failed");
        }, "PeerCraft-Handoff-Publish-Wait");
        wait.setDaemon(true);
        wait.start();
    }

    private static void publish(IntegratedServer server, Done done) {
        if (!done.active() || done.context() == null || !done.context().owns(server, WorldArchiver.worldDir(server))) return;
        try {
            int port = net.minecraft.util.HttpUtil.getAvailablePort();
            GameType gameType = server.getWorldData().getGameType();
            boolean ok = server.publishServer(gameType, false, port);
            if (ok) {
                LOGGER.info("[Handoff] Мир открыт для сети на порту {} — регистрируем комнату как новый хост", port);
                done.serverPublished();
            } else {
                done.failed("peercraft.handoff.abort.transfer_failed");
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[Handoff] publishServer у нового хоста не удался: {}", e.toString());
            done.failed("peercraft.handoff.abort.transfer_failed");
        }
    }
}
