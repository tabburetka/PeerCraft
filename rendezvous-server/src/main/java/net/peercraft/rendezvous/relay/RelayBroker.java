package net.peercraft.rendezvous.relay;

import com.google.gson.*;
import java.io.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Authenticated control plane; no game data or Cloudflare master secrets cross this API. */
public final class RelayBroker implements AutoCloseable {
    public static final long BUDGET_BYTES = 800_000_000_000L;
    public static final int CREDENTIAL_TTL_SECONDS = 900;
    public static final class Failure extends RuntimeException {
        public final int status; public final String code;
        public Failure(int status, String code) { super(code); this.status = status; this.code = code; }
    }
    public record Endpoint(String host, int port) { }
    public record LeaseView(String leaseId, String linkId, String role, UUID attemptId, int generation,
                            String state, String sendKey, String receiveKey, TurnProvider.Credentials credentials,
                            Endpoint peerEndpoint, int peerGeneration) { }
    private static final class Issued {
        String username, account, ip, linkId, leaseId; long issuedAt, expiresAt, revokeAt; boolean revoked, renewal;
    }
    private static final class Journal { List<Issued> issued = new ArrayList<>(); String disabled; String billingStart; }
    private static final class Lease {
        final String id = UUID.randomUUID().toString(); final String role; final UUID account; final String ip;
        int generation = 1, endpointGeneration; TurnProvider.Credentials credentials; Endpoint endpoint; boolean confirmed;
        Lease(String role, UUID account, String ip) { this.role = role; this.account = account; this.ip = ip; }
    }
    private static final class Link {
        final String id = UUID.randomUUID().toString(); final RelayMatchRegistry.Match match;
        final String hostSend = randomKey(), joinSend = randomKey();
        Lease host, joiner; String closed, rotationOwner; long touchedAt, retainedUntil; Long retainedOffer;
        final Set<String> retentionOwners = new HashSet<>();
        Link(RelayMatchRegistry.Match match, long now) { this.match = match; touchedAt = now; }
    }
    private final RelayConfig config; private final TurnProvider provider; private final RelayMatchRegistry matches;
    private final Function<byte[], Optional<UUID>> sessions; private final LongSupplier clock;
    private final Map<UUID, Link> links = new HashMap<>(); private final Map<String, Link> byLease = new HashMap<>();
    private final Gson gson = new Gson(); private Journal journal; private boolean recoveryComplete, closed, everReady;
    private long lastUsageSuccess = Long.MIN_VALUE, lastPoll = Long.MIN_VALUE;
    private String guardReason;
    private Instant confirmedPeriodStart, confirmedPeriodEnd;

    public RelayBroker(RelayConfig config, TurnProvider provider, RelayMatchRegistry matches,
                       Function<byte[], Optional<UUID>> sessions, LongSupplier clock) throws IOException {
        this.config = config; this.provider = provider;
        confirmedPeriodStart = config.billingStart(); confirmedPeriodEnd = config.billingEnd(); this.matches = matches; this.sessions = sessions; this.clock = clock;
        if (Files.exists(config.journal())) {
            try (Reader reader = Files.newBufferedReader(config.journal())) { journal = gson.fromJson(reader, Journal.class); }
            catch (RuntimeException invalid) { throw new IOException("Relay journal invalid; preserve file and recover credentials manually"); }
            if (journal == null || journal.issued == null) throw new IOException("Relay journal invalid");
        } else journal = new Journal();
        // A changed configuration alone is not evidence of a new billing cycle.
        // Preserve its persisted cutoff until a complete query confirms that period.
        guardReason = journal.disabled;
        // Cloudflare account cutoffs are not evidence of a local coturn failure.
        if (!provider.requiresUsageBudget() && Set.of("budget_exhausted", "analytics_unavailable", "billing_period_unverified")
                .contains(guardReason == null ? "" : guardReason)) {
            guardReason = null; journal.disabled = null;
        }
        recoveryComplete = journal.issued.stream().noneMatch(i -> !i.revoked && i.expiresAt > clock.getAsLong());
    }
    public boolean configured() { return config.enabled(); }
    public String advertisedUrl() { return config.advertisedUrl(); }

    /** Runs on HTTP maintenance threads, never on the UDP receive loop. */
    public synchronized void maintenance() {
        long now = clock.getAsLong();
        if (everReady && (lastUsageSuccess == Long.MIN_VALUE || now - lastUsageSuccess > 120_000L)) disable(freshnessFailure());
        for (Link link : links.values()) {
            if (link.closed != null) continue;
            if (!linkLive(link, now)) closeLink(link, "match_expired");
            else if (now - link.touchedAt > 120_000L) closeLink(link, "lease_expired");
            else if ((link.host != null && link.host.credentials != null && link.host.credentials.expiresAt() <= now)
                    || (link.joiner != null && link.joiner.credentials != null && link.joiner.credentials.expiresAt() <= now))
                closeLink(link, "credential_expired");
        }
        if (!recoveryComplete) {
            for (Issued i : journal.issued) if (!i.revoked) i.revokeAt = now;
        }
        revokeDue(now);
        if (!recoveryComplete || provider.requiresUsageBudget())
            recoveryComplete = journal.issued.stream().noneMatch(i -> !i.revoked && i.expiresAt > now && i.revokeAt <= now);
        if (config.enabled() && (lastPoll == Long.MIN_VALUE || now - lastPoll >= 60_000L)) {
            lastPoll = now;
            try {
                if (!provider.requiresUsageBudget()) {
                    provider.checkHealth();
                    lastUsageSuccess = clock.getAsLong();
                    if ("provider_unavailable".equals(guardReason)) { guardReason = null; journal.disabled = null; }
                    if (recoveryComplete && guardReason == null) everReady = true;
                } else {
                TurnProvider.BillingPeriod period = provider.usesBillingPeriodApi() ? provider.billingPeriod()
                        : new TurnProvider.BillingPeriod(config.billingStart(), config.billingEnd());
                if ((!provider.usesBillingPeriodApi() && !config.billingPeriodVerified()) || !validRange(period.start(), period.end(), now))
                    throw new IOException("Billing period unverified");
                if (journal.billingStart != null && period.start().isBefore(Instant.parse(journal.billingStart)))
                    throw new IOException("Billing period moved backwards");
                TurnProvider.Usage usage = provider.usage(period.start(), Instant.ofEpochMilli(now));
                if (!usage.complete() || usage.turnBytes() < 0 || usage.sfuBytes() < 0) throw new IOException("Incomplete account usage");
                long totalBytes = usage.totalBytes(); // Overflow is invalid statistics, never a fresh success.
                lastUsageSuccess = clock.getAsLong();
                confirmedPeriodStart = period.start(); confirmedPeriodEnd = period.end();
                String confirmedStart = confirmedPeriodStart.toString();
                if (!Objects.equals(confirmedStart, journal.billingStart)) {
                    journal.billingStart = confirmedStart; journal.disabled = null; guardReason = null;
                }
                if (totalBytes >= BUDGET_BYTES) disable("budget_exhausted");
                else if (recoveryComplete) {
                    if ("analytics_unavailable".equals(guardReason)) { guardReason = null; journal.disabled = null; }
                    if (guardReason == null) everReady = true;
                }
                }
            } catch (IOException | RuntimeException unavailable) { /* freshness deadline below closes the guard */ }
        }
        if (everReady && (lastUsageSuccess == Long.MIN_VALUE || now - lastUsageSuccess > 120_000L)) disable(freshnessFailure());
        if (everReady && !periodValid(now)) disable("billing_period_unverified");
        links.entrySet().removeIf(e -> e.getValue().closed != null && now - e.getValue().touchedAt > 900_000L);
        byLease.entrySet().removeIf(e -> !links.containsValue(e.getValue()));
        journal.issued.removeIf(i -> i.revoked && now - i.issuedAt > 3_600_000L);
        persistOrDisable();
    }
    private boolean periodValid(long now) {
        if (!provider.requiresUsageBudget()) return true;
        return (provider.usesBillingPeriodApi() ? lastUsageSuccess != Long.MIN_VALUE : config.billingPeriodVerified())
                && validRange(confirmedPeriodStart, confirmedPeriodEnd, now);
    }
    private String freshnessFailure() { return provider.requiresUsageBudget() ? "analytics_unavailable" : "provider_unavailable"; }
    private static boolean validRange(Instant start, Instant end, long now) {
        return start != null && end != null && start.isBefore(end)
                && now >= start.toEpochMilli() && now < end.toEpochMilli();
    }

    private void guard() {
        if (!config.enabled() || closed) throw new Failure(503, "relay_disabled");
        if (guardReason != null) throw new Failure(503, guardReason);
        if (!periodValid(clock.getAsLong())) throw new Failure(503, "billing_period_unverified");
        if (!recoveryComplete || lastUsageSuccess == Long.MIN_VALUE || clock.getAsLong() - lastUsageSuccess > 120_000L) {
            if (everReady) disable(freshnessFailure());
            throw new Failure(503, !recoveryComplete && !provider.requiresUsageBudget() ? "credential_recovery_pending" : freshnessFailure());
        }
    }
    private UUID authenticate(byte[] token) {
        if (token == null || token.length != net.peercraft.rendezvous.AccountProtocol.TOKEN_LENGTH) throw new Failure(401, "unauthorized");
        return sessions.apply(token).orElseThrow(() -> new Failure(401, "unauthorized"));
    }
    public synchronized LeaseView create(String room, long token, UUID attempt, String role, byte[] session,
                                         String ip, boolean directChecksFailed) {
        UUID account = authenticate(session); guard();
        if ((!"host".equals(role) && !"joiner".equals(role)) || !directChecksFailed) throw new Failure(400, "direct_checks_required");
        Link link = links.get(attempt);
        if (link == null) {
            RelayMatchRegistry.Match match = matches.admit(room, token, attempt, role, account);
            if (match == null) throw new Failure(403, "match_not_authorized");
            if (links.values().stream().filter(l -> l.closed == null).count() >= provider.connectionLimit()) throw new Failure(429, "relay_capacity");
            if (links.size() >= 4096) throw new Failure(429, "relay_capacity");
            link = new Link(match, clock.getAsLong()); links.put(attempt, link);
        }
        verifyMatch(link, room, token, account, role);
        Lease lease = "host".equals(role) ? link.host : link.joiner;
        if (lease == null) {
            lease = new Lease(role, account, ip);
            if ("host".equals(role)) link.host = lease; else link.joiner = lease;
            byLease.put(lease.id, link);
        }
        link.touchedAt = clock.getAsLong();
        // A single client's failure never allocates paid-capable credentials.
        if (link.host != null && link.joiner != null && link.host.credentials == null && link.joiner.credentials == null) {
            try { link.host.credentials = issue(link, link.host); link.joiner.credentials = issue(link, link.joiner); }
            catch (Failure failure) { closeLink(link, failure.code); revokeDue(clock.getAsLong()); throw failure; }
        }
        return view(link, lease);
    }
    private void verifyMatch(Link link, String room, long token, UUID account, String role) {
        if (link.closed != null) throw new Failure(410, link.closed);
        if (!link.match.roomCode().equals(room) || link.match.pairToken() != token || !matches.live(link.match)
                || !Objects.equals(account, "host".equals(role) ? link.match.hostAccount() : link.match.joinerAccount()))
            throw new Failure(403, "match_not_authorized");
    }
    private Lease owner(Link link, String id, UUID account) {
        Lease lease = link.host != null && link.host.id.equals(id) ? link.host : link.joiner;
        if (lease == null || !lease.id.equals(id) || !lease.account.equals(account)) throw new Failure(403, "lease_not_authorized");
        return lease;
    }
    private Link lookup(String id, byte[] token) {
        UUID account = authenticate(token); guard();
        Link link = byLease.get(id); if (link == null) throw new Failure(404, "lease_unknown");
        owner(link, id, account);
        if (link.closed != null) throw new Failure(410, link.closed);
        if (!linkLive(link, clock.getAsLong())) { closeLink(link, "match_expired"); throw new Failure(410, "match_expired"); }
        link.touchedAt = clock.getAsLong(); return link;
    }
    private boolean linkLive(Link link, long now) {
        return matches.live(link.match) || (!link.retentionOwners.isEmpty() && now < link.retainedUntil);
    }
    public synchronized LeaseView retain(String id, byte[] token, long offer) {
        Link link = lookup(id, token); Lease lease = owner(link, id, authenticate(token));
        if (link.host == null || link.joiner == null || !link.host.confirmed || !link.joiner.confirmed)
            throw new Failure(409, "peer_not_ready");
        if (link.retainedOffer != null && link.retainedOffer.longValue() != offer)
            throw new Failure(409, "generation_mismatch");
        if (link.retainedOffer == null) {
            link.retainedOffer = offer; link.retainedUntil = clock.getAsLong() + 40 * 60_000L;
        }
        if (clock.getAsLong() >= link.retainedUntil) throw new Failure(410, "lease_expired");
        link.retentionOwners.add(id); return view(link, lease);
    }
    public synchronized LeaseView release(String id, byte[] token, long offer) {
        Link link = lookup(id, token); Lease lease = owner(link, id, authenticate(token));
        if (link.retainedOffer != null && link.retainedOffer.longValue() != offer)
            throw new Failure(409, "generation_mismatch");
        link.retentionOwners.remove(id);
        if (link.retentionOwners.isEmpty()) { link.retainedOffer = null; link.retainedUntil = 0; }
        return view(link, lease);
    }
    public synchronized LeaseView get(String id, byte[] token) { Link link = lookup(id, token); return view(link, owner(link, id, authenticate(token))); }
    public synchronized LeaseView endpoint(String id, byte[] token, Endpoint endpoint, int generation, boolean confirmed) {
        Link link = lookup(id, token); Lease lease = owner(link, id, authenticate(token));
        if (lease.credentials == null) throw new Failure(409, "peer_not_ready");
        if (lease.generation != generation) throw new Failure(409, "generation_mismatch");
        validateEndpoint(endpoint);
        if (confirmed && lease.endpoint != null && !lease.endpoint.equals(endpoint)) throw new Failure(409, "endpoint_mismatch");
        lease.endpoint = endpoint; lease.endpointGeneration = generation; lease.confirmed = confirmed;
        if (confirmed && id.equals(link.rotationOwner)) {
            for (Issued issued : journal.issued) if (issued.linkId.equals(link.id) && Objects.equals(issued.leaseId, lease.id)
                    && !issued.username.equals(lease.credentials.username()) && !issued.revoked)
                issued.revokeAt = Math.min(issued.revokeAt, clock.getAsLong() + 30_000L);
            link.rotationOwner = null; persistOrDisable();
        }
        return view(link, lease);
    }
    public synchronized LeaseView renew(String id, byte[] token) {
        Link link = lookup(id, token); Lease lease = owner(link, id, authenticate(token));
        if (lease.credentials == null || !lease.confirmed) {
            if (id.equals(link.rotationOwner)) return view(link, lease);
            throw new Failure(409, "peer_not_ready");
        }
        if (link.rotationOwner != null) {
            if (id.equals(link.rotationOwner)) return view(link, lease);
            throw new Failure(409, "rotation_busy");
        }
        TurnProvider.Credentials fresh = issue(link, lease);
        lease.credentials = fresh; lease.generation++; lease.confirmed = false;
        // Keep the previous published endpoint until PUT publishes the replacement.
        link.rotationOwner = id;
        return view(link, lease);
    }
    public synchronized void delete(String id, byte[] token) {
        UUID account = authenticate(token); Link link = byLease.get(id);
        if (link == null) throw new Failure(404, "lease_unknown");
        owner(link, id, account); closeLink(link, "lease_closed"); revokeDue(clock.getAsLong()); persistOrDisable();
    }
    private TurnProvider.Credentials issue(Link link, Lease lease) {
        long now = clock.getAsLong();
        boolean renewal = lease.credentials != null;
        long accounts = journal.issued.stream().filter(i -> !i.renewal && now - i.issuedAt < 3_600_000L && i.account.equals(lease.account.toString())).count();
        long ips = journal.issued.stream().filter(i -> !i.renewal && now - i.issuedAt < 3_600_000L && i.ip.equals(lease.ip)).count();
        long accountIssuance = journal.issued.stream().filter(i -> now - i.issuedAt < 3_600_000L && i.account.equals(lease.account.toString())).count();
        long ipIssuance = journal.issued.stream().filter(i -> now - i.issuedAt < 3_600_000L && i.ip.equals(lease.ip)).count();
        // Keep playing after the new-connection quota is reached. A separate generous
        // issuance cap still bounds a modified client's repeated allocation renewals.
        if ((!renewal && (accounts >= 60 || ips >= 120)) || accountIssuance >= 360 || ipIssuance >= 720)
            throw new Failure(429, "relay_rate_limited");
        guard();
        try {
            TurnProvider.Credentials credential = provider.issue(CREDENTIAL_TTL_SECONDS, "peercraft:" + link.id + ":" + lease.role);
            if (credential.expiresAt() <= now || credential.expiresAt() > now + 901_000L
                    || credential.username() == null || credential.password() == null) throw new IOException("Invalid credential lifetime");
            Issued issued = new Issued(); issued.renewal = renewal; issued.username = credential.username(); issued.account = lease.account.toString();
            issued.ip = lease.ip; issued.linkId = link.id; issued.leaseId = lease.id; issued.issuedAt = now; issued.expiresAt = credential.expiresAt();
            issued.revokeAt = Long.MAX_VALUE; journal.issued.add(issued);
            persistOrDisable(); guard(); return credential;
        } catch (IOException unavailable) { throw new Failure(503, "provider_unavailable"); }
    }
    private LeaseView view(Link link, Lease lease) {
        Lease peer = lease == link.host ? link.joiner : link.host;
        return new LeaseView(lease.id, link.id, lease.role, link.match.attemptId(), lease.generation,
                link.host != null && link.joiner != null && link.host.credentials != null && link.joiner.credentials != null ? "ready" : "waiting",
                lease == link.host ? link.hostSend : link.joinSend, lease == link.host ? link.joinSend : link.hostSend,
                lease.credentials, peer == null ? null : peer.endpoint, peer == null ? 0 : peer.endpointGeneration);
    }
    private static void validateEndpoint(Endpoint endpoint) {
        if (endpoint == null || endpoint.host() == null || !endpoint.host().matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
                || endpoint.port() < 1 || endpoint.port() > 65535) throw new Failure(400, "invalid_endpoint");
        try {
            InetAddress address = InetAddress.getByName(endpoint.host());
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) throw new Failure(400, "invalid_endpoint");
        } catch (IOException invalid) { throw new Failure(400, "invalid_endpoint"); }
    }
    private void disable(String reason) {
        if (guardReason == null) guardReason = reason;
        journal.disabled = guardReason;
        for (Link link : links.values()) closeLink(link, guardReason);
        for (Issued issued : journal.issued) if (!issued.revoked) issued.revokeAt = clock.getAsLong();
        persistOrDisable();
    }
    private void closeLink(Link link, String reason) {
        if (link.closed == null) { link.closed = reason; link.touchedAt = clock.getAsLong(); }
        for (Issued issued : journal.issued) if (!issued.revoked && issued.linkId.equals(link.id)) issued.revokeAt = clock.getAsLong();
    }
    private void revokeDue(long now) {
        for (Issued issued : journal.issued) if (!issued.revoked && issued.revokeAt <= now) {
            if (issued.expiresAt <= now) { issued.revoked = true; continue; }
            if (!provider.supportsIndividualRevocation()) continue; // Keep the debt until genuine expiry.
            try { provider.revoke(issued.username); issued.revoked = true; }
            catch (IOException | RuntimeException retryLater) { /* journal retains the revocation debt */ }
        }
    }
    private void persistOrDisable() {
        try {
            Files.createDirectories(config.journal().toAbsolutePath().getParent());
            Path temp = config.journal().resolveSibling(config.journal().getFileName() + ".tmp");
            Files.writeString(temp, gson.toJson(journal), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
            try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp, config.journal(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) { guardReason = "journal_unavailable"; journal.disabled = guardReason; }
    }
    private static String randomKey() { byte[] key = new byte[16]; new SecureRandom().nextBytes(key); return Base64.getEncoder().encodeToString(key); }
    @Override public synchronized void close() {
        closed = true;
        for (Link link : links.values()) closeLink(link, "relay_disabled");
        for (Issued issued : journal.issued) if (!issued.revoked) issued.revokeAt = clock.getAsLong();
        persistOrDisable(); revokeDue(clock.getAsLong()); persistOrDisable();
    }
}
