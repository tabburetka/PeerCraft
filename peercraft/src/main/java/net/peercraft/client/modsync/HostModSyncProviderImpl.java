package net.peercraft.client.modsync;

import net.peercraft.config.ModSyncMode;
import net.peercraft.network.modsync.ModEntry;
import net.peercraft.network.modsync.ModSyncFilter;
import net.peercraft.network.modsync.ModSyncHostProvider;
import net.peercraft.network.modsync.ModSyncProtocol;
import net.peercraft.platform.services.PlatformMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Host side of the mod-sync seam: every {@code *.jar} file in the host's {@code mods/} folder,
 * with its real size + SHA-512 (hashed on a background thread so world load isn't blocked) and
 * a stream of any one of them. Built from {@code OpenToLanMixin} when {@code peercraft.modSync}
 * is on. Jar-file enumeration (not the loader's mod list) so Sinytra Connector's relocated
 * mods and jar-in-jar libraries are handled correctly — the joiner needs the literal jar set.
 */
public final class HostModSyncProviderImpl implements ModSyncHostProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final long HASH_WAIT_MILLIS = 60_000;

    private final Path modsDir;
    private final Path servingDir;
    private final ModSyncMode mode;
    private final CountDownLatch hashed = new CountDownLatch(1);
    private volatile List<ModEntry> cached = List.of();
    private final Map<String, Path> jarById = new LinkedHashMap<>();

    private HostModSyncProviderImpl(Path modsDir, ModSyncMode mode) {
        this.modsDir = modsDir;
        this.mode = mode;
        this.servingDir = ModSyncFilesystem.servingDir(modsDir);
    }

    /** Kicks off hashing straight away; the coordinator's {@link #hostMods()} waits on it. */
    public static HostModSyncProviderImpl start(Path modsDir, ModSyncMode mode) {
        HostModSyncProviderImpl p = new HostModSyncProviderImpl(modsDir, mode);
        Thread t = new Thread(p::hashAll, "PeerCraft-ModSync-Hash");
        t.setDaemon(true);
        t.start();
        return p;
    }

    private void hashAll() {
        List<ModEntry> out = new ArrayList<>();
        int skipped = 0;
        for (ModJarScanner.ScannedJar sj : ModJarScanner.scan(modsDir)) {
            String envStr = switch (sj.env()) {
                case CLIENT -> "client";
                case SERVER -> "server";
                default -> "both";
            };
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

        // "Только обязательные моды": share only what a joiner needs to enter the world. The jar's
        // own fabric.mod.json "environment" is a weak signal — many client-only mods (AppleSkin, …)
        // declare "*" for a trivial server component — so refine each mod's side through Modrinth
        // (the same source the joiner's confirm screen trusts), then withhold the client-only ones.
        // No network calls in ALL / OFF mode.
        int clientOnlyWithheld = 0;
        if (mode == ModSyncMode.REQUIRED && !out.isEmpty()) {
            Map<String, ModEntry.Env> refined = new ModrinthClient(selfVersion()).refineEnvByModId(out);
            List<ModEntry> required = new ArrayList<>(out.size());
            for (ModEntry e : out) {
                ModEntry.Env env = refined.getOrDefault(e.id(), e.env());
                if (env == ModEntry.Env.CLIENT) {
                    jarById.remove(e.id());
                    clientOnlyWithheld++;
                    continue;
                }
                required.add(env == e.env() ? e : withEnv(e, env));
            }
            out = required;
        }

        cached = List.copyOf(out);
        hashed.countDown();
        LOGGER.info("[ModSync] Готово {} модов для отдачи заходящим (режим {}, {} пропущено как загрузчик/PeerCraft, {} придержано как только клиентские).",
                cached.size(), mode.key(), skipped, clientOnlyWithheld);
    }

    private static ModEntry withEnv(ModEntry e, ModEntry.Env env) {
        return new ModEntry(e.id(), e.version(), e.sizeBytes(), e.sha512(), e.fileName(), env, e.homepageUrl(), e.sourcesUrl());
    }

    private static String selfVersion() {
        for (PlatformMod pm : InstalledModScanner.allInstalled()) {
            if ("peercraft".equals(pm.id())) {
                return pm.version();
            }
        }
        return "2.1.0";
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
