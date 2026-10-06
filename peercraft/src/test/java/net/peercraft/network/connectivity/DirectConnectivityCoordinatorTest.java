package net.peercraft.network.connectivity;

import net.peercraft.network.p2p.PeerAddress;
import net.peercraft.network.rendezvous.RendezvousProtocol;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class DirectConnectivityCoordinatorTest {
    private static PeerAddress peer(String address, int port) throws Exception {
        return new PeerAddress(InetAddress.getByName(address), port);
    }
    private static RendezvousProtocol.NetworkOffer offer(UUID attempt, byte[] key, List<RendezvousProtocol.Address> candidates) {
        return new RendezvousProtocol.NetworkOffer(attempt, key, candidates, false, "");
    }

    private static final class Packet {
        final boolean fromHost;
        final byte[] bytes;
        final PeerAddress target;
        Packet(boolean fromHost, byte[] bytes, InetAddress address, int port) {
            this.fromHost = fromHost; this.bytes = bytes; this.target = new PeerAddress(address, port);
        }
    }
    private static final class Simulation {
        final AtomicLong now = new AtomicLong(1);
        final Queue<Packet> packets = new ArrayDeque<>();
        final AtomicInteger hostConnected = new AtomicInteger(), joinerConnected = new AtomicInteger();
        final AtomicInteger hostFailed = new AtomicInteger(), joinerFailed = new AtomicInteger();
        final PeerAddress hostActual, joinerActual;
        final DirectConnectivityCoordinator host, joiner;
        Predicate<Packet> drop = packet -> false;
        Simulation(PeerAddress hostActual, PeerAddress joinerActual, PeerAddress hostObserved,
                   PeerAddress joinerObserved, List<RendezvousProtocol.Address> hostCandidates,
                   List<RendezvousProtocol.Address> joinerCandidates) {
            this.hostActual = hostActual; this.joinerActual = joinerActual;
            UUID attempt = UUID.randomUUID(); byte[] key = new byte[32]; Arrays.fill(key, (byte) 7);
            host = new DirectConnectivityCoordinator((bytes, ip, port) -> packets.add(new Packet(true, bytes, ip, port)),
                    joinerObserved, offer(attempt, key, joinerCandidates), true, callback(hostConnected, hostFailed), now::get);
            joiner = new DirectConnectivityCoordinator((bytes, ip, port) -> packets.add(new Packet(false, bytes, ip, port)),
                    hostObserved, offer(attempt, key, hostCandidates), false, callback(joinerConnected, joinerFailed), now::get);
        }
        private DirectConnectivityCoordinator.Callback callback(AtomicInteger connected, AtomicInteger failed) {
            return new DirectConnectivityCoordinator.Callback() {
                public void onSuccess(String ip, int port) { connected.incrementAndGet(); }
                public void onFailure(String reason) { assertEquals("direct_checks_timeout", reason); failed.incrementAndGet(); }
            };
        }
        void step() {
            host.tick(); joiner.tick(); drain(); now.addAndGet(300_000_000L);
        }
        void drain() {
            int budget = 1000;
            while (!packets.isEmpty()) {
                assertTrue(--budget > 0, "checks must not create an unbounded response loop");
                Packet packet = packets.remove();
                if (drop.test(packet)) continue;
                PeerAddress target = packet.fromHost ? joinerActual : hostActual;
                PeerAddress source = packet.fromHost ? hostActual : joinerActual;
                if (packet.target.equals(target)) (packet.fromHost ? joiner : host)
                        .onPacket(packet.bytes, packet.bytes.length, source.host(), source.port());
            }
        }
        void run(int steps) { for (int i = 0; i < steps; i++) step(); }
    }
    private static Simulation reachable() throws Exception {
        PeerAddress host = peer("203.0.113.1", 10001), joiner = peer("198.51.100.2", 10002);
        return new Simulation(host, joiner, host, joiner, Collections.emptyList(), Collections.emptyList());
    }

    @Test void confirmsBothDirectionsAndCallsEachSideOnce() throws Exception {
        Simulation sim = reachable(); sim.run(5);
        assertEquals(1, sim.hostConnected.get()); assertEquals(1, sim.joinerConnected.get());
        assertEquals(sim.joinerActual, sim.host.selectedPeer()); assertEquals(sim.hostActual, sim.joiner.selectedPeer());
        assertEquals(0, sim.hostFailed.get() + sim.joinerFailed.get());
    }

    @Test void learnsAuthenticatedRemappedPortInsteadOfDeclaringSymmetricNatFailure() throws Exception {
        PeerAddress host = peer("203.0.113.1", 10001), joiner = peer("198.51.100.2", 12000);
        Simulation sim = new Simulation(host, joiner, host, peer("198.51.100.2", 10002), Collections.emptyList(), Collections.emptyList());
        sim.run(5);
        assertEquals(joiner, sim.host.selectedPeer()); assertEquals(1, sim.hostConnected.get());
        assertEquals(1, sim.joinerConnected.get());
    }

    @Test void oneWayReachabilityNeverReportsConnected() throws Exception {
        Simulation sim = reachable(); sim.drop = packet -> packet.fromHost; sim.run(70);
        assertEquals(0, sim.hostConnected.get() + sim.joinerConnected.get());
        assertEquals(1, sim.hostFailed.get()); assertEquals(1, sim.joinerFailed.get());
    }

    @Test void transientLossAndLostNominationAcknowledgementsRecover() throws Exception {
        Simulation sim = reachable(); AtomicInteger initial = new AtomicInteger(), ackLoss = new AtomicInteger();
        sim.drop = packet -> initial.getAndIncrement() < 3
                || (!packet.fromHost && packet.bytes[3] == 4 && ackLoss.getAndIncrement() < 3);
        sim.run(15);
        assertEquals(1, sim.hostConnected.get()); assertEquals(1, sim.joinerConnected.get());
    }

    @Test void authenticatedGlobalIpv6CandidateWorksWhenObservedIpv4DoesNot() throws Exception {
        PeerAddress host = peer("2001:db8::1", 10001), joiner = peer("2001:db8::2", 10002);
        Simulation sim = new Simulation(host, joiner, peer("203.0.113.1", 10001), peer("198.51.100.2", 10002),
                Collections.singletonList(new RendezvousProtocol.Address(host.host(), host.port())),
                Collections.singletonList(new RendezvousProtocol.Address(joiner.host(), joiner.port())));
        sim.run(5);
        assertEquals(host, sim.joiner.selectedPeer()); assertEquals(joiner, sim.host.selectedPeer());
    }

    @Test void staleAttemptAndTamperedProofCannotLearnAnEndpoint() throws Exception {
        Simulation old = reachable(); old.host.tick(); byte[] captured = old.packets.peek().bytes.clone();
        Simulation sim = reachable(); sim.host.tick(); sim.joiner.tick(); sim.packets.clear();
        sim.joiner.onPacket(captured, captured.length, old.hostActual.host(), old.hostActual.port());
        assertTrue(sim.packets.isEmpty());
        sim.host.tick(); byte[] modified = sim.packets.remove().bytes.clone(); modified[modified.length - 1] ^= 1;
        sim.joiner.onPacket(modified, modified.length, sim.hostActual.host(), sim.hostActual.port());
        assertTrue(sim.packets.isEmpty()); assertNull(sim.joiner.selectedPeer());
    }

    @Test void cancellationIsSilentAndRejectsLatePackets() throws Exception {
        Simulation sim = reachable(); sim.host.cancel(); sim.joiner.cancel(); sim.run(75);
        assertTrue(sim.packets.isEmpty()); assertEquals(0, sim.hostConnected.get() + sim.joinerConnected.get());
        assertEquals(0, sim.hostFailed.get() + sim.joinerFailed.get());
    }

    @Test void lateValidatedDirectPathCanWinWhileFallbackIsStillNegotiating() throws Exception {
        Simulation sim = reachable(); sim.drop = packet -> true; sim.run(70);
        assertEquals(1, sim.hostFailed.get()); assertEquals(1, sim.joinerFailed.get());
        sim.drop = packet -> false; sim.run(5);
        assertEquals(1, sim.hostConnected.get()); assertEquals(1, sim.joinerConnected.get());
        assertEquals(1, sim.hostFailed.get()); assertEquals(1, sim.joinerFailed.get());
    }

    @Test void committedAlternativeCancelsAllLateDirectCallbacks() throws Exception {
        Simulation sim = reachable(); sim.drop = packet -> true; sim.run(70);
        sim.host.cancel(); sim.joiner.cancel(); sim.drop = packet -> false; sim.run(110);
        assertEquals(0, sim.hostConnected.get() + sim.joinerConnected.get());
        assertEquals(1, sim.hostFailed.get()); assertEquals(1, sim.joinerFailed.get());
    }

    @Test void gatheringSkipsInvalidPortsAndScopedIpv6() throws Exception {
        assertTrue(DirectCandidates.gather(0).isEmpty());
        assertFalse(DirectCandidates.eligible(InetAddress.getByName("fe80::1")));
        assertFalse(DirectCandidates.eligible(InetAddress.getByName("fd00::1")));
        assertFalse(DirectCandidates.eligible(InetAddress.getByName("127.0.0.1")));
        assertTrue(DirectCandidates.gather(50001).size() <= 8);
    }
}
