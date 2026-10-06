package net.peercraft.rendezvous.relay;

import java.util.*;
import java.util.function.LongSupplier;

/** Admission is fed only by accepted UDP REGISTER/JOIN, never by HTTP claims. */
public final class RelayMatchRegistry {
    public record Match(String roomCode, long pairToken, UUID attemptId, UUID hostAccount,
                        UUID joinerAccount, boolean eligible, long createdAt, UUID hostAttempt) { }
    private record Host(UUID account, boolean consent, long seenAt, UUID attempt) { }
    private final Map<String, Host> hosts = new HashMap<>();
    private final Map<UUID, Match> matches = new HashMap<>();
    private final LongSupplier clock;
    public RelayMatchRegistry(LongSupplier clock) { this.clock = clock; }
    public synchronized void observeHost(String code, UUID account, boolean consent) {
        observeHost(code, account, consent, null);
    }
    public synchronized void observeHost(String code, UUID account, boolean consent, UUID attempt) {
        hosts.put(code, new Host(account, consent, clock.getAsLong(), attempt));
    }
    public synchronized void observeMatch(String room, long token, UUID attempt, UUID host, UUID joiner, boolean eligible) {
        Match previous = matches.get(attempt);
        if (previous != null) return; // retransmissions cannot extend the initial admission window
        matches.entrySet().removeIf(e -> clock.getAsLong() - e.getValue().createdAt() > 24 * 60 * 60_000L);
        if (matches.size() >= 10_000) return;
        Host roomHost = hosts.get(room);
        matches.put(attempt, new Match(room, token, attempt, host, joiner, eligible, clock.getAsLong(), roomHost == null ? null : roomHost.attempt()));
    }
    public synchronized Match admit(String room, long token, UUID attempt, String role, UUID account) {
        Match match = matches.get(attempt);
        if (match == null || !match.roomCode().equals(room) || match.pairToken() != token
                || clock.getAsLong() - match.createdAt() > 120_000L || !live(match)
                || !Objects.equals(account, "host".equals(role) ? match.hostAccount() : match.joinerAccount())) return null;
        return match;
    }
    public synchronized boolean live(Match match) {
        Host host = hosts.get(match.roomCode());
        return match.eligible() && match.hostAccount() != null && match.joinerAccount() != null
                && host != null && host.consent() && Objects.equals(host.account(), match.hostAccount())
                && Objects.equals(host.attempt(), match.hostAttempt())
                && clock.getAsLong() - host.seenAt() <= 120_000L;
    }
}
