package net.peercraft.client.update;

import net.peercraft.client.modsync.InstalledModScanner;
import net.peercraft.client.modsync.ModSyncPendingInstaller;
import net.peercraft.modsync.install.ModSyncInstaller;
import net.peercraft.platform.Services;
import net.peercraft.platform.services.PlatformMod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads this build's jar from CurseForge's CDN (as listed in a signature-verified
 * {@code update.json}), checks its size and SHA-512, and hands it to the Mod Sync helper, which
 * swaps the jars after the game exits with the usual backup and rollback. Java-8 clean.
 */
public final class SelfUpdateInstaller {
    private static final Logger LOGGER = LogManager.getLogger("peercraft");
    private static final int TIMEOUT_MILLIS = 15000;
    private static final long DEADLINE_MILLIS = 5L * 60 * 1000;
    private static final int MAX_REDIRECTS = 3;

    public enum State { IDLE, DOWNLOADING, READY_RESTART, FAILED }

    /** Why the download button is unavailable; the screen shows the matching lang key. */
    public enum Blocker {
        NONE, NO_BUILD, NOT_IN_MODS, PENDING_OPERATION;

        public String langKey() {
            return "peercraft.update.blocked." + name().toLowerCase(Locale.ROOT);
        }
    }

    private static volatile State state = State.IDLE;
    private static volatile String failure = "";
    private static volatile String readyVersion;
    private static final AtomicLong received = new AtomicLong();
    private static volatile long total;
    private static final AtomicBoolean cancelled = new AtomicBoolean();

    private SelfUpdateInstaller() {
    }

    public static State state() { return state; }
    public static String failure() { return failure; }
    public static String readyVersion() { return readyVersion; }

    /** 0..1 while downloading. */
    public static float progress() {
        long size = total;
        return size <= 0 ? 0f : Math.min(1f, received.get() / (float) size);
    }

    public static Blocker blocker(UpdateChecker.UpdateInfo info) {
        if (info == null || info.file == null) return Blocker.NO_BUILD;
        if (currentJar() == null) return Blocker.NOT_IN_MODS;
        try {
            if (ModSyncPendingInstaller.hasPending()) return Blocker.PENDING_OPERATION;
        } catch (RuntimeException e) {
            return Blocker.PENDING_OPERATION;
        }
        return Blocker.NONE;
    }

    public static synchronized boolean start(final UpdateChecker.UpdateInfo info) {
        if (state == State.DOWNLOADING || state == State.READY_RESTART || blocker(info) != Blocker.NONE) return false;
        state = State.DOWNLOADING;
        failure = "";
        received.set(0);
        total = info.file.size;
        cancelled.set(false);
        Thread thread = new Thread(() -> run(info), "PeerCraft-SelfUpdate");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    public static void cancel() {
        cancelled.set(true);
    }

    private static void run(UpdateChecker.UpdateInfo info) {
        Path dir = Services.PLATFORM.getConfigDir().resolve("peercraft/update");
        Path part = dir.resolve("download.part");
        Path jar = dir.resolve(info.file.fileName);
        try {
            Path current = currentJar();
            if (current == null) throw new IOException("PeerCraft jar is not in the mods folder");
            Files.createDirectories(dir);
            Files.deleteIfExists(part);
            download(info.file, part);
            Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
            if (cancelled.get()) throw new IOException("cancelled");
            ModSyncInstaller.Entry entry = new ModSyncInstaller.Entry("peercraft", info.file.version, info.file.fileName,
                    info.file.sha512, current.getFileName().toString(), info.current, ModSyncInstaller.hash(current), "self-update");
            ModSyncPendingInstaller.armSelfUpdate(entry, jar, cancelled::get);
            readyVersion = info.file.version;
            state = State.READY_RESTART;
            LOGGER.info("[PeerCraft] Обновление {} подготовлено, установится после выхода из игры", info.file.version);
        } catch (Exception e) {
            failure = cancelled.get() ? "cancelled" : e.getMessage() == null ? e.toString() : e.getMessage();
            state = cancelled.get() ? State.IDLE : State.FAILED;
            LOGGER.warn("[PeerCraft] Самообновление не удалось: {}", e.toString());
        } finally {
            try { Files.deleteIfExists(part); } catch (IOException ignored) { }
            try { Files.deleteIfExists(jar); } catch (IOException ignored) { }
        }
    }

    /** Removes a download left behind by a game that was killed mid-transfer. */
    static void cleanupLeftovers() {
        Path dir = Services.PLATFORM.getConfigDir().resolve("peercraft/update");
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(file);
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** The running PeerCraft jar when it is a plain file directly inside the mods folder, else null. */
    static Path currentJar() {
        try {
            Path mods = Services.PLATFORM.getModsDir().toRealPath();
            for (PlatformMod mod : InstalledModScanner.allInstalled()) {
                if (!"peercraft".equals(mod.id()) || mod.jarPath() == null) continue;
                Path jar = mod.jarPath();
                if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) return null;
                if (!jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) return null;
                Path parent = jar.toAbsolutePath().getParent();
                return parent != null && parent.toRealPath().equals(mods) ? jar : null;
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return null;
    }

    private static void download(UpdateManifest.File file, Path target) throws Exception {
        long deadline = System.currentTimeMillis() + DEADLINE_MILLIS;
        String url = file.url;
        for (int hop = 0; ; hop++) {
            HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(TIMEOUT_MILLIS);
                connection.setReadTimeout(TIMEOUT_MILLIS);
                connection.setRequestProperty("User-Agent", "PeerCraft self-update (github.com/tabburetka/PeerCraft)");
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (hop >= MAX_REDIRECTS || location == null) throw new IOException("Too many redirects");
                    url = new URL(new URL(url), location).toString();
                    if (!UpdateManifest.isCdnUrl(url)) throw new IOException("Redirect outside CurseForge CDN");
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                MessageDigest digest = MessageDigest.getInstance("SHA-512");
                long count = 0;
                try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (cancelled.get()) throw new IOException("cancelled");
                        if (System.currentTimeMillis() > deadline) throw new IOException("Download timed out");
                        count += n;
                        if (count > file.size) throw new IOException("File is larger than announced");
                        digest.update(buffer, 0, n);
                        out.write(buffer, 0, n);
                        received.set(count);
                    }
                }
                if (count != file.size) throw new IOException("Size mismatch");
                if (!file.sha512.equals(hex(digest.digest()))) throw new IOException("SHA-512 mismatch");
                return;
            } finally {
                connection.disconnect();
            }
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return out.toString();
    }
}
