package net.peercraft.rendezvous;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.LongSupplier;
import static net.peercraft.rendezvous.HandoffAuthorityProtocol.*;

/** Durable session-scoped CAS. Never infers ABORT from an absent record. */
final class HandoffRegistry {
    static final int MAX_ACTIVE = 1000, MAX_CACHE = 1000;
    static final long ATTEMPT_LIFETIME = 2L * 60 * 60 * 1000;
    static final long RETENTION = 30L * 24 * 60 * 60 * 1000;
    private final Path root;
    private final LongSupplier clock;
    private final Path liveIndex;
    private boolean directoryUnsynced = true;
    private DirectoryStream<Path> maintenancePaths;
    private Iterator<Path> maintenanceCursor;
    private boolean scanningHistory = true;
    long corruptRecords, lastMaintenanceNanos, lastFlushNanos;
    int lastMaintenanceCount;

    private final Gson gson = new Gson();
    private final java.util.concurrent.ConcurrentMap<UUID, byte[][]> knownKeys = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<UUID> active = new HashSet<>();
    private final Map<String, UUID> activeRooms = new HashMap<>();
    private final Map<UUID, Entry> cache = new LinkedHashMap<>(16, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<UUID, Entry> eldest) {
            if (size() <= MAX_CACHE) return false;
            if (!active.contains(eldest.getKey())) knownKeys.remove(eldest.getKey());
            return true;
        }
    };
    static final class Entry {
        UUID session;
        long offer, epoch, updated, expires;
        int state;
        boolean installed;
        byte[] owner, host, successor, observer, digest;
        String room, sourceRoom, restoredRoom;
    }
    HandoffRegistry(Path root, LongSupplier clock) throws IOException {
        this.root = root; this.clock = clock; Files.createDirectories(root);
        liveIndex = root.resolve("active"); Files.createDirectories(liveIndex);
        Path migrated = liveIndex.resolve("indexed");
        // One-time migration. Subsequent startups read only bounded live-index entries.
        if (!Files.exists(migrated)) {
            try (DirectoryStream<Path> paths = Files.newDirectoryStream(root, "*.json")) {
                for (Path path : paths) {
                    try { Entry e = read(path); if (live(e)) markLive(e.session); }
                    catch (IOException corrupt) { corruptRecords++; }
                }
            }
            Files.write(migrated, new byte[0], StandardOpenOption.CREATE_NEW);
            try (FileChannel f = FileChannel.open(migrated, StandardOpenOption.WRITE)) { f.force(true); }
            forceIndex();
        }
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(liveIndex, "*.live")) {
            for (Path path : paths) {
                try {
                    UUID id = UUID.fromString(path.getFileName().toString().replace(".live", ""));
                    Entry e = read(current(id)); expire(e);
                    if (live(e)) {
                        if (active.size() >= MAX_ACTIVE) throw new IOException("Too many active handoff journals");
                        if (e.sourceRoom == null || activeRooms.containsKey(e.sourceRoom))
                            throw new IOException("Duplicate source room handoff journals");
                        active.add(e.session); activeRooms.put(e.sourceRoom, e.session); remember(e);
                    } else { Files.deleteIfExists(path); forceIndex(); }
                } catch (IOException | IllegalArgumentException corrupt) { corruptRecords++; }
            }
        }
    }
    private static boolean live(Entry e) { return e.state == PENDING || e.state == STAGED; }
    private void forceIndex() throws IOException {
        try (FileChannel dir = FileChannel.open(liveIndex, StandardOpenOption.READ)) { dir.force(true); }
    }
    private void markLive(UUID id) throws IOException {
        Path path = liveIndex.resolve(id + ".live");
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) { file.force(true); }
        forceIndex();
    }
    private void expire(Entry e) throws IOException {
        long expires = e.expires != 0 ? e.expires : e.updated + ATTEMPT_LIFETIME;
        if (live(e) && clock.getAsLong() >= expires) {
            e.state = ABORTED; e.updated = clock.getAsLong(); save(e);
            active.remove(e.session); activeRooms.remove(e.sourceRoom, e.session);
        }
    }
    synchronized Message handle(Message m, boolean sourceOwnsRoom) throws IOException {
        Message reply = new Message(REPLY); reply.requestId = m.requestId;
        reply.sessionId = m.sessionId; reply.offerId = m.offerId;
        if (m.type == CAPABILITIES) { reply.state = SUPPORTED; return reply; }
        Entry e = load(m.sessionId);
        if (e != null) {
            expire(e);
            if (e.state == PENDING || e.state == STAGED) {
                UUID occupant = activeRooms.get(e.sourceRoom);
                if ((!active.contains(e.session) && active.size() >= MAX_ACTIVE)
                        || (occupant != null && !occupant.equals(e.session))) { reply.state = DENIED; return reply; }
                active.add(e.session); activeRooms.put(e.sourceRoom, e.session);
            } else {
                active.remove(e.session); activeRooms.remove(e.sourceRoom, e.session);
            }
        }
        if (m.type == BEGIN) {
            Path previous = history(m.sessionId, m.offerId);
            if (Files.isRegularFile(previous)) {
                Entry historic = read(previous);
                if (!same(historic.host, m.key)) { reply.state = DENIED; return reply; }
                return result(reply, historic, m.key, e);
            }
            if (e != null && e.offer == m.offerId) {
                if (!same(e.host, m.key)) { reply.state = DENIED; return reply; }
                return result(reply, e, m.key);
            }
            if (!sourceOwnsRoom || ZERO.equals(m.sessionId) || m.epoch < 0
                    || allZero(m.key) || allZero(m.successorKey) || allZero(m.observerKey)
                    || same(m.key, m.successorKey) || same(m.key, m.observerKey) || same(m.successorKey, m.observerKey)) {
                reply.state = DENIED; return reply;
            }
            if (e != null && ((e.state != COMMITTED && e.state != ROOM_READY && e.state != ABORTED && e.state != UNKNOWN)
                    || e.epoch != m.epoch || !same(e.owner, m.key))) {
                reply.state = DENIED; return reply;
            }
            if (e == null && m.epoch != 0) { reply.state = UNKNOWN; return reply; }
            if ((active.size() >= MAX_ACTIVE && !active.contains(m.sessionId))
                    || (activeRooms.containsKey(m.room) && !m.sessionId.equals(activeRooms.get(m.room)))) { reply.state = DENIED; return reply; }
            if (e != null) persist(history(e.session, e.offer), e);
            e = new Entry(); e.session = m.sessionId; e.offer = m.offerId; e.epoch = m.epoch;
            e.owner = m.key.clone(); e.host = m.key.clone(); e.successor = m.successorKey.clone();
            e.observer = m.observerKey.clone(); e.state = PENDING; e.room = ""; e.sourceRoom = m.room;
            e.updated = clock.getAsLong(); e.expires = e.updated + ATTEMPT_LIFETIME;
            // Reserve admission before disk I/O. If a rename succeeds but fsync fails,
            // another session cannot occupy the same source room during the retry.
            markLive(e.session); active.add(e.session); activeRooms.put(e.sourceRoom, e.session); save(e);
            return result(reply, e, m.key);
        }
        if (e == null || e.offer != m.offerId) {
            Path old = history(m.sessionId, m.offerId);
            if (Files.isRegularFile(old)) return result(reply, read(old), m.key, e);
            else { reply.state = UNKNOWN; return reply; }
        }
        boolean host = same(e.host, m.key), successor = same(e.successor, m.key);
        if (!host && !successor && !same(e.observer, m.key)) { reply.state = DENIED; return reply; }
        if (m.type == QUIESCE && (!host || e.state != PENDING || m.epoch != e.epoch)) {
            reply.state = DENIED; return reply;
        }
        boolean changed = false;
        if (m.type == VERIFIED && successor && e.state == PENDING && m.epoch == e.epoch && !allZero(m.digest)) {
            e.digest = m.digest.clone(); e.state = STAGED; changed = true;
        } else if (m.type == COMMIT && host && e.state == STAGED && e.epoch == m.epoch && e.epoch < Long.MAX_VALUE
                && same(e.digest, m.digest)) {
            e.epoch++; e.owner = e.successor.clone(); e.state = COMMITTED; changed = true;
        } else if (m.type == ABORT && (host || successor) && m.epoch == e.epoch && (e.state == PENDING || e.state == STAGED)) {
            e.state = ABORTED; changed = true;
        } else if (m.type == INSTALLED && successor && e.state == COMMITTED && e.epoch == m.epoch && !e.installed) {
            e.installed = true; changed = true;
        } else if (m.type == READY && successor && m.epoch == e.epoch && e.state == COMMITTED && e.installed && sourceOwnsRoom) {
            e.room = m.room; e.state = ROOM_READY; changed = true;
        } else if (m.type == RECOVERED && host && e.state == ABORTED && e.epoch == m.epoch && sourceOwnsRoom) {
            if (!m.room.equals(e.restoredRoom)) { e.restoredRoom = m.room; changed = true; }
        } else if (m.type != QUERY && m.type != QUIESCE && m.type != VERIFIED && m.type != COMMIT
                && m.type != ABORT && m.type != READY && m.type != RECOVERED && m.type != INSTALLED) {
            reply.state = DENIED; return reply;
        }
        if (changed) {
            e.updated = clock.getAsLong(); save(e);
            if (e.state != PENDING && e.state != STAGED) {
                active.remove(e.session); activeRooms.remove(e.sourceRoom, e.session);
            }
        }
        return result(reply, e, m.key);
    }
    private Message result(Message reply, Entry e, byte[] key) { return result(reply, e, key, e); }
    private Message result(Message reply, Entry e, byte[] key, Entry current) {
        if (!same(key, e.host) && !same(key, e.successor) && !same(key, e.observer)) { reply.state = DENIED; return reply; }
        reply.state = e.state; reply.epoch = e.epoch; reply.installed = e.installed;
        reply.currentEpoch = current == null ? -1 : current.epoch;
        reply.isCurrentAttempt = current != null && current.offer == e.offer && current.epoch == e.epoch;
        reply.ownsCurrentEpoch = reply.isCurrentAttempt && same(current.owner, key);
        reply.sourceRestored = e.state == ABORTED && e.restoredRoom != null && !e.restoredRoom.isEmpty();
        reply.room = reply.sourceRestored ? e.restoredRoom
                : e.state == PENDING || e.state == STAGED || e.state == ABORTED ? e.sourceRoom : e.room;
        reply.digest = e.digest == null ? new byte[64] : e.digest.clone(); return reply;
    }
    private Entry load(UUID id) throws IOException {
        Entry e = cache.get(id);
        if (e == null && Files.isRegularFile(current(id))) { e = read(current(id)); cache.put(id, e); remember(e); }
        // Return a detached copy: a failed persist must not make an uncommitted state visible.
        return e == null ? null : gson.fromJson(gson.toJson(e), Entry.class);
    }
    boolean recognizes(Message m) {
        byte[][] keys = knownKeys.get(m.sessionId);
        if (keys == null) return false;
        boolean host = same(keys[0], m.key), successor = same(keys[1], m.key), observer = same(keys[2], m.key);
        switch (m.type) {
            case QUERY: return host || successor || observer;
            case BEGIN: case QUIESCE: case COMMIT: case RECOVERED: return host;
            case VERIFIED: case INSTALLED: case READY: return successor;
            case ABORT: return host || successor;
            default: return false;
        }
    }
    private void remember(Entry e) { knownKeys.put(e.session, new byte[][] {e.host.clone(), e.successor.clone(), e.observer.clone()}); }
    private void save(Entry e) throws IOException {
        cache.remove(e.session); persist(current(e.session), e); cache.put(e.session, e); remember(e);
        if (!live(e)) { Files.deleteIfExists(liveIndex.resolve(e.session + ".live")); forceIndex(); }
    }
    private Path current(UUID id) { return root.resolve(id.toString() + ".json"); }
    private Path history(UUID id, long offer) { return root.resolve(id + "-" + Long.toHexString(offer) + ".history"); }
    private Entry read(Path p) throws IOException {
        // A previous atomic move may have succeeded while directory fsync failed.
        // Never expose that record as a durable result until fsync succeeds on retry.
        if (directoryUnsynced) {
            try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) { directory.force(true); }
            directoryUnsynced = false;
        }
        try {
            if (Files.size(p) > 16 * 1024) throw new IOException("Oversized handoff journal");
            Entry e = gson.fromJson(Files.readString(p), Entry.class);
            if (e == null || e.session == null || e.owner == null || e.owner.length != 32
                    || e.epoch < 0 || e.state < UNKNOWN || e.state > ROOM_READY
                    || (e.digest != null && e.digest.length != 64) || e.host == null || e.host.length != 32
                    || e.successor == null || e.successor.length != 32 || e.observer == null || e.observer.length != 32)
                throw new IOException("Invalid handoff journal " + p.getFileName());
            return e;
        } catch (RuntimeException ex) { throw new IOException("Unreadable handoff journal", ex); }
    }
    private void persist(Path p, Entry e) throws IOException {
        long flushStart = System.nanoTime();
        Path tmp = Files.createTempFile(root, ".handoff-", ".tmp");
        try {
            try (FileChannel file = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = java.nio.charset.StandardCharsets.UTF_8.encode(gson.toJson(e));
                while (bytes.hasRemaining()) file.write(bytes); file.force(true);
            }
            directoryUnsynced = true;
            Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) { directory.force(true); }
            directoryUnsynced = false;
        } finally { lastFlushNanos = System.nanoTime() - flushStart; Files.deleteIfExists(tmp); }
    }
    /** One bounded portion on the same writer as transitions; callers schedule frequent portions. */
    synchronized void maintenance() throws IOException {
        long start = System.nanoTime(); lastMaintenanceCount = 0;
        long cutoff = clock.getAsLong() - RETENTION;
        // At most sixteen live records per portion, rotating independently of historical scan.
        List<UUID> liveIds = new ArrayList<>(active);
        liveIds.sort(Comparator.comparing(UUID::toString));
        if (!liveIds.isEmpty()) {
            int index = liveMaintenanceOffset % liveIds.size();
            for (int n = 0; n < Math.min(16, liveIds.size()); n++) {
                try { Entry e = load(liveIds.get((index + n) % liveIds.size())); if (e != null) expire(e); }
                catch (IOException corrupt) { corruptRecords++; }
            }
            liveMaintenanceOffset += 16;
        }
        if (maintenanceCursor == null) {
            maintenancePaths = Files.newDirectoryStream(root, scanningHistory ? "*.history" : "*.json");
            maintenanceCursor = maintenancePaths.iterator();
        }
        try {
            while (lastMaintenanceCount < 64 && System.nanoTime() - start < 25_000_000L && maintenanceCursor.hasNext()) {
                Path path = maintenanceCursor.next(); lastMaintenanceCount++;
                try {
                    Entry e = read(path);
                    if (scanningHistory) { if (e.updated < cutoff) { directoryUnsynced = true; Files.deleteIfExists(path); } }
                    else if (e.updated < cutoff && (e.state == COMMITTED || e.state == ROOM_READY || e.state == ABORTED)) {
                        e.state = UNKNOWN; e.host = e.owner.clone(); e.successor = e.owner.clone();
                        e.observer = new byte[32]; e.digest = null; e.room = ""; e.sourceRoom = "";
                        e.restoredRoom = ""; e.installed = false; save(e);
                    }
                } catch (IOException corrupt) { corruptRecords++; }
            }
            if (!maintenanceCursor.hasNext()) {
                maintenancePaths.close(); maintenancePaths = null; maintenanceCursor = null;
                scanningHistory = !scanningHistory;
                // An empty history pass can safely process a small current pass immediately.
                if (lastMaintenanceCount == 0 && !scanningHistory) maintenance();
            }
        } finally { lastMaintenanceNanos = System.nanoTime() - start; }
    }
    private int liveMaintenanceOffset;
    synchronized String metrics() {
        return "active=" + active.size() + " cache=" + cache.size() + " corrupt=" + corruptRecords
                + " maintenanceNanos=" + lastMaintenanceNanos + " maintenanceRecords=" + lastMaintenanceCount
                + " flushNanos=" + lastFlushNanos;
    }
    private static boolean same(byte[] a, byte[] b) { return a != null && b != null && MessageDigest.isEqual(a, b); }
    private static boolean allZero(byte[] b) { for (byte v : b) if (v != 0) return false; return true; }
}
