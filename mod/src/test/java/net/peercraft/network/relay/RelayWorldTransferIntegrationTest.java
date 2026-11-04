package net.peercraft.network.relay;

import net.peercraft.network.handoff.WorldTransfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RelayWorldTransferIntegrationTest {
    @Test @Timeout(45)
    void verifiedWorldArchiveAndDoneTravelThroughTurn(@TempDir Path dir) throws Exception {
        runTransfer(dir, false, false);
    }
    @Test @Timeout(45)
    void budgetShutdownDuringTransferNeverReportsVerifiedSuccess(@TempDir Path dir) throws Exception {
        runTransfer(dir, true, false);
    }
    @Test @Timeout(45)
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="PEERCRAFT_TEST_COTURN_BIN",matches=".+")
    void verifiedWorldTravelsThroughActualCoturn(@TempDir Path dir) throws Exception { runTransfer(dir,false,true); }
    @Test @Timeout(45)
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="PEERCRAFT_TEST_COTURN_BIN",matches=".+")
    void interruptedActualCoturnTransferPreservesSource(@TempDir Path dir) throws Exception { runTransfer(dir,true,true); }
    private void runTransfer(Path dir, boolean interrupt, boolean actualCoturn) throws Exception {
        byte[] archive = new byte[interrupt ? 8_000_000 : 1_200_000]; new Random(91).nextBytes(archive);
        Path source = Files.write(dir.resolve("world.zip"), archive);
        byte[] hash = MessageDigest.getInstance("SHA-512").digest(archive);
        InetAddress logicalAddress = InetAddress.getLoopbackAddress();
        try (RelayTestRig server = new RelayTestRig(dir, actualCoturn)) {
            RelayPeerTransportIntegrationTest.Broker broker = server.broker;
            Endpoint hostEvents = new Endpoint(), receiverEvents = new Endpoint();
            RelayPeerTransport hostRoute = broker.route(true, hostEvents), receiverRoute = broker.route(false, receiverEvents);
            try {
                hostRoute.start(); receiverRoute.start();
                assertTrue(hostEvents.ready.await(5, TimeUnit.SECONDS)); assertTrue(receiverEvents.ready.await(5, TimeUnit.SECONDS));
                CompletableFuture<Path> received = new CompletableFuture<>(); CompletableFuture<Boolean> done = new CompletableFuture<>();
                WorldTransfer receiver = WorldTransfer.receiver(73, dir.resolve("world.part"), 10_000_000,
                        (ip, port, data) -> send(receiverRoute, data), logicalAddress, 50000, new WorldTransfer.ReceiverCallbacks() {
                            public void onProgress(long receivedBytes, long totalBytes) {
                                if (interrupt && receivedBytes >= 128_000) broker.blocked = true;
                            }
                            public void onComplete(Path file) { received.complete(file); }
                            public void onFailed(String reason) { received.completeExceptionally(new AssertionError(reason)); }
                        });
                WorldTransfer host = WorldTransfer.host(73, source, archive.length, hash,
                        (ip, port, data) -> send(hostRoute, data), logicalAddress, 50000, new WorldTransfer.HostCallbacks() {
                            public void onProgress(long bytes, long total) { }
                            public void onComplete() { done.complete(true); }
                            public void onFailed(String reason) { done.completeExceptionally(new AssertionError(reason)); }
                        });
                receiverEvents.transfer = receiver; hostEvents.transfer = host;
                host.startHost();
                if (interrupt) {
                    RelayPeerTransportIntegrationTest.await(() -> hostEvents.failure.get() != null && receiverEvents.failure.get() != null, 8000);
                    assertEquals("peercraft.p2p.fail.budget_exhausted", hostEvents.failure.get());
                    assertEquals("peercraft.p2p.fail.budget_exhausted", receiverEvents.failure.get());
                    assertTrue(received.isCompletedExceptionally(), "Incomplete archive reports failure, never verified success");
                    assertTrue(done.isCompletedExceptionally(), "Source receives failure, never authoritative DONE");
                    assertArrayEquals(archive, Files.readAllBytes(source), "Original world snapshot is preserved");
                } else {
                    assertArrayEquals(archive, Files.readAllBytes(received.get(25, TimeUnit.SECONDS)));
                    assertTrue(done.get(5, TimeUnit.SECONDS));
                    assertNull(hostEvents.failure.get()); assertNull(receiverEvents.failure.get());
                }
                server.assertHealthy();
            } finally {
                if (hostEvents.transfer != null) hostEvents.transfer.stopAndAwait(2000);
                if (receiverEvents.transfer != null) receiverEvents.transfer.stopAndAwait(2000);
                hostRoute.close(); receiverRoute.close();
            }
        }
    }
    private static void send(RelayPeerTransport route, byte[] bytes) {
        try { route.send(bytes); } catch (IOException error) { throw new IllegalStateException(error); }
    }
    private static final class Endpoint implements RelayPeerTransport.Listener {
        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicReference<String> failure = new AtomicReference<>(); volatile WorldTransfer transfer;
        public void onConnected(RelayPeerTransport route) { ready.countDown(); }
        public void onFailure(String reason) {
            WorldTransfer current = transfer;
            if (current != null) current.transportFailed("peercraft.handoff.abort.transfer_failed");
            failure.set(reason);
        }
        public void onData(byte[] data) {
            WorldTransfer current = transfer;
            if (current != null) current.onPacket(data, data.length, InetAddress.getLoopbackAddress(), 50000);
        }
    }
}
