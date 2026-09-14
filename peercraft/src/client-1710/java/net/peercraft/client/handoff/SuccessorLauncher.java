package net.peercraft.client.handoff;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.WorldSettings;
import net.peercraft.client.PeerCraftHostOptions;
import net.peercraft.client.gui.HandoffReclaimConfirmScreen;
import net.peercraft.client.gui.PeerCraftUi;
import net.peercraft.network.handoff.HandoffProtocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
 * Forge 1.7.10 backport of {@code src/main/.../client/handoff/SuccessorLauncher.java} (cf. the
 * 1.12.2 twin, near-mechanical from there). Deltas from 1.12.2:
 * <ul>
 *   <li>Saves root is {@code Minecraft.mcDataDir/"saves"} ({@code mcDataDir}, not {@code gameDir}).</li>
 *   <li>{@code Minecraft.player} -&gt; {@code Minecraft.thePlayer}; {@code getSingleplayerServer}/
 *       {@code getIntegratedServer} unchanged.</li>
 *   <li>{@code GameType} is nested: {@code WorldSettings.GameType}, not a top-level class.</li>
 *   <li>{@code IntegratedServer.shareToLAN(WorldSettings.GameType, boolean)} — picks its own
 *       port; {@code OpenToLanMixin} (already ported) fires on this same call.</li>
 * </ul>
 */
public final class SuccessorLauncher {

    private static final Logger LOGGER = LogManager.getLogger("peercraft");

    public interface Done {
        void serverPublished();

        void failed(String reasonKey);
    }

    private SuccessorLauncher() {
    }

    public static void launch(HandoffProtocol.Offer offer, Path worldZip, Done done) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> {
            Path staging = null;
            try {
                Path savesDir = mc.mcDataDir.toPath().resolve("saves");
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

    private static void finish(String levelId, HandoffProtocol.Offer offer, Done done) {
        if (levelId == null) {
            done.failed("peercraft.handoff.abort.transfer_failed");
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        applyHostOptions(offer);
        try {
            PeercraftWorldMeta.markBecameHost(mc.mcDataDir.toPath().resolve("saves").resolve(levelId));
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

    /** See src/main's original for the full rationale (true-singleplayer owner position). */
    private static void stripEmbeddedOwnerPosition(Path worldDir) {
        Path levelDat = worldDir.resolve("level.dat");
        if (!Files.exists(levelDat)) {
            return;
        }
        try {
            NBTTagCompound root;
            try (InputStream in = Files.newInputStream(levelDat)) {
                root = CompressedStreamTools.readCompressed(in);
            }
            NBTTagCompound data = root.getCompoundTag("Data");
            if (data.hasKey("Player")) {
                data.removeTag("Player");
                try (OutputStream out = Files.newOutputStream(levelDat)) {
                    CompressedStreamTools.writeCompressed(root, out);
                }
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
                    throw new IOException("zip entry escapes target: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Path parent = out.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (OutputStream os = Files.newOutputStream(out)) {
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
        mc.launchIntegratedServer(levelId, levelId, null);

        Thread wait = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 60_000L;
            while (System.currentTimeMillis() < deadline) {
                IntegratedServer server = mc.getIntegratedServer();
                if (server != null && server.isServerRunning() && mc.thePlayer != null) {
                    mc.func_152344_a(() -> publish(server, done));
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
            WorldSettings.GameType gameType = server.getGameType();
            String returned = server.shareToLAN(gameType, false);
            int port = parsePort(returned);
            if (port > 0) {
                LOGGER.info("[Handoff] Мир открыт для сети на порту {} — регистрируем комнату как новый хост", port);
                done.serverPublished();
            } else {
                done.failed("peercraft.handoff.abort.transfer_failed");
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[Handoff] shareToLAN у нового хоста не удался: {}", e.toString());
            done.failed("peercraft.handoff.abort.transfer_failed");
        }
    }

    private static int parsePort(String returned) {
        if (returned == null) {
            return -1;
        }
        try {
            return Integer.parseInt(returned.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
