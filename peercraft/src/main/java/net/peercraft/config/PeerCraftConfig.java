package net.peercraft.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PeerCraftConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    public static final String MODE_AUTO = "auto";
    public static final String MODE_CLIENT = "client";
    public static final String MODE_HOST = "host";
    public static final String MODE_DISABLED = "disabled";

    private static final String PROPERTY_PREFIX = "peercraft.";
    private static final String ENV_PREFIX = "PEERCRAFT_";

    private PeerCraftConfig() {
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

    private static String stringValue(String key, String defaultValue) {
        String property = System.getProperty(PROPERTY_PREFIX + key);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }

        String env = System.getenv(ENV_PREFIX + toEnvName(key));
        if (env != null && !env.isBlank()) {
            return env.trim();
        }

        return defaultValue;
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
            if (Character.isUpperCase(c)) {
                builder.append('_');
            }
            builder.append(Character.toUpperCase(c));
        }
        return builder.toString();
    }
}
