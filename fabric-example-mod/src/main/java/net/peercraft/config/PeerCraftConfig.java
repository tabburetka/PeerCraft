package net.peercraft.config;

public final class PeerCraftConfig {
    public static final String MODE_AUTO = "auto";
    public static final String MODE_CLIENT = "client";
    public static final String MODE_HOST = "host";
    public static final String MODE_DISABLED = "disabled";

    private static final String PROPERTY_PREFIX = "peercraft.";
    private static final String ENV_PREFIX = "PEERCRAFT_";

    private PeerCraftConfig() {
    }

    public static String mode() {
        String mode = rawMode().toLowerCase();
        if (MODE_CLIENT.equals(mode) || MODE_HOST.equals(mode) || MODE_DISABLED.equals(mode)) {
            return mode;
        }
        return MODE_AUTO;
    }

    public static String rawMode() {
        return stringValue("mode", MODE_AUTO);
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
        String value = stringValue(key, Integer.toString(defaultValue));
        try {
            int port = Integer.parseInt(value);
            if (port >= 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
        }
        return defaultValue;
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
