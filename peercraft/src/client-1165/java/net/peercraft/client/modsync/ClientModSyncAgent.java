package net.peercraft.client.modsync;

// Minecraft 1.16.5 Fabric backport of src/main/.../client/modsync/ClientModSyncAgent.java
// (structural twin of the src/client-1710 backport). Deltas vs src/main:
//   * HTTP / Modrinth fast-path NOT ported (no java.net.http on Java 8) — every missing mod is
//     streamed from the host over P2P. ModrinthClient / ModDownloader / selfVersion() are gone;
//     planning collapses to "every entry -> PlannedMod.p2p"; fetch() always goes P2P;
//     recordInstall always writes source="p2p". `record ModrinthDownloadDeps` removed.
//   * 1.16.5 GUI API: Minecraft.getInstance(); mc.setScreen(s); mc.execute(r); TitleScreen.
//   * Status lines are 1.16.5 Components (new TranslatableComponent(key, args)); the
//     ModSync*Screen twins take Component, like src/main.
// Keep in sync with the original where the shared behaviour is unchanged.

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.peercraft.client.gui.ModSyncConfirmScreen;
import net.peercraft.client.gui.ModSyncPreparingScreen;
import net.peercraft.client.gui.ModSyncProgressScreen;
import net.peercraft.client.gui.ModSyncRestartRequiredScreen;
import net.peercraft.client.gui.ModSyncSecurityNoticeScreen;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.modsync.ModEntry;
import net.peercraft.network.modsync.ModrinthCatalogClient;
import net.peercraft.network.modsync.ModSyncAgent;
import net.peercraft.network.modsync.ModSyncCoordinator;
import net.peercraft.network.modsync.ModSyncLink;
import net.peercraft.network.modsync.ModSyncPlan;
import net.peercraft.platform.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Joiner-side orchestrator for mod sync: runs the handshake through {@link ModSyncCoordinator},
 * turns the host's manifest into a {@link ModSyncPlan} (peer-to-peer stream from the host for
 * every missing jar), gets the player's consent, downloads + verifies + installs each jar,
 * writes the install manifest, shows the "restart required" screen — then tells
 * {@code P2PBridge} to abort the join (no connect this launch).
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

    private void prepStatus(String key, Object... args) {
        ModSyncPreparingScreen s = preparingScreen;
        if (s != null) {
            s.setStatus(new TranslatableComponent(key, args));
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
        // Mod sync is an enhancement, not a gate. If the host aborts the handshake, don't
        // dead-end the join — proceed to connect and let vanilla show its "missing mods"
        // screen if it comes to that.
        LOGGER.info("[ModSync] Хост прервал синхронизацию ({}) — подключаемся обычным образом.", reasonKey);
        finishProceed();
    }

    @Override
    public void onManifest(List<ModEntry> missing) {
        prepStatus("peercraft.modsync.prepare.checking", missing.size(), missing.size());
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
            // arbitrary code. Shown before every branch below so it can't be skipped; once
            // acknowledged, the recursive call falls straight through.
            if (!net.peercraft.config.PeerCraftSettingsStore.load().modSyncTrustAcknowledged) {
                runOnClientThread(() -> setScreen(new ModSyncSecurityNoticeScreen(
                        () -> {
                            net.peercraft.config.PeerCraftSettings s = net.peercraft.config.PeerCraftSettingsStore.load();
                            s.modSyncTrustAcknowledged = true;
                            net.peercraft.config.PeerCraftSettingsStore.save(s);
                            Thread t = new Thread(() -> planAndPrompt(missing), "PeerCraft-ModSync-Plan");
                            t.setDaemon(true);
                            t.start();
                        },
                        this::onUserCancel)));
                return;
            }

            Set<String> declined = ModSyncDeclinedStore.load();

            // "Только обязательные моды" (client side): drop purely client-side mods — keep only
            // what's needed to join. Mode-driven, so NOT persisted to ModSyncDeclinedStore.
            // Uses the host's env tag only — no Modrinth refinement here (no java.net.http on Java 8),
            // so a client mod the host offered as "*"/BOTH is not caught (src/main does catch it).
            boolean requiredOnly = PeerCraftConfig.modSyncClientMode() == net.peercraft.config.ModSyncMode.REQUIRED;
            List<ModEntry> visible = missing;
            if (requiredOnly) {
                visible = new ArrayList<>();
                for (ModEntry e : missing) {
                    if (e.env() != ModEntry.Env.CLIENT) {
                        visible.add(e);
                    }
                }
            }

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

            boolean forceScreen = PeerCraftConfig.modSyncReofferDeclined();
            boolean anythingNew = !toFetch.isEmpty();
            if (!forceScreen && !anythingNew) {
                finishProceed();
                return;
            }

            ModSyncPlan plan = ModSyncPlan.of(plannedP2p(visible));
            ModSyncPlan selected = plan.excluding(declined);
            boolean allPublished = true;
            for (ModSyncPlan.PlannedMod mod : selected.mods()) {
                if (mod.catalogStatus() != ModSyncPlan.CatalogStatus.PUBLISHED) allPublished = false;
            }
            if (!forceScreen && PeerCraftConfig.modSyncAutoAccept() && allPublished) {
                runDownloads(selected);
                return;
            }
            Set<String> preDeselected = new LinkedHashSet<>();
            for (ModEntry e : visible) {
                if (e.env() == ModEntry.Env.CLIENT && declined.contains(e.id())) {
                    preDeselected.add(e.id());
                }
            }
            final ModSyncPlan finalPlan = plan;
            runOnClientThread(() -> setScreen(new ModSyncConfirmScreen(
                    finalPlan,
                    preDeselected,
                    deselectedIds -> {
                        ModSyncDeclinedStore.save(deselectedIds);
                        startDownloadThread(finalPlan.excluding(deselectedIds));
                    },
                    this::onUserCancel)));
        } catch (RuntimeException e) {
            LOGGER.warn("[ModSync] Ошибка при построении плана загрузки: {}", e.toString());
            finishFail("peercraft.modsync.fail.transfer");
        }
    }

    /** no Modrinth fast-path here — every missing jar is streamed from the host over P2P. */
    private List<ModSyncPlan.PlannedMod> plannedP2p(List<ModEntry> missing) {
        ModSyncPlan.CatalogStatus[] statuses = ModrinthCatalogClient.checkAll(missing,
                count -> prepStatus("peercraft.modsync.prepare.checking", count, missing.size()));
        List<ModSyncPlan.PlannedMod> out = new ArrayList<>(missing.size());
        for (int i = 0; i < missing.size(); i++) {
            out.add(ModSyncPlan.PlannedMod.p2p(missing.get(i)).withCatalogStatus(statuses[i]));
        }
        return out;
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
        terminated.set(true);
        safeSendAbort("peercraft.modsync.cancelled");
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

            Path verified = fetch(pm, part, maxModBytes, screen);
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
        final List<String> names = new ArrayList<>(installedNames);
        runOnClientThread(() -> setScreen(new ModSyncRestartRequiredScreen(names)));
        finishAbort();
    }

    /** always a peer-to-peer transfer. Returns a hash-verified part file or null. */
    private Path fetch(ModSyncPlan.PlannedMod pm, Path part, long maxModBytes, ModSyncProgressScreen screen) {
        return fetchP2p(pm.entry(), screen);
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
        rec.source = "p2p";
        rec.url = "";
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
        mc.setScreen(target);
    }

    private static void runOnClientThread(Runnable r) {
        Minecraft.getInstance().execute(r);
    }

    private static long mib(int mb) {
        return (long) mb * 1024L * 1024L;
    }

    private static final class TransferFailed extends RuntimeException {
        TransferFailed(String key) {
            super(key);
        }
    }
}
