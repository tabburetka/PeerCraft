package net.peercraft.client.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.peercraft.client.modsync.InstalledModScanner;
import net.peercraft.config.PeerCraftConfig;
import net.peercraft.config.PeerCraftSettings;
import net.peercraft.config.PeerCraftSettingsStore;
import net.peercraft.platform.services.PlatformMod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Once per launch, reads {@code update.json} from the PeerCraft repository and remembers whether
 * a newer release exists. The title-screen notice polls {@link #available()} every frame, so the
 * result may arrive after the menu is already open. Failures are silent: no notice, one debug line.
 *
 * <p>Java-8 clean ({@link HttpURLConnection}, old Gson API): shared with the Forge backports.
 */
public final class UpdateChecker {
    private static final Logger LOGGER = LogManager.getLogger("peercraft");
    public static final String DEFAULT_PAGE = "https://www.curseforge.com/minecraft/mc-mods/peercraft/files/all";
    private static final int TIMEOUT_MILLIS = 5000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile UpdateInfo result;
    private static volatile String dismissedVersion;

    private UpdateChecker() {
    }

    public static final class UpdateInfo {
        public final String current;
        public final String latest;
        public final String url;
        /** When the result became known, for the notice's fade-in. */
        public final long foundAtMillis;

        UpdateInfo(String current, String latest, String url, long foundAtMillis) {
            this.current = current;
            this.latest = latest;
            this.url = url;
            this.foundAtMillis = foundAtMillis;
        }
    }

    public static void startOnce() {
        if (!PeerCraftConfig.updateCheckEnabled() || !STARTED.compareAndSet(false, true)) return;
        try {
            dismissedVersion = PeerCraftSettingsStore.load().updateNoticeDismissedVersion;
        } catch (RuntimeException ignored) {
            dismissedVersion = null;
        }
        Thread thread = new Thread(UpdateChecker::run, "PeerCraft-UpdateCheck");
        thread.setDaemon(true);
        thread.start();
    }

    /** The newer release to advertise, or null when there is none, it was dismissed, or checks are off. */
    public static UpdateInfo available() {
        if (!PeerCraftConfig.updateCheckEnabled()) return null;
        startOnce();
        UpdateInfo info = result;
        if (info == null || info.latest.equals(dismissedVersion)) return null;
        return info;
    }

    /** Hides the notice until a version newer than the one shown is published. */
    public static void dismiss() {
        UpdateInfo info = result;
        if (info == null) return;
        dismissedVersion = info.latest;
        try {
            PeerCraftSettings settings = PeerCraftSettingsStore.load();
            settings.updateNoticeDismissedVersion = info.latest;
            PeerCraftSettingsStore.save(settings);
        } catch (RuntimeException e) {
            LOGGER.debug("[PeerCraft] Update notice dismissal not saved: {}", e.toString());
        }
    }

    private static void run() {
        String self = selfVersion();
        ModVersion current = ModVersion.parse(self);
        if (current == null) {
            LOGGER.debug("[PeerCraft] Update check skipped: unknown own version '{}'", self);
            return;
        }
        String url = PeerCraftConfig.updateCheckUrl();
        try {
            String body = fetch(url, self);
            JsonElement root = new JsonParser().parse(body);
            if (!root.isJsonObject()) return;
            JsonObject json = root.getAsJsonObject();
            ModVersion latest = pick(current, ModVersion.parse(string(json, "stable")), ModVersion.parse(string(json, "beta")));
            if (latest == null) return;
            result = new UpdateInfo(current.toString(), latest.toString(), safePage(string(json, "url")),
                    System.currentTimeMillis());
            LOGGER.info("[PeerCraft] Доступна новая версия {} (установлена {})", latest, current);
        } catch (Exception e) {
            LOGGER.debug("[PeerCraft] Update check failed: {}", e.toString());
        }
    }

    /**
     * The version to advertise, or null when {@code current} is already up to date. Pre-releases
     * are only offered to players who are themselves on a pre-release.
     */
    static ModVersion pick(ModVersion current, ModVersion stable, ModVersion beta) {
        ModVersion best = stable;
        if (current.isPrerelease() && beta != null && (best == null || beta.compareTo(best) > 0)) best = beta;
        if (best == null || best.compareTo(current) <= 0) return null;
        return best;
    }

    /** Only links PeerCraft publishes to are opened; anything else falls back to the CurseForge page. */
    static String safePage(String url) {
        if (url != null && (url.startsWith("https://www.curseforge.com/")
                || url.startsWith("https://github.com/tabburetka/")) && url.indexOf(' ') < 0) {
            return url;
        }
        return DEFAULT_PAGE;
    }

    private static String fetch(String url, String self) throws Exception {
        boolean custom = !PeerCraftConfig.DEFAULT_UPDATE_CHECK_URL.equals(url);
        if (!url.startsWith("https://") && !(custom && url.startsWith("http://"))) {
            throw new IllegalArgumentException("unsupported update URL");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "PeerCraft/" + self + " (github.com/tabburetka/PeerCraft)");
            int code = connection.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[4096];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    if (bytes.size() + count > MAX_RESPONSE_BYTES) throw new IllegalStateException("response too large");
                    bytes.write(chunk, 0, count);
                }
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String selfVersion() {
        try {
            for (PlatformMod mod : InstalledModScanner.allInstalled()) {
                if ("peercraft".equals(mod.id())) return mod.version();
            }
        } catch (RuntimeException ignored) {
        }
        return "";
    }
}
