package net.peercraft.network.handoff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end world-archive transfer in one JVM, no sockets: a host {@link WorldTransfer} and a
 * receiver {@link WorldTransfer} cross-wired through a single-thread executor that stands in
 * for the punched UDP link, optionally dropping a fraction of datagrams to exercise the
 * selective-ACK repair loop.
 */
class WorldTransferLoopbackIntegrationTest {

    private static final long AWAIT = 30;

    @Test
    @Timeout(60)
    void transfersAMultiChunkArchiveCleanly(@TempDir Path dir) throws Exception {
        run(dir, 250_000, 0.0);
    }

    @Test
    @Timeout(90)
    void transfersWithPacketLossViaRepair(@TempDir Path dir) throws Exception {
        run(dir, 400_000, 0.10);
    }

    @Test
    @Timeout(60)
    void requestsVerifiedResultAgainWhenAllInitialDonePacketsAreLost(@TempDir Path dir) throws Exception {
        run(dir, 250_000, 0.0, true);
    }

    private void run(Path dir, int archiveBytes, double dropRate) throws Exception {
        run(dir, archiveBytes, dropRate, false);
    }
    private void run(Path dir, int archiveBytes, double dropRate, boolean loseDone) throws Exception {
        byte[] archive = new byte[archiveBytes];
        new Random(42).nextBytes(archive);
        Path src = dir.resolve("world.zip");
        Files.write(src, archive);
        byte[] sha = sha512(archive);
        long transferId = ThreadLocalRandom.current().nextLong();

        ExecutorService wire = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fake-udp");
            t.setDaemon(true);
            return t;
        });
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        Random drop = new Random(7);

        CompletableFuture<Path> received = new CompletableFuture<>();
        CompletableFuture<Boolean> hostDone = new CompletableFuture<>();
        AtomicLong lastRecvProgress = new AtomicLong();

        WorldTransfer[] host = new WorldTransfer[1];
        WorldTransfer[] recv = new WorldTransfer[1];

        WorldTransfer.Sender toRecv = (ip, port, data) -> {
            if (dropRate > 0 && drop.nextDouble() < dropRate) return;
            wire.execute(() -> recv[0].onPacket(data, data.length, lo, 40000));
        };
        java.util.concurrent.atomic.AtomicInteger donePackets = new java.util.concurrent.atomic.AtomicInteger();
        WorldTransfer.Sender toHost = (ip, port, data) -> {
            if (loseDone && WorldTransferProtocol.messageType(data, data.length) == WorldTransferProtocol.T_DONE
                    && donePackets.incrementAndGet() <= 5) return;
            if (dropRate > 0 && drop.nextDouble() < dropRate) return;
            wire.execute(() -> host[0].onPacket(data, data.length, lo, 50000));
        };

        recv[0] = WorldTransfer.receiver(transferId, dir.resolve("in.part"), 8L * 1024 * 1024,
                toHost, lo, 40000, new WorldTransfer.ReceiverCallbacks() {
                    @Override public void onProgress(long r, long total) { lastRecvProgress.set(r); }
                    @Override public void onComplete(Path verifiedZip) { received.complete(verifiedZip); }
                    @Override public void onFailed(String reasonKey) { received.completeExceptionally(new AssertionError("recv failed: " + reasonKey)); }
                });

        host[0] = WorldTransfer.host(transferId, src, archive.length, sha,
                toRecv, lo, 50000, new WorldTransfer.HostCallbacks() {
                    @Override public void onProgress(long acked, long total) { }
                    @Override public void onComplete() { hostDone.complete(true); }
                    @Override public void onFailed(String reasonKey) { hostDone.completeExceptionally(new AssertionError("host failed: " + reasonKey)); }
                });

        host[0].startHost();

        Path got = received.get(AWAIT, TimeUnit.SECONDS);
        assertArrayEquals(archive, Files.readAllBytes(got), "reassembled archive matches the source");
        assertTrue(hostDone.get(AWAIT, TimeUnit.SECONDS));
        wire.shutdownNow();
    }

    private static byte[] sha512(byte[] b) throws Exception {
        return MessageDigest.getInstance("SHA-512").digest(b);
    }
    @Test void retainedDoneIsBoundToTheVerifiedSnapshot(@TempDir Path dir) throws Exception {
        byte[] data = new byte[]{4}, hash = sha512(data); InetAddress lo = InetAddress.getLoopbackAddress();
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        WorldTransfer receiver = WorldTransfer.receiver(1, dir.resolve("in.part"), 1024,
                (ip, port, packet) -> { if (WorldTransferProtocol.messageType(packet, packet.length) == WorldTransferProtocol.T_DONE) done.incrementAndGet(); },
                lo, 1000, new WorldTransfer.ReceiverCallbacks() {
                    public void onProgress(long a, long b) { }
                    public void onComplete(Path p) { }
                    public void onFailed(String reason) { fail(reason); }
                });
        byte[] begin = WorldTransferProtocol.encodeBegin(1, 1, 1, hash, WorldTransferProtocol.CHUNK_PAYLOAD);
        receiver.onPacket(begin, begin.length, lo, 1000);
        byte[] chunk = WorldTransferProtocol.encodeChunk(1, 0, data, 0, 1);
        receiver.onPacket(chunk, chunk.length, lo, 1000);
        assertEquals(5, done.get());
        byte[] wrongHash = hash.clone(); wrongHash[0] ^= 1;
        byte[] wrong = WorldTransferProtocol.encodeBegin(1, 1, 1, wrongHash, WorldTransferProtocol.CHUNK_PAYLOAD);
        receiver.onPacket(wrong, wrong.length, lo, 1000); assertEquals(5, done.get());
        receiver.onPacket(begin, begin.length, lo, 1000); assertEquals(6, done.get());
    }

}
