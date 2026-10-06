package net.peercraft.rendezvous.relay;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/** Provider boundary; Cloudflare billing and self-hosted admission remain distinct. */
public interface TurnProvider {
    default boolean requiresUsageBudget() { return true; }
    default boolean supportsIndividualRevocation() { return true; }
    default int connectionLimit() { return 32; }
    default void checkHealth() throws IOException { }
    record Credentials(List<String> urls, String username, String password, long expiresAt, int bulkBytesPerSecond) {
        public Credentials(List<String> urls, String username, String password, long expiresAt) {
            this(urls, username, password, expiresAt, 2 * 1024 * 1024);
        }
        public Credentials { urls = List.copyOf(urls); }
        @Override public String toString() { return "TURN credentials [redacted]"; }
    }
    /** Complete account usage, including SFU unless the account is explicitly TURN-exclusive. */
    record Usage(long turnBytes, long sfuBytes, boolean complete) {
        public long totalBytes() { return Math.addExact(turnBytes, sfuBytes); }
    }
    record BillingPeriod(Instant start, Instant end) { }
    default boolean usesBillingPeriodApi() { return false; }
    default BillingPeriod billingPeriod() throws IOException { throw new IOException("Billing period API unavailable"); }
    Credentials issue(int ttlSeconds, String customIdentifier) throws IOException;
    void revoke(String username) throws IOException;
    Usage usage(Instant billingStart, Instant billingEnd) throws IOException;
}
