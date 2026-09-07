package net.peercraft.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public final class PeerCraftConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    public static final String MODE_AUTO = "auto";
    public static final String MODE_CLIENT = "client";
    public static final String MODE_HOST = "host";
    public static final String MODE_DISABLED = "disabled";

    private static final String PROPERTY_PREFIX = "peercraft.";
    private static final String ENV_PREFIX = "PEERCRAFT_";

    // In-memory override layer fed from config/peercraft/settings.json (the in-game PeerCraft
    // Settings screen) at client init via applyOverrides(). Resolved BELOW -Dpeercraft.* /
    // PEERCRAFT_* (an explicit launch flag always wins) but ABOVE the baked defaults and the
    // hardcoded fallback. Empty until applyOverrides() runs, so a server / test with no
    // settings.json behaves exactly as before.
    private static volatile Map<String, String> overrides = Collections.emptyMap();

    /** Replaces the settings.json override layer. Called at client init and again from the Settings screen's Save. */
    public static void applyOverrides(Map<String, String> map) {
        overrides = (map == null || map.isEmpty())
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(map));
    }

    // Optional baked-in overrides shipped as a classpath resource. Absent from a normal build;
    // the "-DEVELOP" jars carry one (rendezvousHost=127.0.0.1 + shifted local ports) so a
    // second game instance on the same machine talks to a local rendezvous server with no
    // launch flags. Read below -Dpeercraft.* / PEERCRAFT_* but above the hardcoded defaults.
    private static final String BAKED_DEFAULTS_RESOURCE = "/peercraft-defaults.properties";
    private static final Properties BAKED_DEFAULTS = loadBakedDefaults();

    private PeerCraftConfig() {
    }

    private static Properties loadBakedDefaults() {
        Properties props = new Properties();
        try (InputStream in = PeerCraftConfig.class.getResourceAsStream(BAKED_DEFAULTS_RESOURCE)) {
            if (in != null) {
                props.load(in);
                LOGGER.info("[PeerCraftConfig] Загружен встроенный конфиг {} ({} ключей) — это DEVELOP-сборка.",
                        BAKED_DEFAULTS_RESOURCE, props.size());
            }
        } catch (Exception e) {
            LOGGER.warn("[PeerCraftConfig] Не удалось прочитать {}: {}", BAKED_DEFAULTS_RESOURCE, e.toString());
        }
        return props;
    }

    public static String mode() {
        String mode = stringValue("mode", MODE_AUTO).toLowerCase();
        if (MODE_AUTO.equals(mode) || MODE_CLIENT.equals(mode) || MODE_HOST.equals(mode) || MODE_DISABLED.equals(mode)) {
            return mode;
        }
        LOGGER.warn("[PeerCraftConfig] Неизвестное значение peercraft.mode='{}' (допустимые: auto/client/host/disabled), использую '{}' по умолчанию", mode, MODE_AUTO);
        return MODE_AUTO;
    }

    public static int proxyPort() {
        return intValue("proxyPort", 25566);
    }

    public static int clientUdpPort() {
        return intValue("clientUdpPort", 50002);
    }

    public static int hostUdpPort() {
        return intValue("hostUdpPort", 50001);
    }

    public static String peerHost() {
        return stringValue("peerHost", "127.0.0.1");
    }

    public static int peerPortForClient() {
        return intValue("peerPort", hostUdpPort());
    }

    public static int peerPortForHost() {
        return intValue("peerPort", clientUdpPort());
    }

    // Master opt-in for internet play (rendezvous server + UDP hole punching) instead of
    // the static peerHost/peerPort path. false leaves all existing local behavior unchanged.
    public static boolean internetPlay() {
        return boolValue("internetPlay", false);
    }

    public static String rendezvousHost() {
        return stringValue("rendezvousHost", "91.146.31.165");
    }

    public static int rendezvousPort() {
        return intValue("rendezvousPort", 51000);
    }

    // Joiner-only: room code obtained from the host out-of-band (e.g. shared via chat/Discord).
    public static String roomCode() {
        return stringValue("roomCode", "");
    }

    // Host-only: default maximum number of players for a room opened via the rendezvous
    // path — the GUI (ShareToLanScreenMixin) lets the host override this per-session; this
    // is just the initial/launch-flag value. Range must match RoomRegistry's own clamp on
    // the server side ([1, 32]) so a launch-flag override can't silently get clamped away.
    public static int maxPlayers() {
        return intValueInRange("maxPlayers", 4, 1, 32);
    }

    // Mod sync: when joining a friend's world, offer to download server-side mods the joiner
    // is missing (then restart). Master switch — false disables the whole handshake, join
    // behaves exactly as before.
    public static boolean modSync() {
        return boolValue("modSync", true);
    }

    // Skip the "these mods will be downloaded" confirmation screen and install straight away.
    // Opt-in — only for a closed group of trusted friends on a private rendezvous server.
    public static boolean modSyncAutoAccept() {
        return boolValue("modSync.autoAccept", false);
    }

    // Cap on the combined size of one mod-sync batch, in MiB — the plan is rejected before any
    // download starts if the manifest totals more than this.
    public static int modSyncMaxTotalMb() {
        return intValueInRange("modSync.maxTotalMb", 512, 1, 4096);
    }

    // Cap on any single downloaded jar, in MiB — enforced against the manifest size up front
    // and against the actual byte count during transfer.
    public static int modSyncMaxModMb() {
        return intValueInRange("modSync.maxModMb", 256, 1, 2048);
    }

    // The confirm screen normally stays hidden on re-join once every missing mod is one the
    // player already made a call on (client-side mods they unchecked are remembered in
    // config/peercraft/modsync-declined.json). Set this to force the screen whenever anything
    // is missing — the way back to a mod that was unchecked earlier.
    public static boolean modSyncReofferDeclined() {
        return boolValue("modSync.reofferDeclined", false);
    }

    // Host side: what this player shares with joiners when hosting their own world.
    //   off      — no mod sync for incoming players (like the legacy modSync=false)
    //   required — only mods needed to join (ModEntry.Env BOTH/SERVER); client-only mods withheld
    //   all      — every non-excluded mod (original behaviour)
    // Defaults to ALL, or OFF when the legacy -Dpeercraft.modSync=false is set.
    public static ModSyncMode modSyncHostMode() {
        return ModSyncMode.fromKey(stringValue("modSync.host", null), modSync() ? ModSyncMode.ALL : ModSyncMode.OFF);
    }

    // Client/joiner side: what this player downloads when joining someone else's world.
    //   off      — never run the mod-sync handshake
    //   required — only download mods needed to join; auto-skip client-only mods
    //   all      — offer to download everything the host has (original behaviour)
    // Independent of modSyncHostMode() so a player can download mods as a guest but share
    // nothing (or only the essentials) as a host.
    public static ModSyncMode modSyncClientMode() {
        return ModSyncMode.fromKey(stringValue("modSync.client", null), modSync() ? ModSyncMode.ALL : ModSyncMode.OFF);
    }

    private static String stringValue(String key, String defaultValue) {
        String property = System.getProperty(PROPERTY_PREFIX + key);
        //? if >=1.17
        if (property != null && !property.isBlank()) {
        //? if <1.17
        /*if (property != null && !property.trim().isEmpty()) {*/
            return property.trim();
        }

        String env = System.getenv(ENV_PREFIX + toEnvName(key));
        //? if >=1.17
        if (env != null && !env.isBlank()) {
        //? if <1.17
        /*if (env != null && !env.trim().isEmpty()) {*/
            return env.trim();
        }

        String override = overrides.get(key);
        //? if >=1.17
        if (override != null && !override.isBlank()) {
        //? if <1.17
        /*if (override != null && !override.trim().isEmpty()) {*/
            return override.trim();
        }

        String baked = BAKED_DEFAULTS.getProperty(key);
        if (baked != null && !baked.trim().isEmpty()) {
            return baked.trim();
        }

        return defaultValue;
    }

    /**
     * The value {@code key} resolves to from launch flags only — {@code -Dpeercraft.<key>} →
     * {@code PEERCRAFT_<KEY>} → the baked {@code peercraft-defaults.properties} — ignoring both
     * the settings.json override layer and the hardcoded fallback. {@code ""} when none set it.
     * The in-game settings screen uses this to show the value a flag is currently forcing (and
     * to avoid re-persisting a flag/baked value into settings.json).
     */
    public static String baselineValue(String key) {
        String property = System.getProperty(PROPERTY_PREFIX + key);
        if (property != null && !property.trim().isEmpty()) {
            return property.trim();
        }
        String env = System.getenv(ENV_PREFIX + toEnvName(key));
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        String baked = BAKED_DEFAULTS.getProperty(key);
        if (baked != null && !baked.trim().isEmpty()) {
            return baked.trim();
        }
        return "";
    }

    private static int intValue(String key, int defaultValue) {
        return intValueInRange(key, defaultValue, 0, 65535);
    }

    private static int intValueInRange(String key, int defaultValue, int min, int max) {
        String value = stringValue(key, Integer.toString(defaultValue));
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
            LOGGER.warn("[PeerCraftConfig] peercraft.{}='{}' вне диапазона {}-{}, использую {} по умолчанию", key, value, min, max, defaultValue);
        } catch (NumberFormatException e) {
            LOGGER.warn("[PeerCraftConfig] peercraft.{}='{}' — не целое число, использую {} по умолчанию", key, value, defaultValue);
        }
        return defaultValue;
    }

    private static boolean boolValue(String key, boolean defaultValue) {
        String value = stringValue(key, Boolean.toString(defaultValue));
        return "true".equalsIgnoreCase(value.trim());
    }

    private static String toEnvName(String key) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            // A dotted key (peercraft.modSync.autoAccept) maps its dots to underscores too, so
            // -Dpeercraft.modSync.autoAccept <-> PEERCRAFT_MOD_SYNC_AUTO_ACCEPT. Dot-free keys
            // are unaffected.
            if (c == '.') {
                builder.append('_');
                continue;
            }
            if (Character.isUpperCase(c)) {
                builder.append('_');
            }
            builder.append(Character.toUpperCase(c));
        }
        return builder.toString();
    }
}
