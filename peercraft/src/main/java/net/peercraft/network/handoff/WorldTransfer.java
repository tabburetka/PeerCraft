package net.peercraft.network.handoff;

import net.peercraft.network.modsync.FileReassembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Peer-to-peer transfer of the world-save archive from the host to the handoff successor,
 * over the {@code 0xE4} {@link WorldTransferProtocol} family. Same proven shape as mod-sync's
 * jar transfer — index-addressed chunks, a first pass then a selective-ACK repair loop,
 * end-to-end SHA-512 — but standalone and with world-scale caps / timeouts.
 *
 * <p>Two roles, each a static factory:
 * <ul>
 *   <li>{@link #host} — reads the zip off disk and serves {@code T_CHUNK}s, repairing against
 *       {@code T_ACK}. Fires {@link HostCallbacks} on DONE / failure.</li>
 *   <li>{@link #receiver} — reassembles into a {@code .part} file via {@link FileReassembler},
 *       ACKs every {@link #ACK_INTERVAL_MILLIS}, verifies the hash, then hands the finished
 *       zip to {@link ReceiverCallbacks}.</li>
 * </ul>
 *
 * <p>No {@code net.minecraft.*}. Java-8-source-clean. Callbacks fire on this class's own
 * daemon threads.
 */
public final class WorldTransfer {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    /** A multi-hundred-MB save on a home uplink can take a long time — well past mod-sync's 30 min. */
    static final long TRANSFER_TIMEOUT_MILLIS = 60L * 60_000L;
    static final long ACK_INTERVAL_MILLIS = 400L;
    static final int MAX_GAPS_PER_ACK = 256;
    static final int CHUNK_BURST = 6;
    /** Hard ceiling on an accepted archive — a sanity bound, not a real limit anyone should hit. */
    static final long MAX_ARCHIVE_BYTES = 4L * 1024L * 1024L * 1024L;
    /** Optional per-chunk send throttle (ms), {@code -Dpeercraft.handoff.sendPacingMillis} (0..50). */
    private static final long CHUNK_PACING_MILLIS =
            Math.max(0L, Math.min(50L, Long.getLong("peercraft.handoff.sendPacingMillis", 0L)));

    public interface Sender {
        void send(InetAddress ip, int port, byte[] data);
    }

    public interface HostCallbacks {
        void onProgress(long ackedBytes, long totalBytes);

        void onComplete();

        void onFailed(String reasonKey);
    }

    public interface ReceiverCallbacks {
        void onProgress(long receivedBytes, long totalBytes);

        /** The archive arrived and its SHA-512 matched — {@code verifiedZip} is the finished file. */
        void onComplete(Path verifiedZip);

        void onFailed(String reasonKey);
    }

    private final boolean isHost;
    private final long transferId;
    private final Sender sender;
    private final InetAddress peerIp;
    private volatile int peerPort;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    /** Host: the send loop is finished, but the coordinator keeps processing packets to catch the authoritative DONE. */
    private final AtomicBoolean sendLoopDone = new AtomicBoolean(false);
    private volatile Thread worker, finalizer;
    private final AtomicBoolean finalizing = new AtomicBoolean();

    // host state
    private Path servingFile;
    private long size;
    private int chunkCount;
    private byte[] sha512;
    private HostCallbacks hostCb;
    private volatile int highestAcked = -1;
    private volatile int[] outstandingGaps = new int[0];

    // receiver state
    private Path partFile;
    private long maxBytes;
    private ReceiverCallbacks recvCb;
    private volatile FileReassembler reassembler;
    private volatile byte[] expectedSha;
    private volatile boolean finished;
    private volatile byte[] verifiedResult;

    private WorldTransfer(boolean isHost, long transferId, Sender sender, InetAddress peerIp, int peerPort) {
        this.isHost = isHost;
        this.transferId = transferId;
        this.sender = sender;
        this.peerIp = peerIp;
        this.peerPort = peerPort;
    }

    public static WorldTransfer host(long transferId, Path zip, long size, byte[] sha512,
                                     Sender sender, InetAddress successorIp, int successorPort,
                                     HostCallbacks cb) {
        WorldTransfer t = new WorldTransfer(true, transferId, sender, successorIp, successorPort);
        t.servingFile = zip;
        t.size = size;
        t.sha512 = sha512;
        t.chunkCount = (int) ((size + WorldTransferProtocol.CHUNK_PAYLOAD - 1) / WorldTransferProtocol.CHUNK_PAYLOAD);
        t.hostCb = cb;
        return t;
    }

    public static WorldTransfer receiver(long transferId, Path partFile, long maxBytes,
                                         Sender sender, InetAddress hostIp, int hostPort,
                                         ReceiverCallbacks cb) {
        WorldTransfer t = new WorldTransfer(false, transferId, sender, hostIp, hostPort);
        t.partFile = partFile;
        t.maxBytes = Math.min(maxBytes <= 0 ? MAX_ARCHIVE_BYTES : maxBytes, MAX_ARCHIVE_BYTES);
        t.recvCb = cb;
        return t;
    }

    public long transferId() {
        return transferId;
    }

    // ================= host =================

    public void startHost() {
        if (!isHost) {
            return;
        }
        Thread t = new Thread(this::runHost, "PeerCraft-WorldTransfer-Send");
        t.setDaemon(true);
        this.worker = t;
        t.start();
    }

    private void runHost() {
        byte[] begin = WorldTransferProtocol.encodeBegin(transferId, size, chunkCount, sha512, WorldTransferProtocol.CHUNK_PAYLOAD);
        long deadline = System.currentTimeMillis() + TRANSFER_TIMEOUT_MILLIS;
        try (RandomAccessFile raf = new RandomAccessFile(servingFile.toFile(), "r")) {
            send(begin);
            for (int i = 0; i < chunkCount && !stopped.get() && !sendLoopDone.get(); i++) {
                sendChunk(raf, i);
                pace(i);
            }
            while (!stopped.get() && !sendLoopDone.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(ACK_INTERVAL_MILLIS);
                int hi = highestAcked;
                int[] gaps = outstandingGaps;
                reportProgress(hi);
                if (hi >= chunkCount && gaps.length == 0) {
                    break;
                }
                if (gaps.length == 0) {
                    send(begin);
                    int from = Math.max(0, hi);
                    for (int i = from; i < chunkCount && !stopped.get(); i++) {
                        sendChunk(raf, i);
                        pace(i - from);
                    }
                    continue;
                }
                for (int g = 0; g < gaps.length && !stopped.get(); g++) {
                    int gap = gaps[g];
                    if (gap >= 0 && gap < chunkCount) {
                        sendChunk(raf, gap);
                        pace(g);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOGGER.warn("[WorldTransfer] Ошибка чтения архива: {}", e.toString());
            send(WorldTransferProtocol.encodeAbort(transferId, "peercraft.handoff.abort.transfer_failed"));
            if (hostCb != null) {
                hostCb.onFailed("peercraft.handoff.abort.transfer_failed");
            }
            return;
        }
        // ACK confirms delivery only. Re-request the verified result until the transfer
        // deadline; a receiver retains DONE for duplicate BEGIN even after closing its file.
        while (!stopped.get() && System.currentTimeMillis() < deadline) {
            send(begin);
            try { Thread.sleep(ACK_INTERVAL_MILLIS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        if (stopped.compareAndSet(false, true) && hostCb != null) {
            hostCb.onFailed("peercraft.handoff.abort.transfer_failed");
        }
    }

    private void sendChunk(RandomAccessFile raf, int index) throws IOException {
        long offset = (long) index * WorldTransferProtocol.CHUNK_PAYLOAD;
        int len = (int) Math.min(WorldTransferProtocol.CHUNK_PAYLOAD, size - offset);
        if (len <= 0) {
            return;
        }
        byte[] buf = new byte[len];
        raf.seek(offset);
        raf.readFully(buf);
        send(WorldTransferProtocol.encodeChunk(transferId, index, buf, 0, len));
    }

    private void reportProgress(int hi) {
        if (hostCb != null) {
            long acked = Math.min(size, (long) Math.max(0, hi) * WorldTransferProtocol.CHUNK_PAYLOAD);
            hostCb.onProgress(acked, size);
        }
    }

    private void pace(int step) throws InterruptedException {
        if (CHUNK_PACING_MILLIS > 0) {
            Thread.sleep(CHUNK_PACING_MILLIS);
        } else if (step % CHUNK_BURST == CHUNK_BURST - 1) {
            Thread.sleep(3);
        }
    }

    // ================= receiver =================

    private void ensureAckThread() {
        if (worker != null) {
            return;
        }
        Thread t = new Thread(this::runReceiverAcks, "PeerCraft-WorldTransfer-Ack");
        t.setDaemon(true);
        this.worker = t;
        t.start();
    }

    private void runReceiverAcks() {
        long deadline = System.currentTimeMillis() + TRANSFER_TIMEOUT_MILLIS;
        while (!stopped.get() && !finished && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(ACK_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            FileReassembler r = reassembler;
            if (r == null) {
                continue;
            }
            int next; int[] gaps; long received;
            synchronized (r) { next = r.nextContiguous(); gaps = r.missingIndices(MAX_GAPS_PER_ACK); received = r.bytesWritten(); }
            send(WorldTransferProtocol.encodeAck(transferId, next, gaps));
            if (recvCb != null) {
                recvCb.onProgress(received, sizeOrZero(r));
            }
        }
        if (!finished && !stopped.get() && recvCb != null) {
            recvCb.onFailed("peercraft.handoff.abort.transfer_failed");
        }
    }

    private static long sizeOrZero(FileReassembler r) {
        return r.partFile() == null ? 0L : r.partFile().toFile().length();
    }

    // ================= inbound demux (from P2PBridge) =================

    public void onPacket(byte[] data, int length, InetAddress ip, int port) {
        if (!peerIp.equals(ip) || peerPort != port) return;
        if (stopped.get()) {
            if (!isHost && verifiedResult != null && WorldTransferProtocol.messageType(data, length) == WorldTransferProtocol.T_BEGIN) {
                try {
                    WorldTransferProtocol.Begin begin = WorldTransferProtocol.decodeBegin(data, length);
                    if (begin.transferId() == transferId && begin.size() == size && begin.chunkCount() == chunkCount
                            && begin.chunkSize() == WorldTransferProtocol.CHUNK_PAYLOAD && expectedSha != null && MessageDigest.isEqual(begin.sha512(), expectedSha)) send(verifiedResult);
                } catch (RuntimeException ignored) { }
            }
            return;
        }
        int type = WorldTransferProtocol.messageType(data, length);
        if (type < 0) {
            return;
        }

        try {
            switch (type) {
                case WorldTransferProtocol.T_ACK: {
                    if (!isHost) {
                        return;
                    }
                    WorldTransferProtocol.Ack ack = WorldTransferProtocol.decodeAck(data, length);
                    if (ack.transferId() != transferId) {
                        return;
                    }
                    int next = ack.nextContiguous();
                    if (next >= 0 && next <= chunkCount) {
                        highestAcked = Math.max(highestAcked, next);
                    }
                    int[] g = ack.gapIndices();
                    outstandingGaps = g != null ? g : new int[0];
                    // A clean "have everything" ACK only stops the send loop; T_DONE (below) is
                    // the authoritative completion signal — it's sent by the receiver only
                    // AFTER the SHA-512 verifies and the finished zip is in hand, so the host's
                    // MIGRATE can't race ahead of the successor being ready to load it.
                    if (next == chunkCount && outstandingGaps.length == 0) {
                        stopSendLoopOnly();
                    }
                    break;
                }
                case WorldTransferProtocol.T_DONE: {
                    if (!isHost) {
                        return;
                    }
                    WorldTransferProtocol.Done done = WorldTransferProtocol.decodeDone(data, length);
                    if (done.transferId() != transferId) {
                        return;
                    }
                    if (stopped.get()) return;
                    stop();
                    if (hostCb != null) {
                        if (done.ok()) {
                            hostCb.onComplete();
                        } else {
                            hostCb.onFailed("peercraft.handoff.abort.transfer_failed");
                        }
                    }
                    break;
                }
                case WorldTransferProtocol.T_BEGIN: {
                    if (isHost) {
                        return;
                    }
                    onBegin(WorldTransferProtocol.decodeBegin(data, length));
                    break;
                }
                case WorldTransferProtocol.T_CHUNK: {
                    if (isHost || reassembler == null) {
                        return;
                    }
                    WorldTransferProtocol.Chunk c = WorldTransferProtocol.decodeChunk(data, length);
                    if (c.transferId() != transferId) {
                        return;
                    }
                    if (finalizing.get()) return;
                    FileReassembler assembly = reassembler;
                    synchronized (assembly) {
                        assembly.accept(c.index(), c.data());
                        if (assembly.isComplete() && finalizing.compareAndSet(false, true)) {
                            Thread hash = new Thread(this::finishReceive, "PeerCraft-WorldTransfer-Verify");
                            hash.setDaemon(true); finalizer = hash; hash.start();
                        }
                    }
                    break;
                }
                case WorldTransferProtocol.T_ABORT: {
                    WorldTransferProtocol.Abort a = WorldTransferProtocol.decodeAbort(data, length);
                    if (a.transferId() != transferId) {
                        return;
                    }
                    stop();
                    String key = a.reasonKey().isEmpty() ? "peercraft.handoff.abort.transfer_failed" : a.reasonKey();
                    if (isHost && hostCb != null) {
                        hostCb.onFailed(key);
                    } else if (!isHost && recvCb != null) {
                        recvCb.onFailed(key);
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (IOException e) {
            LOGGER.warn("[WorldTransfer] Ошибка обработки пакета: {}", e.toString());
            send(WorldTransferProtocol.encodeAbort(transferId, "peercraft.handoff.abort.transfer_failed"));
            stop();
            if (!isHost && recvCb != null) {
                recvCb.onFailed("peercraft.handoff.abort.transfer_failed");
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[WorldTransfer] Ошибка обработки пакета: {}", e.toString());
        }
    }

    private void onBegin(WorldTransferProtocol.Begin begin) throws IOException {
        if (begin.transferId() != transferId || reassembler != null) {
            return;
        }
        if (begin.size() <= 0 || begin.size() > maxBytes || begin.chunkSize() != WorldTransferProtocol.CHUNK_PAYLOAD
                || begin.chunkCount() != (begin.size() + WorldTransferProtocol.CHUNK_PAYLOAD - 1) / WorldTransferProtocol.CHUNK_PAYLOAD) {
            send(WorldTransferProtocol.encodeAbort(transferId, "peercraft.handoff.abort.transfer_failed"));
            if (recvCb != null) {
                recvCb.onFailed("peercraft.handoff.abort.transfer_failed");
            }
            return;
        }
        this.size = begin.size(); this.chunkCount = begin.chunkCount();
        this.expectedSha = begin.sha512();
        this.reassembler = new FileReassembler(partFile, begin.size(), begin.chunkCount(), begin.chunkSize(), maxBytes);
        ensureAckThread();
        LOGGER.info("[WorldTransfer] Начат приём архива мира: {} байт, {} чанков", begin.size(), begin.chunkCount());
    }

    private synchronized void finishReceive() {
        if (finished) {
            return;
        }
        FileReassembler r = reassembler;
        if (r == null || !r.isComplete()) {
            return;
        }
        try {
            byte[] actual = r.finishAndHash();
            if (stopped.get()) return;
            boolean ok = expectedSha != null && Arrays.equals(actual, expectedSha);
            finished = true;
            // Repeat the verified result; subsequent BEGIN also re-requests it.
            byte[] done = WorldTransferProtocol.encodeDone(transferId, ok);
            verifiedResult = done;
            for (int i = 0; i < 5; i++) {
                send(done);
            }
            stop();
            if (recvCb != null) {
                if (ok) {
                    recvCb.onComplete(r.partFile());
                } else {
                    recvCb.onFailed("peercraft.handoff.abort.transfer_failed");
                }
            }
        } catch (IOException e) {
            if (stopped.get()) return;
            LOGGER.warn("[WorldTransfer] Ошибка финализации архива: {}", e.toString());
            send(WorldTransferProtocol.encodeDone(transferId, false));
            stop();
            if (recvCb != null) {
                recvCb.onFailed("peercraft.handoff.abort.transfer_failed");
            }
        }
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        sendLoopDone.set(true);
        Thread t = worker;
        if (t != null) {
            if (t != Thread.currentThread()) t.interrupt();
        }
        Thread hash = finalizer;
        if (hash != null && hash != Thread.currentThread()) hash.interrupt();
        FileReassembler r = reassembler;
        if (r != null) {
            synchronized (r) { r.close(); }
        }
    }

    /** Worker-thread cleanup must await all file readers before deleting attempt files. */
    public void stopAndAwait(long timeoutMillis) throws IOException {
        stop();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        for (Thread thread : new Thread[]{worker, finalizer}) {
            if (thread == null || thread == Thread.currentThread()) continue;
            long left = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (left <= 0) throw new IOException("Transfer workers did not stop");
            try { thread.join(left); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            if (thread.isAlive()) throw new IOException("Transfer worker still owns snapshot files");
        }
    }

    /** Host: the successor has every chunk — end the send loop but keep listening for the DONE. */
    private void stopSendLoopOnly() {
        sendLoopDone.set(true);
        // Do not interrupt the worker: it must continue requesting the verified result.
    }

    public void abort(String reasonKey) {
        send(WorldTransferProtocol.encodeAbort(transferId, reasonKey));
        stop();
    }

    private void send(byte[] data) {
        try {
            sender.send(peerIp, peerPort, data);
        } catch (RuntimeException ignored) {
        }
    }
}
