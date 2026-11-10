package net.peercraft.network.control;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides whether a rendezvous server's static key is the right one.
 *
 * <ul>
 *   <li>Built-in pins: the official server's key ships with the mod, so even a first connection
 *       cannot be intercepted. {@code -Dpeercraft.rendezvous.serverKey=<base64>[,<base64>]}
 *       replaces the pins for whatever server is configured.</li>
 *   <li>Trust on first use for self-hosted servers: the first key seen is remembered in
 *       {@code config/peercraft/known-servers}; a different key later is refused.</li>
 *   <li>Unpinned loopback servers (local test rigs) are accepted with any key and never remembered.</li>
 * </ul>
 *
 * A server that is pinned or remembered must always speak the encrypted protocol; only an
 * unknown self-hosted server may fall back to the legacy plaintext protocol (an older server).
 */
public final class ControlTrust {
    private static final Map<String, List<String>> BUILTIN_PINS;
    static {
        Map<String, List<String>> pins = new HashMap<>();
        pins.put("connection.peercraft.ru", Collections.singletonList(
                "BC0gRKjwffdQn2fjg2g+5ILxOfyOM9S19j984v2E5rkaTMLdrTKlcvQVw+YJxvRapKwkOVzeyuovy7l0Vn0kMA8=" /* 0a4a:e43a:223f:18f9:1b56:c9b0:e5c5:228a */));
        BUILTIN_PINS = Collections.unmodifiableMap(pins);
    }

    private static volatile Path storeFile = Paths.get("config", "peercraft", "known-servers");
    private static Map<String, String> known;

    private ControlTrust() { }

    /** Tests point the TOFU store elsewhere. */
    static synchronized void useStore(Path file) {
        storeFile = file;
        known = null;
    }

    /** @return true when plaintext must never be used with this server. */
    public static boolean requiresEncryption(String host, int port, InetAddress address) {
        if (!pins(host).isEmpty()) return true;
        if (address != null && address.isLoopbackAddress()) return false;
        return knownKey(host, port) != null;
    }

    /** @return true if {@code serverKey} may be used for this server; remembers it on first use. */
    public static boolean accept(String host, int port, InetAddress address, byte[] serverKey) {
        String encoded = Base64.getEncoder().encodeToString(serverKey);
        List<String> pins = pins(host);
        if (!pins.isEmpty()) return pins.contains(encoded);
        if (address != null && address.isLoopbackAddress()) return true;
        synchronized (ControlTrust.class) {
            String remembered = knownKey(host, port);
            if (remembered != null) return remembered.equals(encoded);
            known.put(key(host, port), encoded);
            save();
            return true;
        }
    }

    public static Path storeFile() { return storeFile; }

    static List<String> pins(String host) {
        String override = System.getProperty("peercraft.rendezvous.serverKey", "").trim();
        if (!override.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (String part : override.split(",")) if (!part.trim().isEmpty()) out.add(part.trim());
            return out;
        }
        if (host == null) return Collections.emptyList();
        List<String> pinned = BUILTIN_PINS.get(host.toLowerCase(Locale.ROOT));
        return pinned == null ? Collections.<String>emptyList() : pinned;
    }

    private static synchronized String knownKey(String host, int port) {
        if (known == null) known = load();
        return known.get(key(host, port));
    }

    private static String key(String host, int port) {
        return (host == null ? "" : host.toLowerCase(Locale.ROOT)) + ":" + port;
    }

    private static Map<String, String> load() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            if (!Files.exists(storeFile)) return out;
            for (String line : Files.readAllLines(storeFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] parts = trimmed.split("\\s+");
                if (parts.length == 2) out.put(parts[0], parts[1]);
            }
        } catch (IOException ignored) {
            // Unreadable store: behave as if empty, the next save rewrites it.
        }
        return out;
    }

    private static void save() {
        StringBuilder text = new StringBuilder("# Rendezvous servers seen by PeerCraft (host:port public-key).\n"
                + "# Delete a line only if that server's owner told you its key changed.\n");
        for (Map.Entry<String, String> entry : known.entrySet()) text.append(entry.getKey()).append(' ').append(entry.getValue()).append('\n');
        try {
            Path parent = storeFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(storeFile, text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Still trusted for this run; it is simply asked again next launch.
        }
    }
}
