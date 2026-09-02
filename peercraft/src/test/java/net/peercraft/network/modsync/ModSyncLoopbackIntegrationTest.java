package net.peercraft.network.modsync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end mod-sync handshake + peer-to-peer jar transfer in a single JVM, no Minecraft and
 * no real sockets: two {@link ModSyncCoordinator}s (one {@code host()}, one {@code joiner()})
 * are cross-wired so each one's {@link ModSyncCoordinator.Sender} hands the datagram to the
 * other's {@link ModSyncCoordinator#onPacket}, marshalled through a single-thread executor that
 * stands in for the UDP link. This is the "verify mod sync works without a runtime" path — it
 * exercises the real {@code T_HELLO}/{@code T_MANIFEST}/{@code T_FILE_*} state machine,
 * chunking, selective-ACK repair and SHA-512 verification the Forge 1.7.10 / 1.12.2 backports
 * reuse verbatim from {@code src/modsync-java8}.
 */
class ModSyncLoopbackIntegrationTest {

    private static final long AWAIT_SECONDS = 20;

    @Test
    @Timeout(40)
    void joinerFetchesAMissingJarFromTheHostOverLoopback(@TempDir Path dir) throws Exception {
        byte[] alphaBytes = randomBytes(4_096, 1);
        byte[] betaBytes = randomBytes(21_000, 2); // spans several FILE_CHUNKs

        Map<String, byte[]> jars = new HashMap<>();
        jars.put("alpha-lib", alphaBytes);
        jars.put("beta-widgets", betaBytes);
        List<ModEntry> hostMods = Arrays.asList(
                entry("alpha-lib", "1.0.0", alphaBytes),
                entry("beta-widgets", "1.2.0", betaBytes));

        // Joiner already has alpha-lib but not beta-widgets.
        List<ModSyncProtocol.ModRef> joinerMods =
                Arrays.asList(new ModSyncProtocol.ModRef("alpha-lib", "1.0.0"));

        Harness h = start(dir, new FakeHostProvider(hostMods, jars, dir.resolve("host-serve")), joinerMods, 0.0);
        try {
            List<ModEntry> missing = h.handler.manifest.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals(1, missing.size(), "exactly the one jar the joiner lacks");
            ModEntry beta = missing.get(0);
            assertEquals("beta-widgets", beta.id());

            CompletableFuture<Path> done =
                    h.handler.completed.computeIfAbsent("beta-widgets", k -> new CompletableFuture<>());
            h.joiner.requestFile(beta);

            Path got = done.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(Files.exists(got), "verified part file exists");
            assertArrayEquals(betaBytes, Files.readAllBytes(got), "transferred bytes match the host jar");
        } finally {
            h.close();
        }
    }

    @Test
    @Timeout(60)
    void transferCompletesDespiteHeavyDatagramLoss(@TempDir Path dir) throws Exception {
        byte[] alphaBytes = randomBytes(4_096, 1);
        byte[] betaBytes = randomBytes(220_000, 2); // ~37 FILE_CHUNKs — real work for the repair loop

        Map<String, byte[]> jars = new HashMap<>();
        jars.put("alpha-lib", alphaBytes);
        jars.put("beta-widgets", betaBytes);
        List<ModEntry> hostMods = Arrays.asList(
                entry("alpha-lib", "1.0.0", alphaBytes),
                entry("beta-widgets", "1.2.0", betaBytes));
        List<ModSyncProtocol.ModRef> joinerMods =
                Arrays.asList(new ModSyncProtocol.ModRef("alpha-lib", "1.0.0"));

        // 35% of every datagram (chunks, ACKs, pings) is dropped — the NACK repair must still converge.
        Harness h = start(dir, new FakeHostProvider(hostMods, jars, dir.resolve("host-serve")), joinerMods, 0.35);
        try {
            List<ModEntry> missing = h.handler.manifest.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertEquals(1, missing.size());
            ModEntry beta = missing.get(0);

            CompletableFuture<Path> done =
                    h.handler.completed.computeIfAbsent("beta-widgets", k -> new CompletableFuture<>());
            h.joiner.requestFile(beta);

            Path got = done.get(50, TimeUnit.SECONDS);
            assertArrayEquals(betaBytes, Files.readAllBytes(got), "lossy transfer still reassembles bit-for-bit");
        } finally {
            h.close();
        }
    }

    @Test
    @Timeout(40)
    void joinerWithEveryHostModProceedsWithoutAManifest(@TempDir Path dir) throws Exception {
        byte[] alphaBytes = randomBytes(4_096, 1);
        byte[] betaBytes = randomBytes(9_000, 2);
        Map<String, byte[]> jars = new HashMap<>();
        jars.put("alpha-lib", alphaBytes);
        jars.put("beta-widgets", betaBytes);
        List<ModEntry> hostMods = Arrays.asList(
                entry("alpha-lib", "1.0.0", alphaBytes),
                entry("beta-widgets", "1.2.0", betaBytes));

        List<ModSyncProtocol.ModRef> joinerMods = Arrays.asList(
                new ModSyncProtocol.ModRef("alpha-lib", "whatever"),
                new ModSyncProtocol.ModRef("beta-widgets", "also-different"));

        Harness h = start(dir, new FakeHostProvider(hostMods, jars, dir.resolve("host-serve")), joinerMods, 0.0);
        try {
            h.handler.nothingMissing.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertFalse(h.handler.manifest.isDone(), "no manifest is offered when nothing is missing");
        } finally {
            h.close();
        }
    }

    // ================= harness =================

    private static Harness start(Path dir, FakeHostProvider provider,
                                 List<ModSyncProtocol.ModRef> joinerMods, double dropRate) throws Exception {
        Path joinerTmp = dir.resolve("joiner-tmp");
        Files.createDirectories(joinerTmp);
        Files.createDirectories(provider.tmp);

        ExecutorService net = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "modsync-loopback-net");
            t.setDaemon(true);
            return t;
        });
        InetAddress lo = InetAddress.getLoopbackAddress();
        ModSyncCoordinator[] hostRef = new ModSyncCoordinator[1];
        ModSyncCoordinator[] joinRef = new ModSyncCoordinator[1];

        ModSyncCoordinator.Sender toHost = data -> {
            if (dropRate > 0 && ThreadLocalRandom.current().nextDouble() < dropRate) {
                return;
            }
            byte[] copy = data.clone();
            net.execute(() -> {
                ModSyncCoordinator c = hostRef[0];
                if (c != null) {
                    c.onPacket(copy, copy.length, lo, 55001);
                }
            });
        };
        ModSyncCoordinator.Sender toJoiner = data -> {
            if (dropRate > 0 && ThreadLocalRandom.current().nextDouble() < dropRate) {
                return;
            }
            byte[] copy = data.clone();
            net.execute(() -> {
                ModSyncCoordinator c = joinRef[0];
                if (c != null) {
                    c.onPacket(copy, copy.length, lo, 55002);
                }
            });
        };

        ModSyncCoordinator host = ModSyncCoordinator.host(toJoiner, provider, provider.tmp);
        hostRef[0] = host;

        TestJoinerHandler handler = new TestJoinerHandler();
        ModSyncCoordinator joiner = ModSyncCoordinator.joiner(
                toHost, ModSyncProtocol.Loader.FORGE, joinerMods, joinerTmp, 64L * 1024 * 1024, handler);
        joinRef[0] = joiner;
        joiner.startJoiner();

        return new Harness(host, joiner, handler, net);
    }

    private static final class Harness {
        final ModSyncCoordinator host;
        final ModSyncCoordinator joiner;
        final TestJoinerHandler handler;
        final ExecutorService net;

        Harness(ModSyncCoordinator host, ModSyncCoordinator joiner, TestJoinerHandler handler, ExecutorService net) {
            this.host = host;
            this.joiner = joiner;
            this.handler = handler;
            this.net = net;
        }

        void close() {
            joiner.cancel();
            host.cancel();
            net.shutdownNow();
        }
    }

    private static ModEntry entry(String id, String version, byte[] bytes) throws Exception {
        byte[] sha = MessageDigest.getInstance("SHA-512").digest(bytes);
        return new ModEntry(id, version, bytes.length, sha, id + "-" + version + ".jar",
                ModEntry.Env.BOTH, "", "");
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static final class FakeHostProvider implements ModSyncHostProvider {
        private final List<ModEntry> mods;
        private final Map<String, byte[]> jars;
        final Path tmp;

        FakeHostProvider(List<ModEntry> mods, Map<String, byte[]> jars, Path tmp) {
            this.mods = mods;
            this.jars = jars;
            this.tmp = tmp;
        }

        @Override
        public ModSyncProtocol.Loader loader() {
            return ModSyncProtocol.Loader.FORGE;
        }

        @Override
        public List<ModEntry> hostMods() {
            return new ArrayList<>(mods);
        }

        @Override
        public InputStream openJar(String modId) {
            return new ByteArrayInputStream(jars.get(modId));
        }

        @Override
        public Path servingTempDir() {
            return tmp;
        }
    }

    private static final class TestJoinerHandler implements ModSyncCoordinator.JoinerHandler {
        final CompletableFuture<List<ModEntry>> manifest = new CompletableFuture<>();
        final CompletableFuture<Void> nothingMissing = new CompletableFuture<>();
        final CompletableFuture<String> aborted = new CompletableFuture<>();
        final Map<String, CompletableFuture<Path>> completed = new ConcurrentHashMap<>();
        final Map<String, CompletableFuture<String>> failed = new ConcurrentHashMap<>();

        @Override
        public void onManifest(List<ModEntry> missing) {
            manifest.complete(missing);
        }

        @Override
        public void onNothingMissing() {
            nothingMissing.complete(null);
        }

        @Override
        public void onHandshakeTimeout() {
            aborted.complete("peercraft.modsync.handshake_timeout");
        }

        @Override
        public void onAbort(String reasonKey) {
            aborted.complete(reasonKey);
        }

        @Override
        public void onFileProgress(String modId, long received, long total) {
        }

        @Override
        public void onFileComplete(String modId, Path verifiedPartFile) {
            completed.computeIfAbsent(modId, k -> new CompletableFuture<>()).complete(verifiedPartFile);
        }

        @Override
        public void onFileFailed(String modId, String reasonKey) {
            failed.computeIfAbsent(modId, k -> new CompletableFuture<>()).complete(reasonKey);
        }
    }
}
