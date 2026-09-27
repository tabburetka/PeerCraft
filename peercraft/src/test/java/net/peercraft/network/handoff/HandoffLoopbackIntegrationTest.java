package net.peercraft.network.handoff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The full graceful-handoff handshake in a single JVM with no Minecraft and no real sockets:
 * a host-side {@link HandoffCoordinator} and a joiner-side {@link HandoffClientAgent} are
 * cross-wired so each side's send primitive hands the datagram to the other's
 * {@code onPacket}, marshalled through a single-thread executor standing in for the UDP link.
 *
 * <p>Exercises OFFER &rarr; ACCEPT &rarr; (stub transfer) &rarr; MIGRATE &rarr; MIGRATE_OK,
 * plus the decline and host-cancel paths.
 */
class HandoffLoopbackIntegrationTest {

    private static final long AWAIT = 10;

    /** A cross-wired host+joiner pair over a fake "UDP link". */
    private static final class Link {
        final ExecutorService wire = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fake-udp");
            t.setDaemon(true);
            return t;
        });
        final InetAddress lo;
        HandoffCoordinator host;
        HandoffClientAgent joiner;

        Link() throws Exception {
            this.lo = InetAddress.getByName("127.0.0.1");
        }

        void close() {
            wire.shutdownNow();
        }
    }

    private HandoffProtocol.Offer sampleOffer(long id) {
        return new HandoffProtocol.Offer(HandoffProtocol.PROTO_VERSION, id, "Test World", 1_000_000L, 8,
                HandoffProtocol.OFFER_FLAG_PUBLIC_ROOM,
                List.of(new HandoffProtocol.ModRef("sodium", "0.5")), "world-id-abc");
    }

    @Test
    @Timeout(30)
    void happyPathOfferAcceptMigrateOk() throws Exception {
        Link link = new Link();
        UUID successorId = UUID.randomUUID();

        CompletableFuture<Boolean> hostAccepted = new CompletableFuture<>();
        CompletableFuture<Boolean> hostReady = new CompletableFuture<>();
        CompletableFuture<String> hostAborted = new CompletableFuture<>();
        AtomicReference<Boolean> transferRan = new AtomicReference<>(false);

        CompletableFuture<HandoffProtocol.Offer> joinerOffer = new CompletableFuture<>();
        CompletableFuture<UUID> joinerMigrate = new CompletableFuture<>();
        AtomicReference<Boolean> joinerAmSuccessor = new AtomicReference<>(false);

        link.joiner = new HandoffClientAgent(successorId,
                (ip, port, data) -> link.wire.execute(() -> link.host.onPacket(data, data.length, link.lo, 50000)),
                new HandoffClientAgent.Callbacks() {
                    @Override public void onOffer(HandoffProtocol.Offer offer) {
                        joinerOffer.complete(offer);
                        link.joiner.accept();
                    }
                    @Override public void onMigrate(UUID successorAccountId, boolean amSuccessor) {
                        joinerAmSuccessor.set(amSuccessor);
                        joinerMigrate.complete(successorAccountId);
                        // stand in for "my integrated server is up"
                        link.joiner.signalReady();
                    }
                    @Override public void onAborted(String reasonKey) { }
                });

        HandoffCoordinator.Transfer stubTransfer = (onDone, onFail) -> {
            transferRan.set(true);
            onDone.run();
        };
        HandoffCoordinator.Callbacks hostCallbacks = new HandoffCoordinator.Callbacks() {
            @Override public void onAccepted() { hostAccepted.complete(true); }
            @Override public void onDeclined(String reasonKey) { hostAborted.complete("declined:" + reasonKey); }
            @Override public void onSuccessorReady() { hostReady.complete(true); }
            @Override public void onAborted(String reasonKey) { hostAborted.complete(reasonKey); }
            @Override public void onStatus(String messageKey) { }
        };

        HandoffProtocol.Offer offer = sampleOffer(0xABCDEF01L);
        link.host = HandoffCoordinator.start(offer, link.lo, 50000, successorId,
                (ip, port, data) -> link.wire.execute(() -> link.joiner.onPacket(data, data.length, link.lo, 40000)),
                java.util.Collections::emptyList,
                stubTransfer, hostCallbacks);

        assertEquals(offer.offerId(), joinerOffer.get(AWAIT, TimeUnit.SECONDS).offerId());
        assertTrue(hostAccepted.get(AWAIT, TimeUnit.SECONDS));
        assertEquals(successorId, joinerMigrate.get(AWAIT, TimeUnit.SECONDS));
        assertTrue(joinerAmSuccessor.get(), "the accepting client is flagged as the successor");
        assertTrue(hostReady.get(AWAIT, TimeUnit.SECONDS));
        assertTrue(transferRan.get(), "the transfer step ran before MIGRATE");
        assertTrue(link.host.isTerminal());
        assertFalse(hostAborted.isDone());
        link.close();
    }

    @Test
    @Timeout(30)
    void joinerDeclineAbortsOnHost() throws Exception {
        Link link = new Link();
        UUID successorId = UUID.randomUUID();
        CompletableFuture<String> hostAborted = new CompletableFuture<>();

        link.joiner = new HandoffClientAgent(successorId,
                (ip, port, data) -> link.wire.execute(() -> link.host.onPacket(data, data.length, link.lo, 50000)),
                new HandoffClientAgent.Callbacks() {
                    @Override public void onOffer(HandoffProtocol.Offer offer) {
                        link.joiner.decline("peercraft.handoff.decline.missing_mods");
                    }
                    @Override public void onMigrate(UUID a, boolean b) { }
                    @Override public void onAborted(String reasonKey) { }
                });

        link.host = HandoffCoordinator.start(sampleOffer(7), link.lo, 50000, successorId,
                (ip, port, data) -> link.wire.execute(() -> link.joiner.onPacket(data, data.length, link.lo, 40000)),
                java.util.Collections::emptyList,
                (onDone, onFail) -> fail("transfer must not run after a decline"),
                new HandoffCoordinator.Callbacks() {
                    @Override public void onAccepted() { fail("must not accept"); }
                    @Override public void onDeclined(String reasonKey) { hostAborted.complete(reasonKey); }
                    @Override public void onSuccessorReady() { }
                    @Override public void onAborted(String reasonKey) { hostAborted.complete(reasonKey); }
                    @Override public void onStatus(String messageKey) { }
                });

        assertEquals("peercraft.handoff.decline.missing_mods", hostAborted.get(AWAIT, TimeUnit.SECONDS));
        assertTrue(link.host.isTerminal());
        link.close();
    }

    @Test
    @Timeout(30)
    void hostCancelNotifiesAndIsTerminal() throws Exception {
        Link link = new Link();
        UUID successorId = UUID.randomUUID();
        CompletableFuture<String> joinerAborted = new CompletableFuture<>();
        CompletableFuture<String> hostAborted = new CompletableFuture<>();

        link.joiner = new HandoffClientAgent(successorId,
                (ip, port, data) -> link.wire.execute(() -> link.host.onPacket(data, data.length, link.lo, 50000)),
                new HandoffClientAgent.Callbacks() {
                    @Override public void onOffer(HandoffProtocol.Offer offer) { /* sit on it */ }
                    @Override public void onMigrate(UUID a, boolean b) { }
                    @Override public void onAborted(String reasonKey) { joinerAborted.complete(reasonKey); }
                });

        link.host = HandoffCoordinator.start(sampleOffer(9), link.lo, 50000, successorId,
                (ip, port, data) -> link.wire.execute(() -> link.joiner.onPacket(data, data.length, link.lo, 40000)),
                java.util.Collections::emptyList,
                (onDone, onFail) -> fail("no transfer"),
                new HandoffCoordinator.Callbacks() {
                    @Override public void onAccepted() { }
                    @Override public void onDeclined(String reasonKey) { }
                    @Override public void onSuccessorReady() { }
                    @Override public void onAborted(String reasonKey) { hostAborted.complete(reasonKey); }
                    @Override public void onStatus(String messageKey) { }
                });

        // let the offer reach the joiner, then cancel
        Thread.sleep(500);
        link.host.cancel("peercraft.handoff.abort.picked_someone_else");

        assertEquals("peercraft.handoff.abort.picked_someone_else", hostAborted.get(AWAIT, TimeUnit.SECONDS));
        assertEquals("peercraft.handoff.abort.picked_someone_else", joinerAborted.get(AWAIT, TimeUnit.SECONDS));
        assertTrue(link.host.isTerminal());
        link.close();
    }
}
