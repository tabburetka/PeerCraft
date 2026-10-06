package net.peercraft.rendezvous.relay;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.Properties;

/** No secrets in this file: TURN/analytics/keystore passwords are environment variables. */
public record RelayConfig(boolean enabled, String advertisedUrl, String bindHost, int port,
                          boolean tlsReverseProxy, Path keyStore, String keyStorePassword,
                          Instant billingStart, Instant billingEnd, boolean billingPeriodVerified,
                          boolean exclusiveTurnAccount, Path usageQuery, Path journal) {
    public static RelayConfig load(Path dataDir) throws IOException {
        Properties p = new Properties();
        Path file = dataDir.resolve("relay.properties");
        if (Files.exists(file)) try (var reader = Files.newBufferedReader(file)) { p.load(reader); }
        boolean enabled = Boolean.parseBoolean(p.getProperty("enabled", "false"));
        String url = p.getProperty("advertisedUrl", "");
        if (enabled) {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null)
                throw new IOException("Relay advertisedUrl must be a public HTTPS URL");
        }
        return new RelayConfig(enabled, url, p.getProperty("bindHost", "127.0.0.1"),
                Integer.parseInt(p.getProperty("port", "51081")),
                Boolean.parseBoolean(p.getProperty("tlsReverseProxy", "false")),
                p.containsKey("keyStore") ? Path.of(p.getProperty("keyStore")) : null,
                System.getenv("PEERCRAFT_RELAY_KEYSTORE_PASSWORD"),
                p.containsKey("billingStart") ? Instant.parse(p.getProperty("billingStart")) : null,
                p.containsKey("billingEnd") ? Instant.parse(p.getProperty("billingEnd")) : null,
                Boolean.parseBoolean(p.getProperty("billingPeriodVerified", "false")),
                Boolean.parseBoolean(p.getProperty("exclusiveTurnAccount", "false")),
                p.containsKey("usageQuery") ? Path.of(p.getProperty("usageQuery")) : null,
                dataDir.resolve("relay-issued.json"));
    }
}
