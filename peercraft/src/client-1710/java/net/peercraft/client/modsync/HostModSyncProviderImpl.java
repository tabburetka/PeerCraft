package net.peercraft.client.modsync;

// Forge 1.7.10 backport of src/main/.../client/modsync/HostModSyncProviderImpl.java —
// arrow-switch expression -> colon switch; List.of()/List.copyOf() -> Collections.
// Keep in sync with the original.

import net.peercraft.network.modsync.ModEntry;
import net.peercraft.network.modsync.ModSyncFilter;
import net.peercraft.network.modsync.ModSyncHostProvider;
import net.peercraft.network.modsync.ModSyncProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Host side of the mod-sync seam: every {@code *.jar} file in the host's {@code mods/} folder,
 * with its real size + SHA-512 (hashed on a background thread so world load isn't blocked) and
 * a stream of any one of them. Built from {@code OpenToLanMixin} when {@code peercraft.modSync}
 * is on.
 */
public final class HostModSyncProviderImpl implements ModSyncHostProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final long HASH_WAIT_MILLIS = 60_000;

    private final Path modsDir;
    private final Path servingDir;
    private final CountDownLatch hashed = new CountDownLatch(1);
    private volatile List<ModEntry> cached = Collections.emptyList();
    private final Map<String, Path> jarById = new LinkedHashMap<>();

    private HostModSyncProviderImpl(Path modsDir) {
        this.modsDir = modsDir;
        this.servingDir = ModSyncFilesystem.servingDir(modsDir);
    }

    /** Kicks off hashing straight away; the coordinator's {@link #hostMods()} waits on it. */
    public static HostModSyncProviderImpl start(Path modsDir) {
        final HostModSyncProviderImpl p = new HostModSyncProviderImpl(modsDir);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                p.hashAll();
            }
        }, "PeerCraft-ModSync-Hash");
        t.setDaemon(true);
        t.start();
        return p;
    }

    private void hashAll() {
        List<ModEntry> out = new ArrayList<>();
        int skipped = 0;
        for (ModJarScanner.ScannedJar sj : ModJarScanner.scan(modsDir)) {
            String envStr;
            switch (sj.env()) {
                case CLIENT:
                    envStr = "client";
                    break;
                case SERVER:
                    envStr = "server";
                    break;
                default:
                    envStr = "both";
                    break;
            }
            if (ModSyncFilter.isExcluded(sj.id(), envStr, false, sj.fileName())) {
                skipped++;
                continue;
            }
            try {
                byte[] sha = ModSyncFilesystem.hashFile(sj.jarPath());
                long size = Files.size(sj.jarPath());
                out.add(new ModEntry(sj.id(), sj.version(), size, sha, sj.fileName(), sj.env(), "", ""));
                jarById.put(sj.id(), sj.jarPath());
            } catch (IOException e) {
                LOGGER.warn("[ModSync] Не удалось захешировать {}: {}", sj.fileName(), e.toString());
            }
        }
        cached = Collections.unmodifiableList(new ArrayList<>(out));
        hashed.countDown();
        LOGGER.info("[ModSync] Готово {} модов для отдачи заходящим ({} пропущено как загрузчик/PeerCraft).", cached.size(), skipped);
    }

    @Override
    public ModSyncProtocol.Loader loader() {
        return LoaderTag.current();
    }

    @Override
    public List<ModEntry> hostMods() {
        try {
            if (!hashed.await(HASH_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                LOGGER.warn("[ModSync] Хеширование модов не завершилось за {} мс — отдаём то, что успели.", HASH_WAIT_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return cached;
    }

    @Override
    public InputStream openJar(String modId) throws IOException {
        Path jar = jarById.get(modId);
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new IOException("no serveable jar for " + modId);
        }
        return Files.newInputStream(jar);
    }

    @Override
    public Path servingTempDir() {
        return servingDir;
    }
}
