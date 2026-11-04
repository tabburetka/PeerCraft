package net.peercraft.network.relay;

import net.peercraft.network.modsync.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Real mod-sync state machines over encrypted UDP TURN allocations, before Minecraft login. */
class RelayModSyncIntegrationTest {
    @Test @Timeout(40)
    void missingModIsVerifiedAfterTransferThroughTurn(@TempDir Path dir) throws Exception {
        runModSync(dir, false);
    }
    @Test @Timeout(40)
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="PEERCRAFT_TEST_COTURN_BIN", matches=".+")
    void missingModIsVerifiedThroughActualCoturn(@TempDir Path dir) throws Exception {
        runModSync(dir, true);
    }
    private void runModSync(Path dir, boolean actualCoturn) throws Exception {
        byte[] bytes = new byte[220_000]; new Random(43).nextBytes(bytes);
        ModEntry entry = new ModEntry("relay-test", "1.0", bytes.length,
                MessageDigest.getInstance("SHA-512").digest(bytes), "relay-test.jar", ModEntry.Env.BOTH, "", "");
        Path hostTmp = Files.createDirectory(dir.resolve("host")), joinTmp = Files.createDirectory(dir.resolve("join"));
        try (RelayTestRig turn = new RelayTestRig(dir, actualCoturn)) {
            RelayPeerTransportIntegrationTest.Broker broker = turn.broker;
            Endpoint hostEvents = new Endpoint(), joinEvents = new Endpoint();
            RelayPeerTransport hostRoute = broker.route(true, hostEvents), joinRoute = broker.route(false, joinEvents);
            ModSyncCoordinator host = null, join = null;
            try {
                hostRoute.start(); joinRoute.start();
                assertTrue(hostEvents.ready.await(5, TimeUnit.SECONDS)); assertTrue(joinEvents.ready.await(5, TimeUnit.SECONDS));
                ModSyncHostProvider provider = new ModSyncHostProvider() {
                    public ModSyncProtocol.Loader loader() { return ModSyncProtocol.Loader.FABRIC; }
                    public List<ModEntry> hostMods() { return Collections.singletonList(entry); }
                    public InputStream openJar(String id) { return new ByteArrayInputStream(bytes); }
                    public Path servingTempDir() { return hostTmp; }
                };
                CompletableFuture<List<ModEntry>> manifest = new CompletableFuture<>();
                CompletableFuture<Path> completed = new CompletableFuture<>();
                host = ModSyncCoordinator.host(data -> send(hostRoute, data), provider, hostTmp);
                join = ModSyncCoordinator.joiner(data -> send(joinRoute, data), ModSyncProtocol.Loader.FABRIC,
                        Collections.emptyList(), joinTmp, 1_000_000, new ModSyncCoordinator.JoinerHandler() {
                            public void onManifest(List<ModEntry> missing) { manifest.complete(missing); }
                            public void onNothingMissing() { manifest.completeExceptionally(new AssertionError("missing manifest")); }
                            public void onHandshakeTimeout() { manifest.completeExceptionally(new AssertionError("handshake timeout")); }
                            public void onAbort(String reason) { completed.completeExceptionally(new AssertionError(reason)); }
                            public void onFileProgress(String id, long received, long total) { }
                            public void onFileComplete(String id, Path file) { completed.complete(file); }
                            public void onFileFailed(String id, String reason) { completed.completeExceptionally(new AssertionError(reason)); }
                        });
                hostEvents.coordinator = host; joinEvents.coordinator = join;
                join.startJoiner();
                List<ModEntry> missing = manifest.get(10, TimeUnit.SECONDS);
                assertEquals(1, missing.size()); join.requestFile(missing.get(0));
                assertArrayEquals(bytes, Files.readAllBytes(completed.get(15, TimeUnit.SECONDS)));
                assertNull(hostEvents.failure.get()); assertNull(joinEvents.failure.get());
                turn.assertHealthy();
            } finally {
                if (join != null) join.cancel(); if (host != null) host.cancel();
                hostRoute.close(); joinRoute.close();
            }
        }
    }
    private static void send(RelayPeerTransport route, byte[] data) {
        try { route.send(data); } catch (IOException error) { throw new IllegalStateException(error); }
    }
    private static final class Endpoint implements RelayPeerTransport.Listener {
        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicReference<String> failure = new AtomicReference<>();
        volatile ModSyncCoordinator coordinator;
        public void onConnected(RelayPeerTransport route) { ready.countDown(); }
        public void onFailure(String reason) { failure.set(reason); }
        public void onData(byte[] data) {
            ModSyncCoordinator target = coordinator;
            if (target != null) target.onPacket(data, data.length, InetAddress.getLoopbackAddress(), 50000);
        }
    }
}
