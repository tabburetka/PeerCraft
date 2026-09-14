package net.peercraft.client.handoff;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
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
        mc.execute(() -> {
            Path staging = null;
            try {
                Path savesDir = mc.getLevelSource().getBaseDir();
                Files.createDirectories(savesDir);
                staging = savesDir.resolve(".peercraft-handoff-staging-" + offer.offerId());
                deleteRecursive(staging);
                unzipInto(worldZip, staging);
                Files.deleteIfExists(staging.resolve("session.lock"));
                stripEmbeddedOwnerPosition(staging);

                String incomingId = readWorldId(staging);
                Path existing = incomingId.isEmpty() ? null : findWorldById(savesDir, incomingId, staging);

                if (existing != null) {
                    String existingName = savesDir.relativize(existing).toString();
                    String backupName = existingName + " (до возврата " + timestamp() + ")";
                    Path st = staging;
                    PeerCraftUi.setScreen(mc, new HandoffReclaimConfirmScreen(existingName, backupName,
                            () -> finish(reclaimInPlace(savesDir, existing, st, backupName), offer, done),
                            () -> finish(overwriteInPlace(savesDir, existing, st), offer, done)));
                } else {
                    finish(freshFolder(savesDir, staging, offer.worldLabel()), offer, done);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[Handoff] Не удалось запустить мир как новый хост: {}", e.toString());
                if (staging != null) {
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
        openThenPublish(mc, levelId, done);
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
        try {
            Files.move(existing, savesDir.resolve(backupName), StandardCopyOption.ATOMIC_MOVE);
            Files.move(staging, existing);
            LOGGER.info("[Handoff] Локальная копия «{}» обновлена (бэкап: {})", savesDir.relativize(existing), backupName);
            return savesDir.relativize(existing).toString();
        } catch (IOException e) {
            LOGGER.warn("[Handoff] Обновление на месте не удалось ({}), кладём в новую папку", e.toString());
            return freshFolder(savesDir, staging, savesDir.relativize(existing).toString());
        }
    }

    /** Deletes the old copy outright (no backup — the player confirmed this on a warning screen first), then moves staging into its place. */
    private static String overwriteInPlace(Path savesDir, Path existing, Path staging) {
        try {
            deleteRecursive(existing);
            Files.move(staging, existing);
            LOGGER.info("[Handoff] Локальная копия «{}» перезаписана без бэкапа", savesDir.relativize(existing));
            return savesDir.relativize(existing).toString();
        } catch (IOException e) {
            LOGGER.warn("[Handoff] Перезапись не удалась ({}), кладём в новую папку", e.toString());
            return freshFolder(savesDir, staging, savesDir.relativize(existing).toString());
        }
    }

    /** Move staging into a fresh, unique {@code <base>-peercraft} folder. */
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

    private static Path findWorldById(Path savesDir, String worldId, Path staging) {
        try (Stream<Path> dirs = Files.list(savesDir)) {
            return dirs.filter(Files::isDirectory)
                    .filter(p -> !p.equals(staging))
                    .filter(p -> {
                        PeercraftWorldMeta m = PeercraftWorldMeta.loadOrNull(p);
                        return m != null && worldId.equals(m.worldId());
                    })
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String readWorldId(Path worldDir) {
        PeercraftWorldMeta m = PeercraftWorldMeta.loadOrNull(worldDir);
        return m == null || m.worldId() == null ? "" : m.worldId();
    }

    /**
     * True singleplayer stores the local player's position directly in level.dat's
     * {@code Data.Player} compound, not in {@code playerdata/<uuid>.dat} — and
     * {@code MinecraftServer.isSingleplayerOwner(...)} treats WHOEVER's local session opens a
     * world this way as "the owner", regardless of account identity. Left in place, every
     * successor (and every original host reclaiming a returned world) would spawn standing
     * exactly where the previous owner last stood instead of at their own position or the
     * world spawn. Strip it so the normal per-account playerdata path is used instead.
     */
    private static void stripEmbeddedOwnerPosition(Path worldDir) {
        Path levelDat = worldDir.resolve("level.dat");
        if (!Files.exists(levelDat)) {
            return;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
            //? if <1.21.5
            CompoundTag data = root.getCompound("Data");
            //? if >=1.21.5
            /*CompoundTag data = root.getCompoundOrEmpty("Data");*/
            if (data.contains("Player")) {
                data.remove("Player");
                NbtIo.writeCompressed(root, levelDat);
                LOGGER.info("[Handoff] Убрана встроенная позиция предыдущего владельца из level.dat ({})", levelDat);
            } else {
                LOGGER.info("[Handoff] В level.dat нет встроенной позиции игрока — нечего убирать ({})", levelDat);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Handoff] Не удалось очистить встроенную позицию в level.dat: {}", e.toString());
        }
    }

    // ---- zip ----

    private static void unzipInto(Path worldZip, Path target) throws IOException {
        Files.createDirectories(target);
        byte[] buf = new byte[1 << 16];
        try (InputStream fin = Files.newInputStream(worldZip);
             ZipInputStream zis = new ZipInputStream(fin)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path out = target.resolve(e.getName()).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("zip entry escapes target: " + e.getName()); // zip-slip guard
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Path parent = out.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (var os = Files.newOutputStream(out)) {
                        int r;
                        while ((r = zis.read(buf)) > 0) {
                            os.write(buf, 0, r);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
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
            long deadline = System.currentTimeMillis() + 60_000L;
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
