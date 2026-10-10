package net.peercraft.network.p2p;

import net.peercraft.network.account.AccountClient;
import net.peercraft.network.connectivity.DirectConnectivityCoordinator;
import net.peercraft.network.transport.PeerTransport;
import net.peercraft.network.modsync.ModSyncAgent;
import net.peercraft.network.modsync.ModSyncLink;
import net.peercraft.network.rendezvous.PunchCoordinator;
import net.peercraft.network.rendezvous.RendezvousClient;
import net.peercraft.network.rendezvous.RendezvousProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class P2PBridgeJoinRetryTest {
    private P2PBridge bridge;
    private DatagramSocket rendezvous;
    private Object previousSession;
    private final Map<String, String> properties = new HashMap<>();

    @BeforeEach void setup() throws Exception {
        var constructor = P2PBridge.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        bridge = constructor.newInstance();
        rendezvous = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        previousSession = field(AccountClient.class, "currentSession").get(AccountClient.INSTANCE);
        field(AccountClient.class, "currentSession").set(AccountClient.INSTANCE,
                new AccountClient.AccountSession(UUID.randomUUID(), new byte[16], new byte[16], false, "ABCDEF", "Tester"));
        property("proxyPort", "0");
        property("clientUdpPort", "0");
        property("modSync.client", "off");
    }

    @AfterEach void cleanup() throws Exception {
        bridge.stop();
        rendezvous.close();
        field(AccountClient.class, "currentSession").set(AccountClient.INSTANCE, previousSession);
        properties.forEach((key, value) -> {
            if (value == null) System.clearProperty(key); else System.setProperty(key, value);
        });
    }

    @Test void cancelDuringRendezvousAllowsImmediateRetryAndIgnoresOldMatch() throws Exception {
        Listener first = start(null);
        RendezvousClient.MatchCallback old = match();
        first.attempt.cancel();
        Listener retry = start(null);
        Object currentInbound = inbound();
        old.onMatched(new RendezvousProtocol.Address(InetAddress.getLoopbackAddress(), 54321), 123);
        old.onFailed("late-timeout");
        first.attempt.cancel(); // An old cancel button or watchdog cannot cancel the retry.
        assertSame(currentInbound, inbound());
        assertTrue(retry.attempt.isBusy());
        assertFalse(first.attempt.isCurrent());
        assertEquals(0, first.connected);
        assertNull(first.failure);
        assertNull(retry.failure);
    }

    @Test void punchFailureReleasesJoinImmediately() throws Exception {
        Listener first = start(null);
        PunchCoordinator.Callback punch = punch();
        punch.onFailure("timeout");
        assertEquals("peercraft.p2p.fail.hole_punching", first.failure);
        assertFalse(first.attempt.isBusy());
        assertNull(inbound());
        Listener retry = start(null);
        assertTrue(retry.attempt.isBusy());
        assertNull(retry.failure);
        punch.onSuccess("127.0.0.1", 54321);
        assertEquals(0, first.connected);
        assertTrue(retry.attempt.isBusy());
    }

    @Test void cancelDuringPunchIgnoresLateSuccessAndFailure() throws Exception {
        Listener first = start(null);
        PunchCoordinator.Callback old = punch();
        first.attempt.cancel();
        Listener retry = start(null);
        Object currentInbound = inbound();
        old.onSuccess("127.0.0.1", 54321);
        old.onFailure("timeout");
        assertSame(currentInbound, inbound());
        assertTrue(retry.attempt.isBusy());
        assertEquals(0, first.connected);
        assertNull(first.failure);
    }

    @Test void cancelAfterPunchBeforeMinecraftSocketAllowsRetry() throws Exception {
        Listener first = start(null);
        punch().onSuccess("127.0.0.1", 54321);
        assertEquals(1, first.connected);
        first.attempt.cancel();
        Listener retry = start(null);
        assertTrue(retry.attempt.isBusy());
        assertNull(field(P2PBridge.class, "clientTargetPeer").get(bridge));
    }

    @Test void cancelVanillaConnectionClosesSessionAndOldSocketTeardownCannotReleaseRetry() throws Exception {
        Listener first = start(null);
        punch().onSuccess("127.0.0.1", 54321);
        try (Socket socket = new Socket()) {
            long session = bridge.beginClientSession(socket);
            assertTrue(bridge.isClientSessionActive());
            first.attempt.cancel();
            assertFalse(bridge.isClientSessionActive());
            Listener retry = start(null);
            bridge.endClientSession(session);
            assertTrue(retry.attempt.isBusy());
        }
    }

    @Test void lateModSyncOutcomeAndUnbindCannotTouchRetry() throws Exception {
        property("modSync.client", "all");
        Agent agent = new Agent();
        Listener first = start(agent);
        punch().onSuccess("127.0.0.1", 54321);
        assertNotNull(agent.outcome);
        first.attempt.cancel();
        assertTrue(agent.cancelled);
        Listener retry = start(null);
        Object currentInbound = inbound();
        agent.link.unbind();
        agent.outcome.proceedToConnect();
        agent.outcome.fail("late-failure");
        agent.outcome.abortJoin();
        assertSame(currentInbound, inbound());
        assertTrue(retry.attempt.isBusy());
        assertEquals(0, first.connected);
        assertNull(first.failure);
    }

    @Test void negotiatedNatFailureAlsoAllowsImmediateRetry() throws Exception {
        Listener first = start(null);
        directChecks().onFailure("direct_checks_timeout");
        assertEquals("peercraft.p2p.fail.hole_punching", first.failure);
        assertFalse(first.attempt.isBusy());
        assertTrue(start(null).attempt.isBusy());
    }

    @Test void cancelledDirectRouteCannotFailReplacementRouteToSameHost() throws Exception {
        Listener first = start(null);
        directChecks().onSuccess("127.0.0.1", 54321);
        PeerAddress peer = new PeerAddress(InetAddress.getLoopbackAddress(), 54321);
        Map<?, ?> routes = (Map<?, ?>) field(P2PBridge.class, "peerRoutes").get(bridge);
        PeerTransport oldRoute = (PeerTransport) routes.get(peer);
        assertNotNull(oldRoute);
        first.attempt.cancel();
        Listener retry = start(null);
        directChecks().onSuccess("127.0.0.1", 54321);
        Object replacement = routes.get(peer);
        var failure = P2PBridge.class.getDeclaredMethod("transportFailed", PeerAddress.class,
                PeerTransport.class, String.class, boolean.class);
        failure.setAccessible(true);
        failure.invoke(bridge, peer, oldRoute, "late-failure", false);
        assertSame(replacement, routes.get(peer));
        assertTrue(retry.attempt.isBusy());
        assertNull(retry.failure);
    }

    @Test void normalModSyncAbortReleasesJoinWithoutCancellingItsReturnScreen() throws Exception {
        property("modSync.client", "all");
        Agent agent = new Agent();
        Listener first = start(agent);
        punch().onSuccess("127.0.0.1", 54321);
        agent.outcome.abortJoin();
        assertFalse(first.attempt.isBusy());
        assertFalse(agent.cancelled);
        assertTrue(start(null).attempt.isBusy());
    }

    @Test void repeatedConnectWhileAttemptIsLiveIsStillRejected() {
        Listener first = start(null);
        Listener duplicate = start(null);
        assertEquals("peercraft.p2p.fail.already_connecting", duplicate.failure);
        assertNull(duplicate.attempt);
        assertTrue(first.attempt.isBusy());
    }

    private Listener start(ModSyncAgent agent) {
        Listener listener = new Listener();
        bridge.startClientViaRendezvous("ABCDEF", "127.0.0.1", rendezvous.getLocalPort(), listener, agent);
        return listener;
    }
    private Object inbound() throws Exception { return field(P2PBridge.class, "rendezvousListener").get(bridge); }
    private RendezvousClient.MatchCallback match() throws Exception {
        return (RendezvousClient.MatchCallback) field(RendezvousClient.class, "matchCallback").get(inbound());
    }
    private DirectConnectivityCoordinator.Callback directChecks() throws Exception {
        RendezvousProtocol.Address peer = new RendezvousProtocol.Address(InetAddress.getLoopbackAddress(), 54321);
        var offer = new RendezvousProtocol.NetworkOffer(UUID.randomUUID(), new byte[32], Collections.emptyList(), false, "");
        match().onMatchedDetailed(new RendezvousProtocol.PeerFound(peer, 123, Optional.empty(), Optional.of(offer)));
        var checks = (Map<?, ?>) field(P2PBridge.class, "activeDirectChecks").get(bridge);
        Object coordinator = checks.values().iterator().next();
        return (DirectConnectivityCoordinator.Callback) field(DirectConnectivityCoordinator.class, "callback").get(coordinator);
    }
    private PunchCoordinator.Callback punch() throws Exception {
        match().onMatched(new RendezvousProtocol.Address(InetAddress.getLoopbackAddress(), 54321), 123);
        return (PunchCoordinator.Callback) field(PunchCoordinator.class, "callback").get(inbound());
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private void property(String key, String value) {
        key = "peercraft." + key;
        if (!properties.containsKey(key)) properties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }
    private static final class Listener implements P2PBridge.ConnectListener {
        P2PBridge.ClientJoinAttempt attempt;
        int connected;
        String failure;
        public void onStarted(P2PBridge.ClientJoinAttempt attempt) { this.attempt = attempt; }
        public void onStatus(String message) {}
        public void onConnected() { connected++; }
        public void onFailed(String reason) { failure = reason; }
    }
    private static final class Agent implements ModSyncAgent {
        ModSyncLink link;
        Outcome outcome;
        boolean cancelled;
        public void cancel() { cancelled = true; }
        public void run(ModSyncLink link, Outcome outcome) { this.link = link; this.outcome = outcome; }
    }
}
