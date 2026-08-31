package net.peercraft.client.modsync;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.gui.ModSyncConfirmScreen;
import net.peercraft.client.gui.ModSyncPreparingScreen;
import net.peercraft.client.gui.ModSyncProgressScreen;
import net.peercraft.client.gui.ModSyncRestartRequiredScreen;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.modsync.ModDiff;
import net.peercraft.network.modsync.ModEntry;
import net.peercraft.network.modsync.ModSyncAgent;
import net.peercraft.network.modsync.ModSyncCoordinator;
import net.peercraft.network.modsync.ModSyncLink;
import net.peercraft.network.modsync.ModSyncPlan;
import net.peercraft.network.modsync.ModSyncProtocol;
import net.peercraft.platform.Services;
import net.peercraft.platform.services.PlatformMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Joiner-side orchestrator for mod sync: runs the handshake through {@link ModSyncCoordinator},
 * turns the host's manifest into a {@link ModSyncPlan} (HTTP-from-Modrinth where a hash
 * lookup resolves, peer-to-peer otherwise), gets the player's consent, downloads + verifies +
 * installs each jar, writes the install manifest, and shows the "restart required" screen —
 * then tells {@code P2PBridge} to abort the join (no connect this launch).
 *
 * <p>Implements {@link ModSyncAgent} (the seam {@code P2PBridge} calls) and
 * {@link ModSyncCoordinator.JoinerHandler} (the coordinator's callbacks). All coordinator
 * callbacks arrive on background threads; screen work is marshalled to the client thread.
 */
public final class ClientModSyncAgent implements ModSyncAgent, ModSyncCoordinator.JoinerHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final long P2P_FILE_TIMEOUT_MILLIS = 12 * 60_000;

    private final Screen previousScreen;
    private final String roomCode;

    private volatile ModSyncLink link;
    private volatile Outcome outcome;
    private volatile ModSyncCoordinator coordinator;
    private volatile ModSyncPreparingScreen preparingScreen;
    private volatile ModSyncProgressScreen progressScreen;
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private volatile long currentBase;       // installed bytes before the mod currently downloading
    private volatile int currentModsDone;    // mods fully installed so far
    private final ConcurrentHashMap<String, CompletableFuture<Path>> p2pFiles = new ConcurrentHashMap<>();

    public ClientModSyncAgent(Screen previousScreen) {
        this(previousScreen, "");
    }

    public ClientModSyncAgent(Screen previousScreen, String roomCode) {
        this.previousScreen = previousScreen;
        this.roomCode = roomCode == null ? "" : roomCode;
    }

    // ================= ModSyncAgent =================

    @Override
    public void run(ModSyncLink link, Outcome outcome) {
        this.link = link;
        this.outcome = outcome;
        ModSyncPreparingScreen prep = new ModSyncPreparingScreen(this::onUserCancel);
        this.preparingScreen = prep;
        runOnClientThread(() -> setScreen(prep));
        try {
            Path modsDir = Services.PLATFORM.getModsDir();
            Path tmpDir = ModSyncFilesystem.tempDir(modsDir);
            long maxModBytes = mib(PeerCraftConfig.modSyncMaxModMb());
            ModSyncCoordinator c = ModSyncCoordinator.joiner(
                    link::send, LoaderTag.current(), InstalledModScanner.localModRefs(), tmpDir, maxModBytes, this);
            this.coordinator = c;
            link.bindInbound(c);
            c.startJoiner();
        } catch (RuntimeException e) {
            LOGGER.warn("[ModSync] Не удалось запустить mod-sync — подключаемся без него: {}", e.toString());
            finishProceed();
        }
    }

    private void prepStatus(Component status) {
        ModSyncPreparingScreen s = preparingScreen;
        if (s != null) {
            s.setStatus(status);
        }
    }

    // ================= JoinerHandler =================

    @Override
    public void onNothingMissing() {
        finishProceed();
    }

    @Override
    public void onHandshakeTimeout() {
        finishProceed();
    }

    @Override
    public void onAbort(String reasonKey) {
        // Mod sync is an enhancement, not a gate. If the host aborts the handshake (loader
        // mismatch, incompatible PeerCraft version, its own error), don't dead-end the join —
        // proceed to connect and let vanilla show its "missing mods" screen if it comes to that.
        // Failing here instead just left the player re-clicking Connect in a loop.
        LOGGER.info("[ModSync] Хост прервал синхронизацию ({}) — подключаемся обычным образом.", reasonKey);
        finishProceed();
    }

    @Override
    public void onManifest(List<ModEntry> missing) {
        prepStatus(Component.translatable("peercraft.modsync.prepare.checking", 0, missing.size()));
        // HTTP resolution + downloads must not run on the packet thread.
        Thread t = new Thread(() -> planAndPrompt(missing), "PeerCraft-ModSync-Plan");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onFileProgress(String modId, long received, long total) {
        ModSyncProgressScreen s = progressScreen;
        if (s != null) {
            long overall = currentBase + received;
            runOnClientThread(() -> {
                s.updateProgress(modId, received, total);
                s.setOverall(overall, currentModsDone);
            });
        }
    }

    @Override
    public void onFileComplete(String modId, Path verifiedPartFile) {
        CompletableFuture<Path> f = p2pFiles.get(modId);
        if (f != null) {
            f.complete(verifiedPartFile);
        }
    }

    @Override
    public void onFileFailed(String modId, String reasonKey) {
        CompletableFuture<Path> f = p2pFiles.get(modId);
        if (f != null) {
            f.completeExceptionally(new TransferFailed(reasonKey));
        }
    }

    // ================= planning =================

    private void planAndPrompt(List<ModEntry> missing) {
        try {
            long maxModBytes = mib(PeerCraftConfig.modSyncMaxModMb());
            long maxTotalBytes = mib(PeerCraftConfig.modSyncMaxTotalMb());

            long total = 0;
            for (ModEntry e : missing) {
                total += Math.max(0, e.sizeBytes());
                if (e.sizeBytes() > maxModBytes) {
                    abortTooBig();
                    return;
                }
            }
            if (total > maxTotalBytes) {
                abortTooBig();
                return;
            }

            List<ModSyncPlan.PlannedMod> planned = resolveSources(missing);
            ModSyncPlan plan = ModSyncPlan.of(planned);
            if (PeerCraftConfig.modSyncAutoAccept()) {
                runDownloads(plan);
            } else {
                runOnClientThread(() -> setScreen(new ModSyncConfirmScreen(
                        plan,
                        () -> startDownloadThread(plan),
                        this::onUserCancel)));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[ModSync] Ошибка при построении плана загрузки: {}", e.toString());
            finishFail("peercraft.modsync.fail.transfer");
        }
    }

    /** Resolve HTTP-vs-P2P for every missing mod, several Modrinth lookups at a time, updating the "checking N/M" status as they land. */
    private List<ModSyncPlan.PlannedMod> resolveSources(List<ModEntry> missing) {
        ModrinthClient modrinth = new ModrinthClient(selfVersion());
        ModSyncPlan.PlannedMod[] out = new ModSyncPlan.PlannedMod[missing.size()];
        AtomicInteger done = new AtomicInteger(0);
        int parallel = Math.min(6, Math.max(1, missing.size()));
        ExecutorService pool = Executors.newFixedThreadPool(parallel, r -> {
            Thread t = new Thread(r, "PeerCraft-ModSync-Resolve");
            t.setDaemon(true);
            return t;
        });
        try {
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < missing.size(); i++) {
                final int idx = i;
                final ModEntry e = missing.get(i);
                tasks.add(CompletableFuture.runAsync(() -> {
                    Optional<ModrinthClient.Resolved> r = modrinth.resolve(e);
                    out[idx] = (r.isPresent() && r.get().sha512Hex().equalsIgnoreCase(e.sha512Hex()))
                            ? ModSyncPlan.PlannedMod.http(e, r.get().url())
                            : ModSyncPlan.PlannedMod.p2p(e);
                    int n = done.incrementAndGet();
                    prepStatus(Component.translatable("peercraft.modsync.prepare.checking", n, missing.size()));
                }, pool));
            }
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdownNow();
        }
        return List.of(out);
    }

    private void abortTooBig() {
        safeSendAbort("peercraft.modsync.fail.too_big");
        runOnClientThread(() -> setScreen(previousScreen));
        finishFail("peercraft.modsync.fail.too_big");
    }

    private void onUserCancel() {
        safeSendAbort("peercraft.modsync.cancelled");
        runOnClientThread(() -> setScreen(previousScreen));
        finishAbort();
    }

    /** Cancel button on the progress screen: stop everything, drop partial files, back out of the join. */
    private void onDownloadCancel() {
        terminated.set(true); // stop the download loop at its next iteration
        safeSendAbort("peercraft.modsync.cancelled");
        // Unblock a fetchP2p() that's parked on a file future.
        p2pFiles.values().forEach(f -> f.completeExceptionally(new TransferFailed("peercraft.modsync.cancelled")));
        ModSyncCoordinator c = coordinator;
        if (c != null) {
            c.cancel();
        }
        runOnClientThread(() -> setScreen(previousScreen));
        if (outcome != null) {
            outcome.abortJoin();
        }
    }

    private void startDownloadThread(ModSyncPlan plan) {
        Thread t = new Thread(() -> runDownloads(plan), "PeerCraft-ModSync-Download");
        t.setDaemon(true);
        t.start();
    }

    // ================= download + install =================

    private void runDownloads(ModSyncPlan plan) {
        ModSyncProgressScreen screen = new ModSyncProgressScreen(plan, this::onDownloadCancel);
        this.progressScreen = screen;
        runOnClientThread(() -> setScreen(screen));

        Path modsDir = Services.PLATFORM.getModsDir();
        Path tmpDir = ModSyncFilesystem.tempDir(modsDir);
        long maxModBytes = mib(PeerCraftConfig.modSyncMaxModMb());
        ModrinthDownloadDeps deps = new ModrinthDownloadDeps(new ModDownloader(selfVersion()));
        ModSyncManifest manifest = ModSyncManifestStore.load();
        List<String> installedNames = new ArrayList<>();
        this.currentBase = 0;
        this.currentModsDone = 0;

        try {
            Files.createDirectories(tmpDir);
        } catch (Exception ignored) {
        }

        for (ModSyncPlan.PlannedMod pm : plan.mods()) {
            if (terminated.get()) {
                return;
            }
            ModEntry e = pm.entry();
            runOnClientThread(() -> {
                screen.setCurrentMod(e.id());
                screen.setOverall(currentBase, currentModsDone);
            });
            Path part = tmpDir.resolve(ModSyncCoordinator.safeName(e.id()) + ".jar.part");

            Path verified = fetch(pm, part, maxModBytes, deps, screen);
            if (terminated.get()) {
                return;
            }
            if (verified == null) {
                failDownload("peercraft.modsync.fail.transfer", screen);
                return;
            }

            try {
                runOnClientThread(() -> screen.setState(e.id(), ModSyncProgressScreen.State.INSTALLING));
                ModSyncFilesystem.Installed done = ModSyncFilesystem.install(verified, e, modsDir);
                recordInstall(manifest, pm, done);
                installedNames.add(e.id() + " " + e.version());
            } catch (Exception ex) {
                LOGGER.warn("[ModSync] Не удалось установить {}: {}", e.id(), ex.toString());
                failDownload("peercraft.modsync.fail.io", screen);
                return;
            }
            this.currentBase += Math.max(0, e.sizeBytes());
            this.currentModsDone++;
            runOnClientThread(() -> screen.setOverall(currentBase, currentModsDone));
        }

        ModSyncManifestStore.save(manifest);
        safeSendAbort("done");
        List<String> names = List.copyOf(installedNames);
        runOnClientThread(() -> setScreen(new ModSyncRestartRequiredScreen(names)));
        finishAbort();
    }

    /** HTTP first when planned; on any HTTP failure fall back to a peer-to-peer transfer. Returns a hash-verified part file or null. */
    private Path fetch(ModSyncPlan.PlannedMod pm, Path part, long maxModBytes, ModrinthDownloadDeps deps, ModSyncProgressScreen screen) {
        ModEntry e = pm.entry();
        if (pm.source() == ModSyncPlan.Source.HTTP) {
            runOnClientThread(() -> screen.setState(e.id(), ModSyncProgressScreen.State.DOWNLOADING));
            byte[] got = deps.downloader().download(pm.httpUrl(), part, e.sizeBytes(), maxModBytes,
                    (r, t) -> onFileProgress(e.id(), r, t));
            if (got != null && MessageDigest.isEqual(got, e.sha512())) {
                runOnClientThread(() -> screen.setState(e.id(), ModSyncProgressScreen.State.VERIFYING));
                return part;
            }
            ModSyncFilesystem.deleteQuietly(part);
            LOGGER.info("[ModSync] HTTP-загрузка {} не удалась — переходим на P2P.", e.id());
        }
        return fetchP2p(e, screen);
    }

    private Path fetchP2p(ModEntry e, ModSyncProgressScreen screen) {
        ModSyncCoordinator c = coordinator;
        if (c == null) {
            return null;
        }
        runOnClientThread(() -> screen.setState(e.id(), ModSyncProgressScreen.State.DOWNLOADING));
        CompletableFuture<Path> f = new CompletableFuture<>();
        p2pFiles.put(e.id(), f);
        c.requestFile(e);
        try {
            return f.get(P2P_FILE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        } catch (ExecutionException ex) {
            LOGGER.info("[ModSync] P2P-передача {} не удалась: {}", e.id(), ex.getCause() != null ? ex.getCause().getMessage() : ex.toString());
            return null;
        } finally {
            p2pFiles.remove(e.id());
        }
    }

    private void recordInstall(ModSyncManifest manifest, ModSyncPlan.PlannedMod pm, ModSyncFilesystem.Installed done) {
        ModSyncManifest.Install rec = new ModSyncManifest.Install();
        rec.modId = pm.entry().id();
        rec.modVersion = pm.entry().version();
        rec.fileName = done.fileName();
        rec.sha512 = pm.entry().sha512Hex();
        rec.source = pm.source() == ModSyncPlan.Source.HTTP ? "http" : "p2p";
        rec.url = pm.httpUrl() == null ? "" : pm.httpUrl();
        rec.sizeBytes = pm.entry().sizeBytes();
        rec.installedAt = System.currentTimeMillis();
        rec.hostRoomCode = roomCode;
        rec.supersededPath = "";
        manifest.record(rec);
    }

    private void failDownload(String key, ModSyncProgressScreen screen) {
        safeSendAbort(key);
        runOnClientThread(() -> screen.showError(key));
        finishFail(key);
    }

    // ================= terminal transitions =================

    private void finishProceed() {
        if (terminated.compareAndSet(false, true)) {
            // Restore the screen the join was started from BEFORE proceeding: the join/games
            // screen's onConnected() bails via stillOnThisScreen() if we're still showing the
            // mod-sync preparing screen, so ConnectScreen.startConnecting would never fire.
            runOnClientThread(() -> setScreen(previousScreen));
            outcome.proceedToConnect();
        }
    }

    private void finishAbort() {
        if (terminated.compareAndSet(false, true)) {
            outcome.abortJoin();
        }
    }

    private void finishFail(String key) {
        if (terminated.compareAndSet(false, true)) {
            outcome.fail(key);
        }
    }

    // ================= helpers =================

    private void safeSendAbort(String key) {
        ModSyncCoordinator c = coordinator;
        if (c != null) {
            c.sendAbort(key);
        }
    }

    private void setScreen(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        Screen target = screen != null ? screen : new TitleScreen();
        //? if <26.2
        mc.setScreen(target);
        //? if >=26.2
        /*mc.gui.setScreen(target);*/
    }

    private static void runOnClientThread(Runnable r) {
        Minecraft.getInstance().execute(r);
    }

    private static long mib(int mb) {
        return (long) mb * 1024L * 1024L;
    }

    private static String selfVersion() {
        for (PlatformMod pm : InstalledModScanner.allInstalled()) {
            if ("peercraft".equals(pm.id())) {
                return pm.version();
            }
        }
        return "2.0.0";
    }

    private record ModrinthDownloadDeps(ModDownloader downloader) {
    }

    private static final class TransferFailed extends RuntimeException {
        TransferFailed(String key) {
            super(key);
        }
    }
}
