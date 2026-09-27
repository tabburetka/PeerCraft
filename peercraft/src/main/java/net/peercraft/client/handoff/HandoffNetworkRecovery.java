package net.peercraft.client.handoff;

import net.peercraft.network.handoff.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static net.peercraft.network.handoff.HandoffAuthorityProtocol.*;

/** Resolve persisted authority before native world opening. Never launches Minecraft itself. */
public final class HandoffNetworkRecovery {
    public interface GrantWriter { void write(Path world, UUID session, long epoch, byte[] key) throws IOException; }
    private static final Map<Path, CompletableFuture<Void>> worlds = new ConcurrentHashMap<>();
    private static final CompletableFuture<Void> scanned = new CompletableFuture<>();
    private static boolean started;
    private HandoffNetworkRecovery() { }
    public static synchronized void start(Path journals, Path saves, GrantWriter grants, boolean modernLock) {
        if (started) return; started = true;
        Thread scan = new Thread(() -> {
            ExecutorService workers = Executors.newFixedThreadPool(2, task -> {
                Thread t = new Thread(task, "PeerCraft-Handoff-Authority-Recovery"); t.setDaemon(true); return t;
            });
            try {
                if (Files.exists(journals, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isDirectory(journals, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid recovery directory");
                    List<HandoffJournal> pending = new ArrayList<>();
                    Map<Path, HandoffJournal> latest = new HashMap<>();
                    Map<HandoffJournal, Long> times = new IdentityHashMap<>();
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(journals, "*.journal")) {
                        for (Path file : files) {
                            if (pending.size() >= 1000 || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 65536)
                                throw new IOException("Invalid or excessive unresolved handoff journals");
                            HandoffJournal j = HandoffJournal.read(file);
                            if (!file.getFileName().toString().equals(j.session + "-" + Long.toHexString(j.offer) + ".journal"))
                                throw new IOException("Recovery identity does not match journal");
                            pending.add(j); times.put(j, Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis());
                            for (String path : Arrays.asList(j.source, j.target)) if (!path.isEmpty()) {
                                Path world = savePath(saves, path);
                                worlds.computeIfAbsent(world, ignored -> new CompletableFuture<>());
                                HandoffJournal old = latest.get(world);
                                if (old == null || (old.session.equals(j.session) ? j.epoch > old.epoch || (j.epoch == old.epoch && times.get(j) > times.get(old)) : times.get(j) > times.get(old))) latest.put(world, j);
                            }
                        }
                    }
                    // Register every affected path before allowing any native opening.
                    Map<Path, List<CompletableFuture<Void>>> checks = new HashMap<>();
                    for (HandoffJournal j : pending) {
                        String protectedPath = "SOURCE".equals(j.role) ? j.source : j.target;
                        if (!protectedPath.isEmpty() && latest.get(savePath(saves, protectedPath)) != j) continue;
                        CompletableFuture<Void> resolved = new CompletableFuture<>();
                        for (String path : Arrays.asList(j.source, j.target)) if (!path.isEmpty())
                            checks.computeIfAbsent(savePath(saves, path), ignored -> new ArrayList<>()).add(resolved);
                        workers.execute(() -> {
                            try { recover(j, saves, grants, modernLock); resolved.complete(null); }
                            catch (Exception failure) {
                                org.slf4j.LoggerFactory.getLogger("peercraft").warn("[Handoff] Recovery unconfirmed; attempt {} retained", j.offer, failure);
                                resolved.completeExceptionally(failure);
                            }
                        });
                    }
                    for (Map.Entry<Path, List<CompletableFuture<Void>>> entry : checks.entrySet()) {
                        CompletableFuture<Void> gate = worlds.get(entry.getKey());
                        CompletableFuture.allOf(entry.getValue().toArray(new CompletableFuture<?>[0])).whenComplete((ok, error) -> {
                            if (error == null) gate.complete(null); else gate.completeExceptionally(error);
                        });
                    }
                }
                scanned.complete(null);
            } catch (Exception corrupt) {
                // A corrupt record has no trustworthy path. Do not guess which world it protects.
                scanned.completeExceptionally(corrupt);
            } finally { workers.shutdown(); }
        }, "PeerCraft-Handoff-Journal-Scan"); scan.setDaemon(true); scan.start();
    }
    private static Path savePath(Path saves, String path) throws IOException {
        Path root = saves.toAbsolutePath().normalize(), world = Paths.get(path).toAbsolutePath().normalize();
        if (!root.equals(world.getParent()) || Files.isSymbolicLink(world)) throw new IOException("Journal world outside saves");
        return world;
    }
    static void recover(HandoffJournal j, Path saves, GrantWriter grants, boolean modernLock) throws IOException {
        if (j.phase == HandoffJournal.Phase.ABORTED) { cleanupAbort(j, saves); return; }
        if (j.authorityHost.isEmpty() || j.authorityPort < 1 || j.authorityPort > 65535 || j.role.isEmpty())
            throw new IOException("Legacy journal needs explicit recovery; authority not recorded");
        try (Rpc rpc = new Rpc(j.authorityHost, j.authorityPort)) {
            HandoffOperation operation = new HandoffOperation(rpc.client::call, j);
            Message answer = operation.query();
            if (answer.state == ABORTED) {
                if (j.phase.ordinal() >= HandoffJournal.Phase.COMMITTED.ordinal() && j.phase != HandoffJournal.Phase.ABORTED)
                    throw new IOException("Authority conflicts with committed journal");
                if (j.phase != HandoffJournal.Phase.ABORTED) j.advance(HandoffJournal.Phase.ABORTED);
                cleanupAbort(j, saves); return; // Only scratch for a confirmed ABORT is removed.
            }
            if (answer.state == PENDING || answer.state == STAGED) {
                if (!operation.abort()) throw new IOException("Cancellation outcome is unconfirmed");
                cleanupAbort(j, saves); return;
            }
            if (answer.state != COMMITTED && answer.state != ROOM_READY) throw new IOException("Unknown handoff outcome");
            if ("SOURCE".equals(j.role)) {
                operation.resolve();
                PeercraftWorldMeta.markHandedOff(savePath(saves, j.source), "");
                return; // Stale copy remains explicitly openable after its warning.
            }
            if ("OBSERVER".equals(j.role)) return;
            if (!"SUCCESSOR".equals(j.role)) throw new IOException("Unknown journal role");
            Path target = savePath(saves, j.target);
            if (!answer.ownsCurrentEpoch && j.phase.ordinal() >= HandoffJournal.Phase.INSTALLED.ordinal()) {
                PeercraftWorldMeta.markHandedOff(target, ""); return;
            }
            operation.requireCommitted();
            if (j.phase.ordinal() < HandoffJournal.Phase.INSTALLED.ordinal()) {
                Path staging = savePath(saves, j.staging);
                Path placement = saves.resolve(staging.getFileName().toString() + ".install");
                if (Files.exists(placement, LinkOption.NOFOLLOW_LINKS)) {
                    WorldInstall.recover(saves, placement);
                } else if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                    PeercraftWorldMeta meta = PeercraftWorldMeta.loadOrNull(staging);
                    if (meta == null || meta.worldId().isEmpty()) throw new IOException("Staging world identity missing");
                    WorldTargetPlan plan = WorldTargetPlan.selected(saves, target, savePath(saves, j.backup), j.keepBackup, meta.worldId());
                    plan.install(staging, operation, modernLock);
                } else if (!WorldInstall.isInstalledSnapshot(target, staging)) {
                    throw new IOException("Committed staging is missing; keep world closed");
                }
                operation.installed();
            }
            grants.write(target, j.session, j.epoch, j.key);
        }
    }
    private static void cleanupAbort(HandoffJournal j, Path saves) throws IOException {
        String attempt = j.session + "-" + Long.toHexString(j.offer);
        Path staging = saves.resolve(".peercraft-handoff-staging-" + attempt);
        if (!j.staging.isEmpty() && !savePath(saves, j.staging).equals(staging.toAbsolutePath().normalize()))
            throw new IOException("Unexpected scratch identity");
        if (Files.exists(saves.resolve(staging.getFileName() + ".install"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("ABORT conflicts with a placement journal; retain files");
        if (Files.isSymbolicLink(staging)) throw new IOException("Scratch directory is a symlink");
        WorldInstall.delete(staging);
        Path root = j.path().toAbsolutePath().normalize().getParent();
        if (!j.archive.isEmpty()) {
            Path archive = Paths.get(j.archive).toAbsolutePath().normalize();
            if (!root.equals(archive.getParent()) || (!archive.getFileName().toString().equals(attempt + ".zip")
                    && !archive.getFileName().toString().equals("peercraft-handoff-" + attempt + ".zip")))
                throw new IOException("Unexpected archive identity");
            if (Files.isSymbolicLink(archive)) throw new IOException("Scratch archive is a symlink");
            Files.deleteIfExists(archive);
        }
    }
    public static boolean deferOpen(Path world, Consumer<Runnable> render, Runnable retry, Consumer<Throwable> failed) {
        synchronized (HandoffNetworkRecovery.class) { if (!started) SafeHandoffPlatform.INSTANCE.recoverJournals(); }
        CompletableFuture<Void> gate = scanned.thenCompose(ignored -> worlds.getOrDefault(world.toAbsolutePath().normalize(), CompletableFuture.completedFuture(null)));
        if (gate.isDone() && !gate.isCompletedExceptionally()) return false;
        gate.whenComplete((ok, error) -> render.accept(() -> { if (error == null) retry.run(); else failed.accept(error); }));
        return true;
    }
    private static final class Rpc implements AutoCloseable {
        final DatagramSocket socket; final HandoffAuthorityClient client; final Thread reader;
        Rpc(String host, int port) throws IOException {
            InetAddress address = InetAddress.getByName(host); socket = new DatagramSocket(); socket.setSoTimeout(1000);
            client = new HandoffAuthorityClient((to, remotePort, bytes) -> {
                try { socket.send(new DatagramPacket(bytes, bytes.length, to, remotePort)); }
                catch (IOException ignored) { /* Bounded RPC retries resolve loss. */ }
            }, address, port);
            reader = new Thread(() -> {
                byte[] bytes = new byte[2048];
                while (!socket.isClosed()) try {
                    DatagramPacket packet = new DatagramPacket(bytes, bytes.length); socket.receive(packet);
                    client.onPacket(bytes, packet.getLength(), packet.getAddress(), packet.getPort());
                } catch (IOException failure) { if (socket.isClosed()) break; }
            }, "PeerCraft-Handoff-Recovery-UDP"); reader.setDaemon(true); reader.start();
        }
        public void close() { client.close(); socket.close(); }
    }
}
