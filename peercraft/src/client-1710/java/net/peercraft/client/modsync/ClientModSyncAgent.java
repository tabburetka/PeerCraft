package net.peercraft.client.modsync;

// Forge 1.7.10 backport of src/main/.../client/modsync/ClientModSyncAgent.java (twin of the
// src/client-1122 backport). Deltas:
//   * Screen -> GuiScreen; TitleScreen -> GuiMainMenu; Minecraft.getInstance() ->
//     getMinecraft(); mc.execute(r) -> mc.func_152344_a(r) (addScheduledTask is not mapped in
//     MCP stable_12, as in the other 1.7.10 GUI twins); mc.setScreen(s) ->
//     mc.displayGuiScreen(s).
//   * net.minecraft.network.chat.Component -> plain translation-key Strings; the GuiScreen
//     twins resolve them through PeerCraftLang.
//   * HTTPS downloads use the shared Java 8 ModrinthDownloader with P2P fallback.
// Keep in sync with the original where the shared behaviour is unchanged.

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiMainMenu;
import net.peercraft.client.gui.ModSyncConfirmScreen;
import net.peercraft.client.gui.ModSyncPreparingScreen;
import net.peercraft.client.gui.ModSyncProgressScreen;
import net.peercraft.client.gui.ModSyncRestartRequiredScreen;
import net.peercraft.client.gui.ModSyncSecurityNoticeScreen;
import net.peercraft.client.gui.ModSyncIssuesScreen;
import net.peercraft.client.gui.PeerCraftLang;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.config.PeerCraftSettings;
import net.peercraft.config.PeerCraftSettingsStore;
import net.peercraft.network.modsync.ModDiff;
import net.peercraft.network.modsync.ModEntry;
import net.peercraft.network.modsync.ModrinthCatalogClient;
import net.peercraft.network.modsync.ModrinthDownloader;
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

    private final GuiScreen previousScreen;
    private ModSyncInventory inventory;
    private final AtomicBoolean committing = new AtomicBoolean(false);
    private final String roomCode;

    private volatile ModSyncLink link;
    private volatile Outcome outcome;
    private volatile ModSyncCoordinator coordinator;
    private volatile ModSyncPreparingScreen preparingScreen;
    private volatile ModSyncProgressScreen progressScreen;
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private volatile boolean cancelled;
    private volatile long currentBase;       // installed bytes before the mod currently downloading
    private volatile int currentModsDone;    // mods fully installed so far
    private final ConcurrentHashMap<String, CompletableFuture<Path>> p2pFiles = new ConcurrentHashMap<>();

    public ClientModSyncAgent(GuiScreen previousScreen) {
        this(previousScreen, "");
    }

    public ClientModSyncAgent(GuiScreen previousScreen, String roomCode) {
        this.previousScreen = previousScreen;
        this.roomCode = roomCode == null ? "" : roomCode;
    }

    // ================= ModSyncAgent =================

    @Override
    public void run(ModSyncLink link, Outcome outcome) {
        if (cancelled) return;
        this.link = link;
        this.outcome = outcome;
        ModSyncPreparingScreen prep = new ModSyncPreparingScreen(this::onUserCancel);
        this.preparingScreen = prep;
        runOnClientThread(() -> setScreen(prep));
        Thread inspect = new Thread(() -> inspectAndStart(link), "PeerCraft-ModSync-Inventory");
        inspect.setDaemon(true); inspect.start();
    }

    private void inspectAndStart(ModSyncLink link) {
        try {
            Path modsDir = Services.PLATFORM.getModsDir();
            Path tmpDir = ModSyncFilesystem.tempDir(modsDir);
            long maxModBytes = mib(PeerCraftConfig.modSyncMaxModMb());
            if (ModSyncPendingInstaller.hasPending()) throw new IllegalStateException("A Mod Sync transaction is pending; close Minecraft first");
            inventory = ModSyncInventory.capture();
            if (terminated.get()) return;
            ModSyncCoordinator c = ModSyncCoordinator.joiner(
                    link::send, LoaderTag.current(), inventory.refs(), tmpDir, maxModBytes, this, InstalledModScanner.minecraftVersion());
            this.coordinator = c;
            if (terminated.get()) { c.cancel(); return; }
            link.bindInbound(c);
            c.startJoiner();
        } catch (Exception e) {
            if (terminated.get()) return;
            LOGGER.warn("[ModSync] Не удалось проверить mod-sync: {}", e.toString());
            ModSyncProgressScreen failure = new ModSyncProgressScreen(ModSyncPlan.of(new ArrayList<ModSyncPlan.PlannedMod>()), this::onUserCancel);
            runOnClientThread(() -> { setScreen(failure); failure.showError("peercraft.modsync.fail.inventory", ModSyncPendingInstaller.startupIssue()); });
            finishFail("peercraft.modsync.fail.inventory");
        }
    }

    private void prepStatus(String key, Object... args) {
        ModSyncPreparingScreen s = preparingScreen;
        if (s != null) {
            s.setStatus(PeerCraftLang.tr(key, args));
        }
    }

    /** Stop attempt-owned work without reopening a screen the player has left. */
    @Override
    public void cancel() {
        cancelled = true;
        terminated.set(true);
        p2pFiles.values().forEach(f -> f.completeExceptionally(new TransferFailed("peercraft.modsync.cancelled")));
        ModSyncCoordinator c = coordinator;
        if (c != null) c.cancel();
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
        Thread t = new Thread(() -> reviewManifest(missing), "PeerCraft-ModSync-Plan");
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

    private void reviewManifest(List<ModEntry> offered) {
        if (terminated.get()) return;
        List<ModEntry> safe = inventory.candidates(offered, coordinator.replacementsAllowed());
        if (inventory.issues.isEmpty()) { planAndPrompt(safe); return; }
        List<String> warnings = new ArrayList<String>();
        for (ModSyncInventory.Issue issue : inventory.issues) warnings.add(PeerCraftLang.tr("peercraft.modsync.skip." + issue.reason, issue.id));
        runOnClientThread(() -> setScreen(new ModSyncIssuesScreen(warnings,
                () -> {
                    Thread t = new Thread(() -> planAndPrompt(safe), "PeerCraft-ModSync-Plan");
                    t.setDaemon(true); t.start();
                }, this::onUserCancel)));
    }

    private void planAndPrompt(List<ModEntry> missing) {
        try {
            // One-time trust gate: mod sync installs jars the host chooses, and a jar is
            // arbitrary code. Shown before every branch below so it can't be skipped; once
            // acknowledged, the recursive call falls straight through.
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
                if (e.env() != ModEntry.Env.CLIENT || !declined.contains(e.id())) {
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

            ModSyncPlan plan = inventory.annotate(ModSyncPlan.of(plannedDownloads(visible)));
            Set<String> optionalDeclined = new LinkedHashSet<String>();
            for (ModSyncPlan.PlannedMod mod : plan.mods()) if (mod.entry().env() == ModEntry.Env.CLIENT && declined.contains(mod.entry().id())) optionalDeclined.add(mod.entry().id());
            ModSyncPlan selected = plan.excluding(optionalDeclined);
            if (selected.canAutoAccept(PeerCraftConfig.modSyncAutoAccept(), forceScreen)) {
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

    /** Resolve exact catalog files; unavailable entries retain the P2P path. */
    private List<ModSyncPlan.PlannedMod> plannedDownloads(List<ModEntry> missing) {
        ModSyncPlan.PlannedMod[] resolved = ModrinthCatalogClient.lookupAll(missing,
                count -> prepStatus("peercraft.modsync.prepare.checking", count, missing.size()));
        List<ModSyncPlan.PlannedMod> out = new ArrayList<>(missing.size());
        for (int i = 0; i < missing.size(); i++) {
            out.add(resolved[i]);
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
        if (committing.get()) return;
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
        Path tmpDir = ModSyncFilesystem.tempDir(Services.PLATFORM.getModsDir());
        long maxModBytes = mib(PeerCraftConfig.modSyncMaxModMb());
        List<Path> verifiedFiles = new ArrayList<Path>();
        List<String> names = new ArrayList<String>();
        this.currentBase = 0; this.currentModsDone = 0;
        try {
            Files.createDirectories(tmpDir);
            for (ModSyncPlan.PlannedMod pm : plan.mods()) {
                if (terminated.get()) return;
                ModEntry e = pm.entry();
                runOnClientThread(() -> { screen.setCurrentMod(e.id()); screen.setOverall(currentBase, currentModsDone); });
                Path part = tmpDir.resolve(ModSyncCoordinator.safeName(e.id()) + ".jar.part");
                Path verified = fetch(pm, part, maxModBytes, screen);
                if (verified != null) verifiedFiles.add(verified);
                if (terminated.get()) return;
                if (verified == null) { failDownload("peercraft.modsync.fail.transfer", screen); return; }
                if (Files.size(verified) != e.sizeBytes() || !e.sha512Hex().equalsIgnoreCase(ModSyncInventory.hex(ModSyncFilesystem.hashFile(verified))))
                    throw new java.io.IOException("Downloaded file failed verification: " + e.id());
                names.add(e.id() + " " + (pm.replacement() == null ? "" : pm.replacement().version() + " → ") + e.version());
                currentBase += Math.max(0, e.sizeBytes()); currentModsDone++;
                runOnClientThread(() -> screen.setOverall(currentBase, currentModsDone));
            }
            if (terminated.get()) return;
            committing.set(true);
            if (terminated.get()) return;
            runOnClientThread(screen::beginCommit);
            if (plan.hasReplacements()) {
                ModSyncPendingInstaller.arm(plan, verifiedFiles, roomCode, inventory.fingerprints(), () -> terminated.get());
            } else {
                ModSyncPendingInstaller.installNow(plan, verifiedFiles, roomCode, inventory.fingerprints());
            }
            safeSendAbort("done");
            runOnClientThread(() -> setScreen(new ModSyncRestartRequiredScreen(names, plan.hasReplacements())));
            finishAbort();
        } catch (Exception e) {
            LOGGER.warn("[ModSync] Install batch failed: {}", e.toString());
            if (!terminated.get()) {
                safeSendAbort("peercraft.modsync.fail.io");
                runOnClientThread(() -> screen.showError("peercraft.modsync.fail.io", e.getMessage()));
                finishFail("peercraft.modsync.fail.io");
            }
        } finally {
            for (Path file : verifiedFiles) ModSyncFilesystem.deleteQuietly(file);
        }
    }

    /** HTTPS failures, including integrity failures, fall back to the host. */
    private Path fetch(ModSyncPlan.PlannedMod pm, Path part, long maxModBytes, ModSyncProgressScreen screen) {
        if (pm.source() == ModSyncPlan.Source.HTTP) {
            runOnClientThread(() -> screen.setState(pm.entry().id(), ModSyncProgressScreen.State.DOWNLOADING));
            boolean downloaded = ModrinthDownloader.download(pm.httpUrl(), part, pm.entry(), maxModBytes,
                    () -> terminated.get(), (received, total) -> onFileProgress(pm.entry().id(), received, total));
            if (downloaded) return part;
            if (terminated.get()) return null;
            LOGGER.info("[ModSync] HTTPS failed for {}; falling back to P2P", pm.entry().id());
            runOnClientThread(() -> screen.updateProgress(pm.entry().id(), 0, pm.entry().sizeBytes()));
        }
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

    private void setScreen(GuiScreen screen) {
        if (cancelled) return;
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen target = screen != null ? screen : new GuiMainMenu();
        mc.displayGuiScreen(target);
    }

    private static void runOnClientThread(Runnable r) {
        Minecraft.getMinecraft().func_152344_a(r);
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
