package net.peercraft.network.handoff;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Local committed placement recovery. It never starts a server or resolves a COMMIT by guesswork. */
public final class WorldInstallRecovery {
    private static final ConcurrentHashMap<Path, CompletableFuture<Void>> roots = new ConcurrentHashMap<>();
    private WorldInstallRecovery() { }

    public static CompletableFuture<Void> start(Path root) {
        Path key = root.toAbsolutePath().normalize();
        return roots.computeIfAbsent(key, path -> {
            CompletableFuture<Void> result = new CompletableFuture<>();
            Thread worker = new Thread(() -> {
                try { WorldInstall.recoverAll(path); result.complete(null); }
                catch (Exception failure) {
                    org.slf4j.LoggerFactory.getLogger("peercraft").error("[Handoff] Placement recovery failed; copies retained at {}", path, failure);
                    result.completeExceptionally(failure);
                }
            }, "PeerCraft-Handoff-Startup-Recovery");
            worker.setDaemon(true); worker.start(); return result;
        });
    }

    /** Returns true when the native open must be postponed/cancelled. All callbacks run on the render scheduler. */
    public static boolean deferOpen(Path root, Consumer<Runnable> render, Runnable retry, Consumer<Throwable> failed) {
        CompletableFuture<Void> result = start(root);
        if (result.isDone() && !result.isCompletedExceptionally()) return false;
        result.whenComplete((value, failure) -> render.accept(() -> {
            if (failure == null) retry.run(); else failed.accept(failure);
        }));
        return true;
    }
}
