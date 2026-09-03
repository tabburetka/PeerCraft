package net.peercraft.network.modsync;

// Java 8 twin of src/main/.../network/modsync/ModSyncCoordinator.java — arrow-switch
// statements lowered to colon switches with break; List.copyOf/List.of -> Collections; every
// other line (threads, ConcurrentHashMap, ByteBuffer, RandomAccessFile, static interface
// methods) already compiles on Java 8. Keep in sync with the original.

import net.peercraft.network.p2p.RawPacketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The mod-sync handshake + peer-to-peer jar transfer state machine for ONE peer, driven by
 * datagrams {@code P2PBridge} demuxes off the shared socket. Runs after the NAT punch
 * succeeds and before any Minecraft bytes flow. Two roles ({@link #joiner} / {@link #host}),
 * chosen at construction.
 *
 * <p>No {@code net.minecraft.*}: file paths, the mod list and the send primitive are all
 * injected. Callbacks fire on this class's own daemon threads.
 */
public final class ModSyncCoordinator implements RawPacketListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    private static final long HELLO_RETRY_MILLIS = 600;
    private static final long HANDSHAKE_TIMEOUT_MILLIS = 8_000;
    private static final long ACK_INTERVAL_MILLIS = 400;
    private static final long TRANSFER_TIMEOUT_MILLIS = 30 * 60_000;
    /** How often to send T_PING so the punched NAT mapping doesn't age out during a quiet stretch. */
    private static final long KEEPALIVE_INTERVAL_MILLIS = 3_000;
    private static final long REQUEST_RETRY_MILLIS = 1_500;
    private static final long REQUEST_TIMEOUT_MILLIS = 90_000;
    /**
     * After this many chunks (first pass or repair), pause ~3 ms so a multi-MB burst can't
     * outrun the joiner's UDP receive buffer (~4 MiB) and get a whole tail dropped every time.
     */
    private static final int CHUNK_BURST = 6;
    /**
     * Optional per-chunk send throttle (ms), read from {@code -Dpeercraft.modSync.sendPacingMillis}
     * (0 = off, clamped to 50). Applied after every chunk in BOTH the first pass and the repair
     * pass — set it on the HOST to roughly its upload rate when big mods keep stalling on a
     * lossy link (a 6000-byte chunk every 8 ms ≈ 750 KiB/s).
     */
    private static final long CHUNK_PACING_MILLIS =
            Math.max(0L, Math.min(50L, Long.getLong("peercraft.modSync.sendPacingMillis", 0L)));
    /** Payload bytes per FILE_CHUNK — well under P2PBridge's 8000-byte per-datagram ceiling once the small header is added. */
    static final int FILE_CHUNK_PAYLOAD = 6000;
    /** A single MANIFEST datagram above this is streamed as a file under {@link ModSyncProtocol#MANIFEST_STREAM_ID} instead. */
    static final int MANIFEST_INLINE_LIMIT = 7000;
    private static final int MAX_GAPS_PER_ACK = 256;
    /** Host serves jars up to this size straight from memory; larger ones spill to a scratch file. */
    private static final long MAX_IN_MEMORY_SERVE_BYTES = 128L * 1024 * 1024;

    public interface Sender {
        void send(byte[] data);
    }

    public interface JoinerHandler {
        /** Host has server-side mods this joiner lacks. {@code missing} is already {@link ModDiff#sanitize sanitized}. */
        void onManifest(List<ModEntry> missing);

        /** Host participates in mod-sync and nothing is missing — proceed to connect. */
        void onNothingMissing();

        /** Host never answered within the window — treat as "host has no mod-sync", proceed to connect. */
        void onHandshakeTimeout();

        void onAbort(String reasonKey);

        void onFileProgress(String modId, long received, long total);

        /** A requested jar arrived and its SHA-512 matched the manifest — {@code verifiedPartFile} is ready to install. */
        void onFileComplete(String modId, Path verifiedPartFile);

        void onFileFailed(String modId, String reasonKey);
    }

    private final Sender sender;
    private final boolean isHost;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Thread keepaliveThread;

    // ---- joiner state ----
    private final ModSyncProtocol.Loader joinerLoader;
    private final List<ModSyncProtocol.ModRef> joinerMods;
    private final Path joinerTmpDir;
    private final long maxModBytes;
    private final JoinerHandler handler;
    private volatile Thread helloThread;
    private final AtomicBoolean manifestSettled = new AtomicBoolean(false);
    private final Map<String, byte[]> expectedSha = new ConcurrentHashMap<String, byte[]>();
    private final Map<String, InboundTransfer> inbound = new ConcurrentHashMap<String, InboundTransfer>();
    private final Map<String, Thread> pendingRequests = new ConcurrentHashMap<String, Thread>();
    private volatile ManifestStreamState manifestStream;

    // ---- host state ----
    private final ModSyncHostProvider provider;
    private final Path hostTmpDir;
    private final AtomicBoolean helloHandled = new AtomicBoolean(false);
    private volatile byte[] cachedHelloResponse;
    private volatile byte[] cachedManifestStreamBody;
    private final Map<String, ModEntry> offeredById = new ConcurrentHashMap<String, ModEntry>();
    private final Map<String, OutboundTransfer> outbound = new ConcurrentHashMap<String, OutboundTransfer>();

    private ModSyncCoordinator(Sender sender, boolean isHost,
                               ModSyncProtocol.Loader joinerLoader, List<ModSyncProtocol.ModRef> joinerMods,
                               Path joinerTmpDir, long maxModBytes, JoinerHandler handler,
                               ModSyncHostProvider provider, Path hostTmpDir) {
        this.sender = sender;
        this.isHost = isHost;
        this.joinerLoader = joinerLoader;
        this.joinerMods = joinerMods;
        this.joinerTmpDir = joinerTmpDir;
        this.maxModBytes = maxModBytes;
        this.handler = handler;
        this.provider = provider;
        this.hostTmpDir = hostTmpDir;
    }

    public static ModSyncCoordinator joiner(Sender sender, ModSyncProtocol.Loader loader,
                                            List<ModSyncProtocol.ModRef> localMods, Path tmpDir,
                                            long maxModBytes, JoinerHandler handler) {
        return new ModSyncCoordinator(sender, false, loader,
                Collections.unmodifiableList(new ArrayList<ModSyncProtocol.ModRef>(localMods)),
                tmpDir, maxModBytes, handler, null, null);
    }

    public static ModSyncCoordinator host(Sender sender, ModSyncHostProvider provider, Path hostTmpDir) {
        return new ModSyncCoordinator(sender, true, null,
                Collections.<ModSyncProtocol.ModRef>emptyList(), null, 0, null, provider, hostTmpDir);
    }

    // ================= joiner API (called by the client layer) =================

    public void startJoiner() {
        startKeepalive();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runHelloLoop();
            }
        }, "PeerCraft-ModSync-Hello");
        t.setDaemon(true);
        this.helloThread = t;
        t.start();
    }

    /** Keeps the punched NAT mapping alive during quiet stretches. Both roles run one. */
    private void startKeepalive() {
        if (keepaliveThread != null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] ping = ModSyncProtocol.encodePing();
                while (!cancelled.get()) {
                    try {
                        sender.send(ping);
                    } catch (RuntimeException ignored) {
                    }
                    try {
                        Thread.sleep(KEEPALIVE_INTERVAL_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "PeerCraft-ModSync-Keepalive");
        t.setDaemon(true);
        this.keepaliveThread = t;
        t.start();
    }

    /**
     * After the player confirms: pull this jar over P2P. Retries the request until the host
     * starts sending (a single lost UDP request datagram must not stall the whole download).
     */
    public void requestFile(final ModEntry entry) {
        if (cancelled.get()) {
            return;
        }
        final String id = entry.id();
        expectedSha.put(id, entry.sha512());
        pendingRequests.computeIfAbsent(id, new java.util.function.Function<String, Thread>() {
            @Override
            public Thread apply(String k) {
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        retryRequest(id);
                    }
                }, "PeerCraft-ModSync-Req-" + safeName(id));
                t.setDaemon(true);
                t.start();
                return t;
            }
        });
    }

    private void retryRequest(String id) {
        byte[] req = ModSyncProtocol.encodeRequestFile(id);
        long deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MILLIS;
        while (!cancelled.get() && System.currentTimeMillis() < deadline) {
            if (inbound.containsKey(id)) {
                pendingRequests.remove(id);
                return; // FILE_BEGIN arrived, transfer is running
            }
            sender.send(req);
            try {
                Thread.sleep(REQUEST_RETRY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pendingRequests.remove(id);
                return;
            }
        }
        pendingRequests.remove(id);
        if (!cancelled.get() && !inbound.containsKey(id)) {
            handler.onFileFailed(id, "peercraft.modsync.fail.transfer");
        }
    }

    public void sendAbort(String reasonKey) {
        try {
            sender.send(ModSyncProtocol.encodeAbort(reasonKey));
        } catch (RuntimeException ignored) {
        }
    }

    // ================= RawPacketListener =================

    @Override
    public void onPacket(byte[] data, int length, InetAddress address, int port) {
        if (cancelled.get()) {
            return;
        }
        int type = ModSyncProtocol.messageType(data, length);
        if (type < 0) {
            return;
        }
        try {
            if (isHost) {
                onHostPacket(type, data, length);
            } else {
                onJoinerPacket(type, data, length);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[ModSync] Ошибка обработки пакета типа {}: {}", type, e.toString());
        }
    }

    /** True once {@link #cancel()} has run — a host-side coordinator in this state can serve nothing and must be replaced for the peer's next attempt. */
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        Thread h = helloThread;
        if (h != null) {
            h.interrupt();
        }
        for (InboundTransfer t : inbound.values()) {
            t.stop();
        }
        inbound.clear();
        for (OutboundTransfer t : outbound.values()) {
            t.stop();
        }
        outbound.clear();
        for (Thread t : pendingRequests.values()) {
            t.interrupt();
        }
        pendingRequests.clear();
        Thread ka = keepaliveThread;
        if (ka != null) {
            ka.interrupt();
        }
        ManifestStreamState ms = manifestStream;
        if (ms != null && ms.reassembler != null) {
            ms.reassembler.close();
        }
    }

    // ================= joiner side =================

    private void runHelloLoop() {
        byte[] hello = ModSyncProtocol.encodeHello(joinerLoader, joinerMods);
        long deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MILLIS;
        while (!cancelled.get() && !manifestSettled.get() && System.currentTimeMillis() < deadline) {
            sender.send(hello);
            try {
                Thread.sleep(HELLO_RETRY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!cancelled.get() && manifestSettled.compareAndSet(false, true)) {
            LOGGER.info("[ModSync] Хост не ответил за {} мс — считаем, что mod-sync у него нет.", HANDSHAKE_TIMEOUT_MILLIS);
            handler.onHandshakeTimeout();
        }
    }

    private void onJoinerPacket(int type, byte[] data, int length) {
        switch (type) {
            case ModSyncProtocol.T_MANIFEST:
                handleManifest(ModSyncProtocol.decodeManifest(data, length), data, length);
                break;
            case ModSyncProtocol.T_MANIFEST_NONE:
                if (manifestSettled.compareAndSet(false, true)) {
                    handler.onNothingMissing();
                }
                break;
            case ModSyncProtocol.T_ABORT: {
                // Ignore a reasonless abort — it's a mangled keepalive, not the host bailing out.
                String reason = ModSyncProtocol.decodeAbort(data, length).reasonKey();
                if (reason == null || reason.isEmpty()) {
                    break;
                }
                manifestSettled.set(true);
                handler.onAbort(reasonKeyOr(reason, "peercraft.modsync.fail.transfer"));
                break;
            }
            case ModSyncProtocol.T_FILE_BEGIN:
                handleFileBegin(ModSyncProtocol.decodeFileBegin(data, length));
                break;
            case ModSyncProtocol.T_FILE_CHUNK:
                handleFileChunk(ModSyncProtocol.decodeFileChunk(data, length));
                break;
            default:
                /* T_HELLO / T_REQUEST_FILE / T_FILE_ACK / T_FILE_DONE are host-directed */
                break;
        }
    }

    private void handleManifest(ModSyncProtocol.Manifest manifest, byte[] raw, int rawLen) {
        if (manifest.protoVersion() != ModSyncProtocol.PROTO_VERSION) {
            LOGGER.warn("[ModSync] Несовместимая версия протокола mod-sync: получено {}, ожидалось {}",
                    manifest.protoVersion(), ModSyncProtocol.PROTO_VERSION);
            if (manifestSettled.compareAndSet(false, true)) {
                handler.onAbort("peercraft.modsync.fail.incompatible");
            }
            return;
        }
        if (manifest.streamed()) {
            // The real list is coming as a file under MANIFEST_STREAM_ID; a FILE_BEGIN for it
            // will follow. Mark that we're waiting so handleFileBegin routes it here.
            if (manifestStream == null) {
                manifestStream = new ManifestStreamState();
            }
            return;
        }
        settleManifest(manifest.mods());
    }

    private void settleManifest(List<ModEntry> mods) {
        if (!manifestSettled.compareAndSet(false, true)) {
            return;
        }
        List<ModEntry> missing = ModDiff.sanitize(mods);
        LOGGER.info("[ModSync] Манифест хоста: {} записей, после фильтра к скачиванию {}.", mods.size(), missing.size());
        if (missing.isEmpty()) {
            handler.onNothingMissing();
        } else {
            StringBuilder sb = new StringBuilder();
            for (ModEntry e : missing) {
                sb.append(e.id()).append('@').append(e.version()).append(' ');
            }
            LOGGER.info("[ModSync] К скачиванию: {}", sb.toString().trim());
            handler.onManifest(missing);
        }
    }

    private void handleFileBegin(final ModSyncProtocol.FileBegin begin) {
        if (begin.modId().equals(ModSyncProtocol.MANIFEST_STREAM_ID)) {
            beginManifestStream(begin);
            return;
        }
        if (!expectedSha.containsKey(begin.modId())) {
            return; // unrequested
        }
        inbound.compute(begin.modId(), new java.util.function.BiFunction<String, InboundTransfer, InboundTransfer>() {
            @Override
            public InboundTransfer apply(String id, InboundTransfer existing) {
                if (existing != null) {
                    return existing; // retransmitted BEGIN
                }
                try {
                    Path part = joinerTmpDir.resolve(safeName(id) + ".jar.part");
                    InboundTransfer t = new InboundTransfer(id, begin, part, maxModBytes);
                    t.start();
                    Thread req = pendingRequests.remove(id);
                    if (req != null) {
                        req.interrupt();
                    }
                    return t;
                } catch (IOException e) {
                    handler.onFileFailed(id, "peercraft.modsync.fail.io");
                    return null;
                }
            }
        });
    }

    private void beginManifestStream(ModSyncProtocol.FileBegin begin) {
        ManifestStreamState ms = manifestStream;
        if (ms == null) {
            ms = new ManifestStreamState();
            manifestStream = ms;
        }
        if (ms.reassembler != null) {
            return;
        }
        try {
            Path part = joinerTmpDir.resolve("manifest.stream.part");
            ms.reassembler = new FileReassembler(part, begin.size(), begin.chunkCount(), begin.chunkSize(), MANIFEST_INLINE_LIMIT * 64L);
            ms.begin = begin;
            ms.startAckLoop();
        } catch (IOException e) {
            if (manifestSettled.compareAndSet(false, true)) {
                handler.onAbort("peercraft.modsync.fail.io");
            }
        }
    }

    private void handleFileChunk(ModSyncProtocol.FileChunk chunk) {
        if (chunk.modId().equals(ModSyncProtocol.MANIFEST_STREAM_ID)) {
            ManifestStreamState ms = manifestStream;
            if (ms != null && ms.reassembler != null) {
                ms.acceptChunk(chunk);
            }
            return;
        }
        InboundTransfer t = inbound.get(chunk.modId());
        if (t != null) {
            t.acceptChunk(chunk);
        }
    }

    // ================= host side =================

    private void onHostPacket(int type, byte[] data, int length) {
        switch (type) {
            case ModSyncProtocol.T_HELLO:
                handleHello(ModSyncProtocol.decodeHello(data, length));
                break;
            case ModSyncProtocol.T_REQUEST_FILE:
                handleRequestFile(ModSyncProtocol.decodeRequestFile(data, length));
                break;
            case ModSyncProtocol.T_FILE_ACK: {
                ModSyncProtocol.FileAck ack = ModSyncProtocol.decodeFileAck(data, length);
                OutboundTransfer t = outbound.get(ack.modId());
                if (t != null) {
                    t.onAck(ack);
                }
                break;
            }
            case ModSyncProtocol.T_FILE_DONE: {
                ModSyncProtocol.FileDone done = ModSyncProtocol.decodeFileDone(data, length);
                OutboundTransfer t = outbound.remove(done.modId());
                if (t != null) {
                    t.stop();
                }
                break;
            }
            case ModSyncProtocol.T_ABORT: {
                // A real abort always carries a reason string. An empty one is almost always a
                // single-bit-mangled keepalive (T_PING 0x0A -> T_ABORT 0x09) off a middlebox —
                // tearing down the whole coordinator over that strands a healthy transfer.
                String reason = ModSyncProtocol.decodeAbort(data, length).reasonKey();
                if (reason != null && !reason.isEmpty()) {
                    cancel();
                }
                break;
            }
            default:
                /* joiner-directed */
                break;
        }
    }

    private void handleHello(ModSyncProtocol.Hello hello) {
        startKeepalive();
        if (!helloHandled.compareAndSet(false, true)) {
            byte[] cached = cachedHelloResponse;
            if (cached != null) {
                sender.send(cached);
            }
            byte[] streamBody = cachedManifestStreamBody;
            if (streamBody != null) {
                streamManifestBody(streamBody);
            }
            return;
        }

        ModSyncProtocol.Loader hostLoader = provider.loader();
        LOGGER.info("[ModSync] HELLO от заходящего: загрузчик={}, модов в списке={} (хост-загрузчик={})",
                hello.loader(), hello.mods().size(), hostLoader);

        Map<String, String> joinerVersions = new java.util.HashMap<String, String>();
        for (ModSyncProtocol.ModRef m : hello.mods()) {
            joinerVersions.put(m.id(), m.version());
        }

        List<ModEntry> hostMods;
        try {
            hostMods = provider.hostMods();
        } catch (RuntimeException e) {
            LOGGER.warn("[ModSync] Не удалось собрать список модов хоста: {}", e.toString());
            cachedHelloResponse = ModSyncProtocol.encodeManifestNone();
            sender.send(cachedHelloResponse);
            return;
        }

        List<ModEntry> missing = ModDiff.missing(joinerVersions, hostMods);
        for (ModEntry e : missing) {
            offeredById.put(e.id(), e);
        }

        if (missing.isEmpty()) {
            // Nothing to sync — the loader doesn't matter, let the join proceed normally.
            cachedHelloResponse = ModSyncProtocol.encodeManifestNone();
            sender.send(cachedHelloResponse);
            return;
        }

        // There ARE mods to send, but a jar built for one loader is useless on the other —
        // only now (not on every HELLO) is a loader mismatch actually a problem.
        boolean loadersDiffer = hostLoader != ModSyncProtocol.Loader.UNKNOWN
                && hello.loader() != ModSyncProtocol.Loader.UNKNOWN
                && hostLoader != hello.loader();
        if (loadersDiffer) {
            LOGGER.warn("[ModSync] Загрузчики различаются (хост {} / заходящий {}), а не хватает {} модов — отдать нельзя.",
                    hostLoader, hello.loader(), missing.size());
            cachedHelloResponse = ModSyncProtocol.encodeAbort("peercraft.modsync.fail.loader_mismatch");
            sender.send(cachedHelloResponse);
            return;
        }

        byte[] inline = ModSyncProtocol.encodeManifest(0, missing);
        if (inline.length <= MANIFEST_INLINE_LIMIT) {
            cachedHelloResponse = inline;
            sender.send(inline);
            return;
        }
        // Too big for one datagram — announce STREAMED, then serve the manifest bytes as a file.
        cachedHelloResponse = ModSyncProtocol.encodeManifest(ModSyncProtocol.MANIFEST_FLAG_STREAMED,
                Collections.<ModEntry>emptyList());
        cachedManifestStreamBody = inline;
        sender.send(cachedHelloResponse);
        streamManifestBody(inline);
    }

    private void streamManifestBody(final byte[] body) {
        outbound.computeIfAbsent(ModSyncProtocol.MANIFEST_STREAM_ID, new java.util.function.Function<String, OutboundTransfer>() {
            @Override
            public OutboundTransfer apply(String id) {
                OutboundTransfer t = new OutboundTransfer(id, null, body, body.length, sha512(body));
                t.start();
                return t;
            }
        });
    }

    private void handleRequestFile(String modId) {
        final ModEntry entry = offeredById.get(modId);
        if (entry == null) {
            sender.send(ModSyncProtocol.encodeAbort("peercraft.modsync.fail.transfer"));
            return;
        }
        outbound.computeIfAbsent(modId, new java.util.function.Function<String, OutboundTransfer>() {
            @Override
            public OutboundTransfer apply(String id) {
                try {
                    OutboundTransfer t;
                    if (entry.sizeBytes() > 0 && entry.sizeBytes() <= MAX_IN_MEMORY_SERVE_BYTES) {
                        // Serve straight from RAM — no scratch file for a mods/-folder watcher
                        // (ModrinthApp etc.) to delete mid-transfer, and one fewer failure point.
                        byte[] jarBytes;
                        try (InputStream in = provider.openJar(id)) {
                            jarBytes = readAll(in);
                        }
                        t = new OutboundTransfer(id, null, jarBytes, jarBytes.length, entry.sha512());
                    } else {
                        Path serving = hostTmpDir.resolve(safeName(id) + ".serving.jar");
                        Files.createDirectories(hostTmpDir);
                        try (InputStream in = provider.openJar(id)) {
                            Files.copy(in, serving, StandardCopyOption.REPLACE_EXISTING);
                        }
                        t = new OutboundTransfer(id, serving, null, Files.size(serving), entry.sha512());
                    }
                    t.start();
                    return t;
                } catch (IOException e) {
                    LOGGER.warn("[ModSync] Не удалось начать отдачу {}: {}", id, e.toString());
                    sender.send(ModSyncProtocol.encodeAbort("peercraft.modsync.fail.io"));
                    return null;
                }
            }
        });
    }

    // ================= inbound (joiner) transfer =================

    private final class InboundTransfer {
        final String modId;
        final FileReassembler reassembler;
        final ModSyncProtocol.FileBegin begin;
        final AtomicBoolean stopped = new AtomicBoolean(false);
        volatile Thread ackThread;

        InboundTransfer(String modId, ModSyncProtocol.FileBegin begin, Path part, long maxBytes) throws IOException {
            this.modId = modId;
            this.begin = begin;
            this.reassembler = new FileReassembler(part, begin.size(), begin.chunkCount(), begin.chunkSize(), maxBytes);
        }

        void start() {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    runAckLoop();
                }
            }, "PeerCraft-ModSync-Ack-" + safeName(modId));
            t.setDaemon(true);
            this.ackThread = t;
            t.start();
        }

        void runAckLoop() {
            long deadline = System.currentTimeMillis() + TRANSFER_TIMEOUT_MILLIS;
            while (!stopped.get() && !cancelled.get() && System.currentTimeMillis() < deadline) {
                sendAck();
                if (reassembler.isComplete()) {
                    break;
                }
                try {
                    Thread.sleep(ACK_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!stopped.get() && !cancelled.get() && !reassembler.isComplete()) {
                finishFailed("peercraft.modsync.fail.transfer");
            }
        }

        void sendAck() {
            int[] gaps = reassembler.missingIndices(MAX_GAPS_PER_ACK);
            sender.send(ModSyncProtocol.encodeFileAck(modId, reassembler.nextContiguous(), gaps));
        }

        void acceptChunk(ModSyncProtocol.FileChunk chunk) {
            if (stopped.get()) {
                return;
            }
            try {
                reassembler.accept(chunk.index(), chunk.data());
            } catch (IOException e) {
                finishFailed("peercraft.modsync.fail.transfer");
                return;
            }
            handler.onFileProgress(modId, reassembler.bytesWritten(), begin.size());
            if (reassembler.isComplete()) {
                finishComplete();
            }
        }

        void finishComplete() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            sender.send(ModSyncProtocol.encodeFileAck(modId, begin.chunkCount(), new int[0]));
            try {
                byte[] actual = reassembler.finishAndHash();
                reassembler.close();
                byte[] want = expectedSha.get(modId);
                if (want == null || !MessageDigest.isEqual(actual, want)) {
                    sender.send(ModSyncProtocol.encodeFileDone(modId, false));
                    handler.onFileFailed(modId, "peercraft.modsync.fail.hash_mismatch");
                    deleteQuietly(reassembler.partFile());
                    return;
                }
                sendDoneRepeatedly();
                handler.onFileComplete(modId, reassembler.partFile());
            } catch (IOException e) {
                sender.send(ModSyncProtocol.encodeFileDone(modId, false));
                handler.onFileFailed(modId, "peercraft.modsync.fail.io");
            } finally {
                inbound.remove(modId);
            }
        }

        /**
         * The joiner's single "done, verified" signal is easy to lose on a flaky link, and a
         * host that never hears it keeps its repair loop resending until the 30-min transfer
         * timeout. Re-send T_FILE_DONE a handful of times off a short-lived daemon; the host's
         * handler is idempotent.
         */
        void sendDoneRepeatedly() {
            final byte[] done = ModSyncProtocol.encodeFileDone(modId, true);
            sender.send(done);
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 5; i++) {
                        try {
                            Thread.sleep(250L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        try {
                            sender.send(done);
                        } catch (RuntimeException ignored) {
                            return;
                        }
                    }
                }
            }, "PeerCraft-ModSync-Done-" + safeName(modId));
            t.setDaemon(true);
            t.start();
        }

        void finishFailed(String key) {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            reassembler.close();
            deleteQuietly(reassembler.partFile());
            inbound.remove(modId);
            handler.onFileFailed(modId, key);
        }

        void stop() {
            stopped.set(true);
            Thread t = ackThread;
            if (t != null) {
                t.interrupt();
            }
            reassembler.close();
        }
    }

    // ================= manifest-stream (joiner) =================

    private final class ManifestStreamState {
        volatile FileReassembler reassembler;
        volatile ModSyncProtocol.FileBegin begin;
        volatile Thread ackThread;
        final AtomicBoolean stopped = new AtomicBoolean(false);

        void startAckLoop() {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    ManifestStreamState.this.run();
                }
            }, "PeerCraft-ModSync-ManifestAck");
            t.setDaemon(true);
            this.ackThread = t;
            t.start();
        }

        void run() {
            long deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MILLIS * 4;
            while (!stopped.get() && !cancelled.get() && System.currentTimeMillis() < deadline) {
                FileReassembler r = reassembler;
                if (r == null) {
                    break;
                }
                sender.send(ModSyncProtocol.encodeFileAck(ModSyncProtocol.MANIFEST_STREAM_ID, r.nextContiguous(), r.missingIndices(MAX_GAPS_PER_ACK)));
                if (r.isComplete()) {
                    break;
                }
                try {
                    Thread.sleep(ACK_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!stopped.get() && !cancelled.get() && reassembler != null && !reassembler.isComplete()
                    && manifestSettled.compareAndSet(false, true)) {
                handler.onAbort("peercraft.modsync.fail.transfer");
            }
        }

        void acceptChunk(ModSyncProtocol.FileChunk chunk) {
            FileReassembler r = reassembler;
            if (r == null || stopped.get()) {
                return;
            }
            try {
                r.accept(chunk.index(), chunk.data());
            } catch (IOException e) {
                if (manifestSettled.compareAndSet(false, true)) {
                    handler.onAbort("peercraft.modsync.fail.transfer");
                }
                return;
            }
            if (r.isComplete() && stopped.compareAndSet(false, true)) {
                sender.send(ModSyncProtocol.encodeFileAck(ModSyncProtocol.MANIFEST_STREAM_ID, begin.chunkCount(), new int[0]));
                sender.send(ModSyncProtocol.encodeFileDone(ModSyncProtocol.MANIFEST_STREAM_ID, true));
                try {
                    byte[] body = Files.readAllBytes(r.partFile());
                    r.close();
                    deleteQuietly(r.partFile());
                    ModSyncProtocol.Manifest m = ModSyncProtocol.decodeManifest(body, body.length);
                    settleManifest(m.mods());
                } catch (IOException e) {
                    if (manifestSettled.compareAndSet(false, true)) {
                        handler.onAbort("peercraft.modsync.fail.io");
                    }
                }
            }
        }
    }

    // ================= outbound (host) transfer =================

    private final class OutboundTransfer {
        final String modId;
        final Path servingFile;   // null when serving from bytes
        final byte[] servingBytes; // null when serving from a file
        final long size;
        final int chunkCount;
        final byte[] sha512;
        final AtomicBoolean stopped = new AtomicBoolean(false);
        volatile Thread sendThread;
        volatile int highestAcked = -1;
        /** Chunk indices the joiner last reported missing. Set (cheaply) by {@link #onAck}, drained by the repair loop on this transfer's own thread. */
        volatile int[] outstandingGaps = new int[0];

        OutboundTransfer(String modId, Path servingFile, byte[] servingBytes, long size, byte[] sha512) {
            this.modId = modId;
            this.servingFile = servingFile;
            this.servingBytes = servingBytes;
            this.size = size;
            this.sha512 = sha512;
            this.chunkCount = (int) ((size + FILE_CHUNK_PAYLOAD - 1) / FILE_CHUNK_PAYLOAD);
        }

        void start() {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    OutboundTransfer.this.run();
                }
            }, "PeerCraft-ModSync-Send-" + safeName(modId));
            t.setDaemon(true);
            this.sendThread = t;
            t.start();
        }

        void run() {
            sender.send(ModSyncProtocol.encodeFileBegin(modId, size, chunkCount, sha512, FILE_CHUNK_PAYLOAD));
            long deadline = System.currentTimeMillis() + TRANSFER_TIMEOUT_MILLIS;
            try (RandomSource src = openSource()) {
                // First pass: send every chunk once, pacing lightly so the relay isn't buried.
                for (int i = 0; i < chunkCount && !stopped.get() && !cancelled.get(); i++) {
                    sendChunk(src, i);
                    pace(i);
                }
                // Repair pass: on THIS thread (not the packet-demux thread) keep resending the
                // chunks the joiner still reports missing, paced the same way. onAck only records
                // the gap list; all disk reads and sends happen here.
                while (!stopped.get() && !cancelled.get() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(ACK_INTERVAL_MILLIS);
                    int hi = this.highestAcked;
                    int[] gaps = this.outstandingGaps;
                    // Only finish on an unambiguous "joiner has everything" — a high highestAcked
                    // AND no outstanding holes. (onAck already drops impossible ACK values, but
                    // requiring both here means one stray ACK can never end a live transfer.)
                    if (hi >= chunkCount && gaps.length == 0) {
                        break;
                    }
                    if (gaps.length == 0) {
                        // Not done, yet no hole named: every ACK since the first pass was lost,
                        // or the joiner is missing a tail past its selective-ACK window. Re-announce
                        // and resend from the last acked index forward instead of only nudging.
                        sender.send(ModSyncProtocol.encodeFileBegin(modId, size, chunkCount, sha512, FILE_CHUNK_PAYLOAD));
                        int from = Math.max(0, hi);
                        for (int i = from; i < chunkCount && !stopped.get() && !cancelled.get(); i++) {
                            sendChunk(src, i);
                            pace(i - from);
                        }
                        continue;
                    }
                    for (int g = 0; g < gaps.length && !stopped.get() && !cancelled.get(); g++) {
                        int gap = gaps[g];
                        if (gap >= 0 && gap < chunkCount) {
                            sendChunk(src, gap);
                            pace(g);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                LOGGER.warn("[ModSync] Ошибка отдачи {}: {}", modId, e.toString());
                sender.send(ModSyncProtocol.encodeAbort("peercraft.modsync.fail.io"));
            } finally {
                cleanup();
            }
        }

        // Runs on the packet-demux thread — must stay cheap: no disk IO, no sends. Just record
        // what the joiner needs; the repair loop in run() does the resending, paced.
        void onAck(ModSyncProtocol.FileAck ack) {
            int next = ack.nextContiguous();
            // nextContiguous can never legitimately exceed chunkCount (nor be negative). An
            // out-of-range value is a mangled/truncated ACK datagram — ignore it. Acting on
            // one used to latch highestAcked past chunkCount, which broke the repair loop for
            // good and left the joiner stalled at ~100%.
            if (next < 0 || next > chunkCount) {
                return;
            }
            this.highestAcked = Math.max(this.highestAcked, next);
            int[] gaps = ack.gapIndices();
            this.outstandingGaps = gaps != null ? gaps : new int[0];
            // Fast-path stop only on a clean "have everything" ACK; T_FILE_DONE stays the
            // authoritative completion signal (see onHostPacket).
            if (next == chunkCount && this.outstandingGaps.length == 0) {
                stop();
            }
        }

        void pace(int step) throws InterruptedException {
            if (CHUNK_PACING_MILLIS > 0) {
                Thread.sleep(CHUNK_PACING_MILLIS);
            } else if (step % CHUNK_BURST == CHUNK_BURST - 1) {
                Thread.sleep(3);
            }
        }

        void sendChunk(RandomSource src, int index) throws IOException {
            long offset = (long) index * FILE_CHUNK_PAYLOAD;
            int len = (int) Math.min(FILE_CHUNK_PAYLOAD, size - offset);
            if (len <= 0) {
                return;
            }
            byte[] buf = new byte[len];
            src.readFully(offset, buf);
            sender.send(ModSyncProtocol.encodeFileChunk(modId, index, buf, 0, len));
        }

        RandomSource openSource() throws IOException {
            return servingBytes != null ? RandomSource.ofBytes(servingBytes) : RandomSource.ofFile(servingFile);
        }

        void stop() {
            stopped.set(true);
            Thread t = sendThread;
            if (t != null) {
                t.interrupt();
            }
        }

        void cleanup() {
            outbound.remove(modId, this);
            if (servingFile != null) {
                deleteQuietly(servingFile);
            }
        }
    }

    // ================= small helpers =================

    private interface RandomSource extends AutoCloseable {
        void readFully(long offset, byte[] dst) throws IOException;

        @Override
        void close();

        static RandomSource ofBytes(final byte[] data) {
            return new RandomSource() {
                @Override
                public void readFully(long offset, byte[] dst) {
                    System.arraycopy(data, (int) offset, dst, 0, dst.length);
                }

                @Override
                public void close() {
                }
            };
        }

        static RandomSource ofFile(Path file) throws IOException {
            final java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r");
            return new RandomSource() {
                @Override
                public void readFully(long offset, byte[] dst) throws IOException {
                    raf.seek(offset);
                    raf.readFully(dst);
                }

                @Override
                public void close() {
                    try {
                        raf.close();
                    } catch (IOException ignored) {
                    }
                }
            };
        }
    }

    private static String reasonKeyOr(String key, String fallback) {
        return (key == null || key.trim().isEmpty()) ? fallback : key;
    }

    /** Filesystem-safe stem for a mod id — the joiner and this coordinator must agree on the {@code .jar.part} name. */
    public static String safeName(String id) {
        String s = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return s.isEmpty() ? "mod" : s;
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    static byte[] sha512(byte[] data) {
        MessageDigest md = FileReassembler.sha512();
        md.update(data);
        return md.digest();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
