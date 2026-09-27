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
 * Turns a handoff successor into the new host: unpack the world archive, then drive the normal
 * "Open to LAN + play over the internet" path so {@code OpenToLanMixin} registers a fresh room.
 *
 * <p>If the archive is a world this machine already has ({@code peercraft-world.json}'s
 * {@code worldId} matches an existing save), it's a world coming back — the player is asked to
 * confirm an in-place update (the old copy is renamed to a dated backup first). Otherwise it
 * lands in a new {@code <name>-peercraft} folder.
 *
 * <p>The world-open + {@code publishServer} calls are the most version-sensitive part — the
 * shapes here target 1.21.x, Stonecutter-switched where the boundary is known.
 */
public final class SuccessorLauncher {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    public interface Done {
        void serverPublished();

        void failed(String reasonKey);
    }

    private SuccessorLauncher() {
    }

    public static void launch(HandoffProtocol.Offer offer, Path worldZip, Done done) {
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
    }

    /** Common tail: apply host options, stamp the world, open + publish. {@code levelId} = folder name, or null on error. */
    private static void finish(String levelId, HandoffProtocol.Offer offer, Done done) {
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
        net.peercraft.network.p2p.P2PBridge.INSTANCE.prepareHandoffRoom(offer.offerId());
        mc.execute(() -> openThenPublish(mc, levelId, new Done() {
            private final java.util.concurrent.atomic.AtomicBoolean terminal = new java.util.concurrent.atomic.AtomicBoolean();
            public void serverPublished() {
                net.peercraft.network.p2p.P2PBridge.INSTANCE.awaitHandoffRoom(offer.offerId(),
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

    /** Rename the old copy to a dated backup, then move staging into its place. Returns the (unchanged) level id. */
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
                    savesDir.resolve(staging.getFileName().toString() + ".install"), keep);
            if (keep) Files.write(backup.resolve(".peercraft-backup"), new byte[0]);
            return savesDir.relativize(existing).toString();
        } catch (IOException e) {
            LOGGER.warn("[Handoff] Placement failed; retaining journal and world copies: {}", e.toString());
            return null;
        }
    }

    private static String freshFolder(Path savesDir, Path staging, String label) {
        String base = sanitize(label == null || label.isBlank() ? "world" : label);
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
        // WorldOpenFlows.openWorld(String, Runnable) — present on 1.20.2+ incl. 1.21.x / 26.x.
        mc.createWorldOpenFlows().openWorld(levelId, () -> done.failed("peercraft.handoff.abort.transfer_failed"));

        Thread wait = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 180_000L;
            while (System.currentTimeMillis() < deadline) {
                IntegratedServer server = mc.getSingleplayerServer();
                // isRunning() alone isn't enough: it flips true as soon as the server thread
                // starts ticking, which can happen before the client's own join sequence has
                // finished setting up Minecraft.player for this world — and vanilla's
                // publishServer() dereferences that player, throwing an NPE if called too
                // early (seen in testing: "Cannot invoke ...player.getGameProfile() because
                // ...player is null"). Wait for both.
                if (server != null && server.isRunning() && mc.player != null) {
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
        try {
            int port = net.minecraft.util.HttpUtil.getAvailablePort();
            GameType gameType = server.getWorldData().getGameType();
            //? if <26.2
            boolean ok = server.publishServer(gameType, false, port);
            //? if >=26.2
            /*boolean ok = server.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, port);*/
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
