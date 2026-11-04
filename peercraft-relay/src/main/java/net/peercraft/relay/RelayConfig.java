package net.peercraft.relay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;
import net.peercraft.network.ownrelay.OwnRelayProtocol;

/** relay.properties: created with a fresh random secret on first start, then read as is. */
final class RelayConfig {
    final int port;
    final byte[] secret;
    final String publicHost;
    final int maxSessions;
    final long maxBytesPerSecondPerSession;
    final int opensPerMinutePerIp;
    final long pendingTimeoutMillis;
    final long idleTimeoutMillis;

    RelayConfig(int port, byte[] secret, String publicHost, int maxSessions, long maxBytesPerSecondPerSession,
                int opensPerMinutePerIp, long pendingTimeoutMillis, long idleTimeoutMillis) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be 1-65535");
        if (secret == null || secret.length != OwnRelayProtocol.SECRET_BYTES) throw new IllegalArgumentException("secret must be 32 bytes");
        if (maxSessions < 1 || maxBytesPerSecondPerSession < 16 * 1024 || opensPerMinutePerIp < 1
                || pendingTimeoutMillis < 1000 || idleTimeoutMillis < 1000) throw new IllegalArgumentException("Invalid relay limits");
        this.port = port; this.secret = secret.clone(); this.publicHost = publicHost == null ? "" : publicHost.trim();
        this.maxSessions = maxSessions; this.maxBytesPerSecondPerSession = maxBytesPerSecondPerSession;
        this.opensPerMinutePerIp = opensPerMinutePerIp; this.pendingTimeoutMillis = pendingTimeoutMillis;
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    static RelayConfig loadOrCreate(Path file, boolean rotateSecret) throws IOException {
        Properties properties = new Properties();
        boolean exists = Files.exists(file);
        if (exists) try (InputStream in = Files.newInputStream(file)) { properties.load(in); }
        boolean changed = !exists;
        if (rotateSecret || properties.getProperty("secret", "").trim().isEmpty()) {
            byte[] secret = new byte[OwnRelayProtocol.SECRET_BYTES];
            new SecureRandom().nextBytes(secret);
            properties.setProperty("secret", Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
            changed = true;
        }
        changed |= defaults(properties);
        if (changed) save(file, properties);
        try {
            return new RelayConfig(
                    Integer.parseInt(properties.getProperty("port").trim()),
                    Base64.getUrlDecoder().decode(properties.getProperty("secret").trim()),
                    properties.getProperty("publicHost", ""),
                    Integer.parseInt(properties.getProperty("maxSessions").trim()),
                    Long.parseLong(properties.getProperty("maxKiBPerSecondPerSession").trim()) * 1024L,
                    Integer.parseInt(properties.getProperty("opensPerMinutePerIp").trim()),
                    Long.parseLong(properties.getProperty("pendingTimeoutSeconds").trim()) * 1000L,
                    Long.parseLong(properties.getProperty("idleTimeoutSeconds").trim()) * 1000L);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid " + file + ": " + invalid.getMessage(), invalid);
        }
    }

    private static boolean defaults(Properties p) {
        boolean changed = false;
        changed |= setDefault(p, "port", Integer.toString(OwnRelayProtocol.DEFAULT_PORT));
        changed |= setDefault(p, "publicHost", "");
        changed |= setDefault(p, "maxSessions", "8");
        changed |= setDefault(p, "maxKiBPerSecondPerSession", "2048");
        changed |= setDefault(p, "opensPerMinutePerIp", "20");
        changed |= setDefault(p, "pendingTimeoutSeconds", "30");
        changed |= setDefault(p, "idleTimeoutSeconds", "60");
        return changed;
    }

    private static boolean setDefault(Properties p, String key, String value) {
        if (p.getProperty(key) != null) return false;
        p.setProperty(key, value);
        return true;
    }

    private static void save(Path file, Properties properties) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        String header = "PeerCraft relay settings. Keep 'secret' private: anyone with it can use this relay.\n"
                + "publicHost: address players reach this relay at (empty = detect automatically).";
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8), header);
        }
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException | IOException notPosix) { /* Windows: rely on the user profile ACL. */ }
    }
}
