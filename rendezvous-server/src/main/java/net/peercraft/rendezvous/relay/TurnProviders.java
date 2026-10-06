package net.peercraft.rendezvous.relay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Missing/unknown provider never silently enables an unauthenticated relay. */
public final class TurnProviders {
    private TurnProviders() { }
    public static TurnProvider load(Path dataDir, RelayConfig config) throws IOException {
        Properties properties = new Properties();
        Path file = dataDir.resolve("relay.properties");
        if (Files.exists(file)) try (var reader = Files.newBufferedReader(file)) { properties.load(reader); }
        return switch (properties.getProperty("provider", "cloudflare")) {
            case "cloudflare" -> new CloudflareTurnProvider(config);
            case "coturn" -> new CoturnTurnProvider(properties, System.getenv("PEERCRAFT_COTURN_SECRET"), System::currentTimeMillis);
            default -> throw new IOException("Unknown TURN provider");
        };
    }
}
