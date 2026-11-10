package net.peercraft.rendezvous.relay;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RelayBrokerTest {
    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_780_000_000_000L);
    final UUID host = UUID.randomUUID(), joiner = UUID.randomUUID();
    final byte[] hostToken = new byte[16], joinToken = new byte[16];
    RelayMatchRegistry matches; FakeProvider provider; RelayBroker broker;
    static class FakeProvider implements TurnProvider {
        final AtomicLong now; int issued; long bytes, sfuBytes; boolean usageFails, revokeFails, complete = true;
        boolean automaticPeriod, periodFails; BillingPeriod period; Instant queriedStart;
        final Set<String> revoked = new HashSet<>();
        public boolean usesBillingPeriodApi() { return automaticPeriod; }
        public BillingPeriod billingPeriod() throws IOException { if (periodFails) throw new IOException(); return period; }
        FakeProvider(AtomicLong now) { this.now = now; }
        public Credentials issue(int ttl, String id) { return new Credentials(List.of("turn:turn.cloudflare.com:3478?transport=udp"), "user" + ++issued, "TOP_SECRET_PASSWORD", now.get() + ttl * 1000L); }
        public void revoke(String username) throws IOException { if (revokeFails) throw new IOException(); revoked.add(username); }
        public Usage usage(Instant start, Instant end) throws IOException { if (usageFails) throw new IOException(); queriedStart = start; return new Usage(bytes, sfuBytes, complete); }
    }
    RelayConfig config() { return new RelayConfig(true, "https://relay.example", "127.0.0.1", 0, true,
            null, null, Instant.ofEpochMilli(now.get() - 1000), Instant.ofEpochMilli(now.get() + 86_400_000), true,
            true, null, dir.resolve("issued.json")); }
    @BeforeEach void setup() throws Exception {
        joinToken[0] = 1; matches = new RelayMatchRegistry(now::get); provider = new FakeProvider(now);
        broker = new RelayBroker(config(), provider, matches, token -> Arrays.equals(token, hostToken) ? Optional.of(host)
                : Arrays.equals(token, joinToken) ? Optional.of(joiner) : Optional.empty(), now::get);
        broker.maintenance();
    }
    UUID match() { UUID attempt = UUID.randomUUID(); matches.observeHost("ABCDEF", host, true);
        matches.observeMatch("ABCDEF", 42L, attempt, host, joiner, true); return attempt; }
    static class LocalProvider extends FakeProvider {
        boolean healthFails;
        LocalProvider(AtomicLong now) { super(now); }
        public boolean requiresUsageBudget() { return false; }
        public boolean supportsIndividualRevocation() { return false; }
        public int connectionLimit() { return 2; }
        public void checkHealth() throws IOException { if (healthFails) throw new IOException(); }
        public Usage usage(Instant start, Instant end) { throw new AssertionError("No Cloudflare usage for coturn"); }
        public void revoke(String username) { throw new AssertionError("REST credentials cannot be individually revoked"); }
    }
    RelayConfig localConfig() { return new RelayConfig(true, "https://relay.example", "127.0.0.1", 0, true,
            null, null, null, null, false, false, null, dir.resolve("local-issued.json")); }
    void localBroker() throws IOException {
        broker.close(); provider = new LocalProvider(now);
        broker = new RelayBroker(localConfig(), provider, matches, token -> Arrays.equals(token, hostToken) ? Optional.of(host)
                : Arrays.equals(token, joinToken) ? Optional.of(joiner) : Optional.empty(), now::get);
    }
    @Test void coturnNeedsHealthButNoBillingAndLimitsConnections() throws Exception {
        localBroker(); failure("provider_unavailable", () -> host(match()));
        broker.maintenance(); UUID first = match(); host(first); join(first);
        UUID second = match(); host(second); join(second);
        failure("relay_capacity", () -> host(match()));
        assertEquals(4,provider.issued); assertNull(provider.queriedStart);
    }
    @Test void coturnHealthFailureClosesExistingLeasesAndRecovers() throws Exception {
        localBroker(); broker.maintenance(); UUID attempt = match(); var h = host(attempt); join(attempt);
        ((LocalProvider)provider).healthFails = true; now.addAndGet(120_001); broker.maintenance();
        failure("provider_unavailable", () -> broker.get(h.leaseId(),hostToken));
        assertTrue(provider.revoked.isEmpty());
        ((LocalProvider)provider).healthFails = false; now.addAndGet(60_001); broker.maintenance();
        var fresh = host(match()); assertEquals("waiting",fresh.state());
    }
    @Test void coturnRestartWaitsForOutstandingCredentialsInsteadOfClaimingRevocation() throws Exception {
        localBroker(); broker.maintenance(); UUID attempt = match(); host(attempt); join(attempt); broker.close();
        broker = new RelayBroker(localConfig(),provider,matches,token -> Optional.of(host),now::get);
        broker.maintenance(); failure("credential_recovery_pending", () -> host(match()));
        now.addAndGet(900_001); broker.maintenance(); assertEquals("waiting",host(match()).state());
        assertTrue(provider.revoked.isEmpty());
    }
    @Test void coturnHandoffHoldSurvivesRoomChangeButCannotBypassHealthCutoff() throws Exception {
        localBroker(); broker.maintenance(); UUID attempt=match(); var h=host(attempt); var j=join(attempt);
        broker.endpoint(h.leaseId(),hostToken,new RelayBroker.Endpoint("203.0.113.1",52000),1,true);
        broker.endpoint(j.leaseId(),joinToken,new RelayBroker.Endpoint("203.0.113.1",52001),1,true);
        broker.retain(h.leaseId(),hostToken,51); broker.retain(j.leaseId(),joinToken,51);
        matches.observeHost("ABCDEF",joiner,true,UUID.randomUUID()); broker.maintenance();
        assertNotNull(broker.get(h.leaseId(),hostToken).credentials());
        ((LocalProvider)provider).healthFails=true; now.addAndGet(120_001); broker.maintenance();
        failure("provider_unavailable",() -> broker.get(h.leaseId(),hostToken));
        assertTrue(provider.revoked.isEmpty());
    }
    RelayBroker.LeaseView host(UUID id) { return broker.create("ABCDEF",42,id,"host",hostToken,"203.0.113.10",true); }
    RelayBroker.LeaseView join(UUID id) { return broker.create("ABCDEF",42,id,"joiner",joinToken,"203.0.113.11",true); }
    void failure(String code, Runnable operation) { assertEquals(code, assertThrows(RelayBroker.Failure.class, operation::run).code); }

    @Test void bothFailedDirectAndAuthenticatedMatchAreRequired() {
        UUID attempt = match();
        failure("direct_checks_required", () -> broker.create("ABCDEF",42,attempt,"host",hostToken,"ip",false));
        failure("match_not_authorized", () -> broker.create("ABCDEF",43,attempt,"host",hostToken,"ip",true));
        failure("match_not_authorized", () -> broker.create("ABCDEF",42,attempt,"joiner",hostToken,"ip",true));
        var host = host(attempt); assertEquals("waiting",host.state()); assertNull(host.credentials()); assertEquals(0,provider.issued);
        var join = join(attempt); assertEquals("ready",join.state()); assertEquals(2,provider.issued);
        assertEquals(host.sendKey(),join.receiveKey()); assertEquals(host.receiveKey(),join.sendKey());
        assertNotNull(broker.get(host.leaseId(),hostToken).credentials());
        failure("lease_not_authorized", () -> broker.get(host.leaseId(),joinToken));
        assertEquals(2,provider.issued); host(attempt); join(attempt); assertEquals(2,provider.issued);
    }
    @Test void retainedLeaseSurvivesHostRoomChangeAndReleaseEndsHold() {
        UUID attempt = match(); var h = host(attempt); var j = join(attempt);
        broker.endpoint(h.leaseId(),hostToken,new RelayBroker.Endpoint("104.16.1.1",40000),1,true);
        broker.endpoint(j.leaseId(),joinToken,new RelayBroker.Endpoint("104.16.1.2",40001),1,true);
        failure("lease_not_authorized", () -> broker.retain(h.leaseId(), joinToken, 9));
        broker.retain(h.leaseId(),hostToken,9); broker.retain(j.leaseId(),joinToken,9);
        matches.observeHost("ABCDEF", joiner, true, UUID.randomUUID());
        broker.maintenance(); assertNotNull(broker.get(h.leaseId(),hostToken).credentials());
        broker.release(h.leaseId(),hostToken,9); assertNotNull(broker.get(j.leaseId(),joinToken).credentials());
        broker.release(j.leaseId(),joinToken,9); broker.maintenance();
        failure("match_expired", () -> broker.get(h.leaseId(),hostToken));
    }
    @Test void repeatedRetainCannotExtendFortyMinuteHold() {
        UUID attempt = match(); var h = host(attempt); var j = join(attempt);
        var hostEndpoint = new RelayBroker.Endpoint("104.16.1.1",40000);
        var joinEndpoint = new RelayBroker.Endpoint("104.16.1.2",40001);
        broker.endpoint(h.leaseId(),hostToken,hostEndpoint,1,true);
        broker.endpoint(j.leaseId(),joinToken,joinEndpoint,1,true);
        broker.retain(h.leaseId(),hostToken,17);
        matches.observeHost("ABCDEF",joiner,true,UUID.randomUUID());
        for (int minute = 1; minute < 40; minute++) {
            now.addAndGet(60_000); broker.maintenance();
            broker.get(h.leaseId(),hostToken); broker.get(j.leaseId(),joinToken);
            if (minute % 12 == 0) {
                var freshHost = broker.renew(h.leaseId(),hostToken);
                broker.endpoint(h.leaseId(),hostToken,hostEndpoint,freshHost.generation(),true);
                var freshJoin = broker.renew(j.leaseId(),joinToken);
                broker.endpoint(j.leaseId(),joinToken,joinEndpoint,freshJoin.generation(),true);
            }
            broker.retain(h.leaseId(),hostToken,17);
        }
        now.addAndGet(60_000); broker.maintenance();
        failure("match_expired", () -> broker.get(h.leaseId(),hostToken));
    }
    @Test void retainedLeaseStillClosesAtBudgetCutoff() {
        UUID attempt = match(); var h = host(attempt); var j = join(attempt);
        broker.endpoint(h.leaseId(),hostToken,new RelayBroker.Endpoint("104.16.1.1",40000),1,true);
        broker.endpoint(j.leaseId(),joinToken,new RelayBroker.Endpoint("104.16.1.2",40001),1,true);
        broker.retain(h.leaseId(),hostToken,11);
        matches.observeHost("ABCDEF", joiner, true, UUID.randomUUID());
        provider.bytes = RelayBroker.BUDGET_BYTES; now.addAndGet(60_001); broker.maintenance();
        failure("budget_exhausted", () -> broker.get(h.leaseId(),hostToken));
        broker.maintenance(); assertEquals(2,provider.revoked.size());
    }

    @Test void rotationIsIdempotentSerialAndRetainsOldCredentialUntilCommitGrace() {
        UUID attempt = match(); var h = host(attempt); var j = join(attempt); h = broker.get(h.leaseId(),hostToken);
        var hEndpoint = new RelayBroker.Endpoint("104.16.1.1",40000); var jEndpoint = new RelayBroker.Endpoint("104.16.1.2",40001);
        broker.endpoint(h.leaseId(),hostToken,hEndpoint,1,false); broker.endpoint(h.leaseId(),hostToken,hEndpoint,1,true);
        broker.endpoint(j.leaseId(),joinToken,jEndpoint,1,false); broker.endpoint(j.leaseId(),joinToken,jEndpoint,1,true);
        String oldUsername = h.credentials().username(); var rotated = broker.renew(h.leaseId(),hostToken);
        assertEquals(2,rotated.generation()); assertEquals(rotated.credentials(),broker.renew(h.leaseId(),hostToken).credentials());
        assertEquals(1,broker.get(j.leaseId(),joinToken).peerGeneration());
        String joinLeaseId = j.leaseId(); failure("rotation_busy", () -> broker.renew(joinLeaseId,joinToken));
        var replacement = new RelayBroker.Endpoint("104.16.1.3",40002);
        broker.endpoint(h.leaseId(),hostToken,replacement,2,false); assertEquals(2,broker.get(j.leaseId(),joinToken).peerGeneration());
        assertFalse(provider.revoked.contains(oldUsername));
        broker.endpoint(h.leaseId(),hostToken,replacement,2,true); now.addAndGet(29_000); broker.maintenance();
        assertFalse(provider.revoked.contains(oldUsername)); now.addAndGet(1_001); broker.maintenance();
        assertTrue(provider.revoked.contains(oldUsername)); assertEquals(3,provider.issued);
        assertEquals(2,broker.renew(j.leaseId(),joinToken).generation());
    }
    @Test void incompleteOrStaleAccountUsageDisablesAndRevokesWithoutCalendarReset() {
        UUID attempt = match(); var h = host(attempt); join(attempt); provider.usageFails = true;
        now.addAndGet(120_001); matches.observeHost("ABCDEF",host,true); broker.maintenance();
        failure("analytics_unavailable", () -> broker.get(h.leaseId(),hostToken)); assertEquals(2,provider.revoked.size());
        provider.usageFails = false; now.addAndGet(60_001); broker.maintenance();
        assertNull(host(match()).credentials());
        failure("analytics_unavailable", () -> broker.get(h.leaseId(),hostToken));
    }
    @Test void budgetIncludesBothProductsAndBlocksAt800GB() {
        UUID attempt = match(); host(attempt); join(attempt); provider.bytes = RelayBroker.BUDGET_BYTES;
        now.addAndGet(60_001); matches.observeHost("ABCDEF",host,true); broker.maintenance();
        failure("budget_exhausted", () -> host(match())); broker.maintenance(); assertEquals(2,provider.revoked.size());
    }
    @Test void hostConsentWithdrawalAndCredentialExpiryCloseLinks() {
        UUID attempt = match(); var h = host(attempt); join(attempt);
        matches.observeHost("ABCDEF",host,false); broker.maintenance();
        failure("match_expired", () -> broker.get(h.leaseId(),hostToken)); assertEquals(2,provider.revoked.size());
        UUID next = match(); var nextH = host(next); join(next);
        for (int i = 0; i < 14; i++) { now.addAndGet(60_000); matches.observeHost("ABCDEF",host,true); broker.maintenance(); broker.get(nextH.leaseId(),hostToken); }
        now.addAndGet(60_001); matches.observeHost("ABCDEF",host,true); broker.maintenance();
        failure("credential_expired", () -> broker.get(nextH.leaseId(),hostToken));
    }
    @Test void journalContainsRevocationDebtAndQuotasButNoPasswordsOrSessionTokens() throws Exception {
        UUID attempt = match(); var h = host(attempt); join(attempt); provider.revokeFails = true; broker.close();
        String file = Files.readString(dir.resolve("issued.json")); assertFalse(file.contains("TOP_SECRET"));
        assertFalse(file.contains(Base64.getEncoder().encodeToString(hostToken)));
        RelayBroker restarted = new RelayBroker(config(),provider,matches,t -> Optional.of(host),now::get);
        restarted.maintenance(); failure("analytics_unavailable", () -> restarted.create("ABCDEF",42,match(),"host",hostToken,"ip",true));
        provider.revokeFails = false; restarted.maintenance(); assertEquals(2,provider.revoked.size());
        assertNull(restarted.create("ABCDEF",42,match(),"host",hostToken,"ip",true).credentials());
    }
    @Test void activeCapacityAndPersistentHourlyAccountQuota() throws Exception {
        List<RelayBroker.LeaseView> waiting = new ArrayList<>();
        for (int i = 0; i < 32; i++) waiting.add(host(match()));
        failure("relay_capacity", () -> host(match())); assertEquals(0,provider.issued);
        for (var lease : waiting) broker.delete(lease.leaseId(),hostToken);
        for (int i = 0; i < 60; i++) { UUID attempt = match(); var h = host(attempt); join(attempt); broker.delete(h.leaseId(),hostToken); }
        UUID exhausted = match(); host(exhausted); failure("relay_rate_limited", () -> join(exhausted)); assertEquals(120,provider.issued);
        RelayBroker restarted = new RelayBroker(config(),provider,matches,t -> Arrays.equals(t,hostToken) ? Optional.of(host) : Optional.of(joiner),now::get);
        restarted.maintenance(); UUID another = match(); restarted.create("ABCDEF",42,another,"host",hostToken,"203.0.113.10",true);
        failure("relay_rate_limited", () -> restarted.create("ABCDEF",42,another,"joiner",joinToken,"203.0.113.11",true));
    }
    @Test void renewalDoesNotConsumeTheNewConnectionQuota() {
        UUID active = match(); var h = host(active); var j = join(active);
        broker.endpoint(h.leaseId(), hostToken, new RelayBroker.Endpoint("104.16.1.1", 40000), 1, true);
        broker.endpoint(j.leaseId(), joinToken, new RelayBroker.Endpoint("104.16.1.2", 40001), 1, true);
        for (int i = 0; i < 59; i++) {
            UUID attempt = match(); var other = host(attempt); join(attempt); broker.delete(other.leaseId(), hostToken);
        }
        assertEquals(120, provider.issued);
        assertEquals(2, broker.renew(h.leaseId(), hostToken).generation());
        UUID exhausted = match(); host(exhausted);
        failure("relay_rate_limited", () -> join(exhausted));
        assertEquals(121, provider.issued);
    }

    @Test void newVerifiedBillingPeriodRequiresFullUsageAndSurvivesTwoRestarts() throws Exception {
        provider.bytes = RelayBroker.BUDGET_BYTES; now.addAndGet(60_001); broker.maintenance();
        failure("budget_exhausted", () -> host(match())); broker.close();
        now.addAndGet(86_400_000); provider.bytes = 0; provider.complete = false;
        RelayConfig nextConfig = config();
        RelayBroker next = new RelayBroker(nextConfig, provider, matches,
                token -> Arrays.equals(token, hostToken) ? Optional.of(host) : Optional.of(joiner), now::get);
        next.maintenance(); UUID attempt = match();
        failure("budget_exhausted", () -> next.create("ABCDEF", 42, attempt, "host", hostToken, "ip", true));
        provider.complete = true; now.addAndGet(60_001); matches.observeHost("ABCDEF", host, true); next.maintenance();
        assertNull(next.create("ABCDEF", 42, match(), "host", hostToken, "ip", true).credentials());
        next.close();
        RelayBroker twice = new RelayBroker(nextConfig, provider, matches,
                token -> Arrays.equals(token, hostToken) ? Optional.of(host) : Optional.of(joiner), now::get);
        twice.maintenance(); assertNull(twice.create("ABCDEF", 42, match(), "host", hostToken, "ip", true).credentials());
        twice.close();
    }

    @Test void automaticPeriodRolloverRequiresCompleteFreshUsage() throws Exception {
        broker.close(); RelayConfig original = config();
        provider.automaticPeriod = true;
        provider.period = new TurnProvider.BillingPeriod(original.billingStart(), original.billingEnd());
        broker = new RelayBroker(original, provider, matches, token -> Optional.of(host), now::get);
        broker.maintenance(); provider.bytes = RelayBroker.BUDGET_BYTES;
        now.addAndGet(60_001); broker.maintenance(); failure("budget_exhausted", () -> host(match()));
        now.set(original.billingEnd().toEpochMilli() + 1);
        provider.period = new TurnProvider.BillingPeriod(original.billingEnd(), original.billingEnd().plusSeconds(2_592_000));
        provider.bytes = 0; provider.complete = false; broker.maintenance();
        assertThrows(RelayBroker.Failure.class, () -> host(match()), "New dates alone must not clear cutoff");
        provider.complete = true; now.addAndGet(60_001); broker.maintenance();
        assertNull(host(match()).credentials()); assertEquals(original.billingEnd(), provider.queriedStart);
        provider.periodFails = true; now.addAndGet(120_001); broker.maintenance();
        failure("analytics_unavailable", () -> host(match()));
    }
    @Test void invalidOverflowCannotClearCutoffForNewPeriod() throws Exception {
        provider.bytes = RelayBroker.BUDGET_BYTES; now.addAndGet(60_001); broker.maintenance();
        failure("budget_exhausted", () -> host(match()));
        provider.automaticPeriod = true; now.addAndGet(86_400_000);
        provider.period = new TurnProvider.BillingPeriod(Instant.ofEpochMilli(now.get() - 1000), Instant.ofEpochMilli(now.get() + 86_400_000));
        provider.bytes = Long.MAX_VALUE; provider.sfuBytes = 1; broker.maintenance();
        assertThrows(RelayBroker.Failure.class, () -> host(match()));
    }

    @Test void subscriptionParserPinsIdentityAndRejectsInvalidDatesOrInactiveState() throws Exception {
        String valid = "{\"success\":true,\"errors\":[],\"result\":{\"id\":\"pinned\",\"frequency\":\"monthly\",\"state\":\"Paid\",\"current_period_start\":\"2026-10-04T12:00:00Z\",\"current_period_end\":\"2026-11-04T12:00:00Z\"}}";
        assertEquals(Instant.parse("2026-10-04T12:00:00Z"), CloudflareTurnProvider.parseBillingPeriod(JsonParser.parseString(valid).getAsJsonObject(), "pinned").start());
        for (String invalid : List.of(valid.replace("Paid", "Cancelled"), valid.replace("2026-11-04", "2026-09-04"), valid.replace("pinned", "different"), valid.replace("\"errors\":[]", "\"errors\":[{}]")))
            assertThrows(IOException.class, () -> CloudflareTurnProvider.parseBillingPeriod(JsonParser.parseString(invalid).getAsJsonObject(), "pinned"));
    }

    @Test void providerRejectsPartialMissingAndUnaggregatedAnalytics() throws Exception {
        String good = "{\"data\":{\"viewer\":{\"accounts\":[{\"turn\":[{\"sum\":{\"egressBytes\":12}}],\"sfu\":[{\"sum\":{\"egressBytes\":34}}]}]}},\"errors\":null}";
        assertEquals(46,CloudflareTurnProvider.parseUsage(JsonParser.parseString(good).getAsJsonObject(),false).totalBytes());
        assertThrows(IOException.class, () -> CloudflareTurnProvider.parseUsage(JsonParser.parseString(good.replace("\"sfu\"", "\"missing\"")).getAsJsonObject(),false));
        assertThrows(IOException.class, () -> CloudflareTurnProvider.parseUsage(JsonParser.parseString(good.replace("\"errors\":null", "\"errors\":[{}]")).getAsJsonObject(),false));
    }

    @Test void providerSelectsTurnCredentialsAfterTheLeadingStunEntry() throws Exception {
        String response = "{\"iceServers\":[{\"urls\":[\"stun:stun.cloudflare.com:3478\"]},{\"urls\":[\"turn:turn.cloudflare.com:3478?transport=udp\",\"turns:turn.cloudflare.com:443?transport=tcp\"],\"username\":\"user\",\"credential\":\"password\"}]}";
        var credential = CloudflareTurnProvider.parseCredentials(JsonParser.parseString(response).getAsJsonObject(),now.get()+900_000);
        assertEquals("user",credential.username()); assertEquals(List.of("turn:turn.cloudflare.com:3478?transport=udp", "turn:turn.cloudflare.com:443?transport=udp"), credential.urls());
    }
}
