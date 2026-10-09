package net.peercraft.client.modsync;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.peercraft.client.gui.ModSyncConfirmScreen;
import net.peercraft.client.gui.ModSyncPreparingScreen;
import net.peercraft.client.gui.ModSyncProgressScreen;
import net.peercraft.client.gui.ModSyncRestartRequiredScreen;
import net.peercraft.client.gui.ModSyncSecurityNoticeScreen;
import net.peercraft.config.ModSyncMode;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.config.PeerCraftSettings;
import net.peercraft.config.PeerCraftSettingsStore;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            // One-time trust gate: mod sync installs jars the host chooses, and a jar is
            // arbitrary code. Shown before every branch below (size checks, autoAccept, the
            // no-screen re-join path) so it can't be skipped. Once acknowledged, the recursive
            // call falls straight through this block.
            if (!PeerCraftSettingsStore.load().modSyncTrustAcknowledged) {
                runOnClientThread(() -> setScreen(new ModSyncSecurityNoticeScreen(
                        () -> {
                            PeerCraftSettings s = PeerCraftSettingsStore.load();
                            s.modSyncTrustAcknowledged = true;
                            PeerCraftSettingsStore.save(s);
                            Thread t = new Thread(() -> planAndPrompt(missing), "PeerCraft-ModSync-Plan");
                            t.setDaemon(true);
                            t.start();
                        },
                        this::onUserCancel)));
                return;
            }

            Set<String> declined = ModSyncDeclinedStore.load();

            // "Только обязательные моды" (client side): drop every purely client-side mod the
            // host offered — keep only what's needed to join. Mode-driven, not the player's
            // choice, so these are NOT written to ModSyncDeclinedStore. First trust the host's
            // env tag, then refine the rest through Modrinth (the same source resolveSources
            // uses), so a mod the host offered as BOTH only because its jar declares "*"
            // (AppleSkin, …) is still skipped. Modrinth failure leaves the host's tag in force.
            boolean requiredOnly = PeerCraftConfig.modSyncClientMode() == ModSyncMode.REQUIRED;
            List<ModEntry> visible = requiredOnly ? keepJoinRequired(missing) : missing;

            // What would actually download if we went ahead with the remembered choices: the
            // visible set minus the client-side mods the player turned down on an earlier join.
            List<ModEntry> toFetch = new ArrayList<>();
            for (ModEntry e : visible) {
                if (!declined.contains(e.id())) {
                    toFetch.add(e);
                }
            }

            long maxModBytes = mib(PeerCraftConfig.modSyncMaxModMb());
            long maxTotalBytes = mib(PeerCraftConfig.modSyncMaxTotalMb());
            long total = 0;
            for (ModEntry e : visible) {
                if (e.sizeBytes() > maxModBytes) {
                    abortTooBig();
                    return;
                }
            }
            for (ModEntry e : toFetch) {
                total += Math.max(0, e.sizeBytes());
            }
            if (total > maxTotalBytes) {
                abortTooBig();
                return;
            }

            // A catalog failure or missing public listing needs a fresh, explicit decision.
            // Even the opt-in autoAccept setting cannot silently install such a jar.
            boolean forceScreen = PeerCraftConfig.modSyncReofferDeclined();
            boolean anythingNew = !toFetch.isEmpty();
            if (!forceScreen && !anythingNew) {
                finishProceed();
                return;
            }

            // Interactive: list the visible set so a previously-declined mod can be re-checked;
            // start its checkbox unticked. Use the plan's mods — their env has been refined by
            // Modrinth, so "is this client-side" matches what the screen will show. In
            // "required only" mode `visible` is already just the join-required mods.
            ModSyncPlan plan = ModSyncPlan.of(resolveSources(visible));
            ModSyncPlan selected = plan.excluding(declined);
            boolean allPublished = selected.mods().stream().allMatch(pm ->
                    pm.catalogStatus() == ModSyncPlan.CatalogStatus.PUBLISHED);
            if (!forceScreen && PeerCraftConfig.modSyncAutoAccept() && allPublished) {
                runDownloads(selected);
                return;
            }
            Set<String> preDeselected = new LinkedHashSet<>();
            for (ModSyncPlan.PlannedMod pm : plan.mods()) {
                if (pm.entry().env() == ModEntry.Env.CLIENT && declined.contains(pm.entry().id())) {
                    preDeselected.add(pm.entry().id());
                }
            }
            runOnClientThread(() -> setScreen(new ModSyncConfirmScreen(
                    plan,
                    preDeselected,
                    deselectedIds -> {
                        ModSyncDeclinedStore.save(deselectedIds);
                        startDownloadThread(plan.excluding(deselectedIds));
                    },
                    this::onUserCancel)));
        } catch (RuntimeException e) {
            LOGGER.warn("[ModSync] Ошибка при построении плана загрузки: {}", e.toString());
            finishFail("peercraft.modsync.fail.transfer");
        }
    }

    /**
     * For every missing mod: look its jar up on Modrinth by hash (parallel), pick HTTP-vs-P2P
     * from whether a {@code cdn.modrinth.com} URL came back, then in one bulk call refine each
     * mod's {@link ModEntry.Env} from Modrinth's curated {@code client_side}/{@code server_side}
     * — the host's jar-metadata guess is only a fallback (on NeoForge it's always {@code BOTH}).
     */
    private List<ModSyncPlan.PlannedMod> resolveSources(List<ModEntry> missing) {
        ModrinthClient modrinth = new ModrinthClient(selfVersion());
        ModrinthClient.Lookup[] lookups = new ModrinthClient.Lookup[missing.size()];
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
                    lookups[idx] = modrinth.lookup(e);
                    int n = done.incrementAndGet();
                    prepStatus(Component.translatable("peercraft.modsync.prepare.checking", n, missing.size()));
                }, pool));
            }
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdownNow();
        }

        List<String> projectIds = new ArrayList<>();
        for (ModrinthClient.Lookup lookup : lookups) {
            ModrinthClient.Resolved r = lookup.resolved().orElse(null);
            if (r != null) {
                projectIds.add(r.projectId());
            }
        }
        Map<String, ModEntry.Env> sideByProject = modrinth.projectSides(projectIds);

        int reclassified = 0;
        List<ModSyncPlan.PlannedMod> out = new ArrayList<>(missing.size());
        for (int i = 0; i < missing.size(); i++) {
            ModEntry e = missing.get(i);
            ModrinthClient.Resolved r = lookups[i].resolved().orElse(null);
            ModEntry.Env env = (r != null) ? sideByProject.getOrDefault(r.projectId(), e.env()) : e.env();
            if (env != e.env()) {
                reclassified++;
            }
            ModEntry entry = (env == e.env()) ? e : withEnv(e, env);
            boolean httpOk = r != null && r.hasDownloadUrl() && r.sha512Hex().equalsIgnoreCase(e.sha512Hex());
            ModSyncPlan.PlannedMod planned = httpOk ? ModSyncPlan.PlannedMod.http(entry, r.url())
                    : ModSyncPlan.PlannedMod.p2p(entry);
            out.add(planned.withCatalogStatus(lookups[i].status()));
        }
        if (reclassified > 0) {
            LOGGER.info("[ModSync] Modrinth уточнил сторону для {} из {} модов.", reclassified, missing.size());
        }
        return out;
    }

    private static ModEntry withEnv(ModEntry e, ModEntry.Env env) {
        return new ModEntry(e.id(), e.version(), e.sizeBytes(), e.sha512(), e.fileName(), env, e.homepageUrl(), e.sourcesUrl());
    }

    /**
     * "Только обязательные моды": keep only the mods a joiner actually needs to enter the world.
     * Drops any the host already tagged client-only, then refines the rest via Modrinth (hash →
     * project → {@code client_side}/{@code server_side}) and drops the ones that come back
     * client-only. Survivors carry the refined env. Modrinth being unreachable just means the
     * host's tags stand (nothing extra is dropped).
     */
    private List<ModEntry> keepJoinRequired(List<ModEntry> missing) {
        List<ModEntry> candidates = new ArrayList<>();
        for (ModEntry e : missing) {
            if (e.env() != ModEntry.Env.CLIENT) {
                candidates.add(e);
            }
        }
        if (candidates.isEmpty()) {
            return candidates;
        }
        Map<String, ModEntry.Env> refined = new ModrinthClient(selfVersion()).refineEnvByModId(candidates);
        List<ModEntry> kept = new ArrayList<>(candidates.size());
        for (ModEntry e : candidates) {
            ModEntry.Env env = refined.getOrDefault(e.id(), e.env());
            if (env != ModEntry.Env.CLIENT) {
                kept.add(env == e.env() ? e : withEnv(e, env));
            }
        }
        return kept;
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
        // Everything the host offered was unchecked (and nothing is required) — nothing to
        // install, no restart needed: just connect to the world this launch.
        if (plan.mods().isEmpty()) {
            finishProceed();
            return;
        }
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
