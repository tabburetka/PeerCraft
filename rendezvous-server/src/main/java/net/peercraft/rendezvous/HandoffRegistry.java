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
    static final long RETENTION = 30L * 24 * 60 * 60 * 1000;
    private final Path root;
    private final LongSupplier clock;
    private final Gson gson = new Gson();
    private final Set<UUID> active = new HashSet<>();
    private final Map<String, UUID> activeRooms = new HashMap<>();
    private final Map<UUID, Entry> cache = new LinkedHashMap<>(16, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<UUID, Entry> eldest) { return size() > MAX_CACHE; }
    };
    static final class Entry {
        UUID session;
        long offer, epoch, updated;
        int state;
        boolean installed;
        byte[] owner, host, successor, observer, digest;
        String room, sourceRoom, restoredRoom;
    }
    HandoffRegistry(Path root, LongSupplier clock) throws IOException {
        this.root = root; this.clock = clock; Files.createDirectories(root);
        // Only live records affect admission; historical records are loaded on demand.
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(root, "*.json")) {
            for (Path p : paths) {
                Entry e = read(p);
                if (e.state == PENDING || e.state == STAGED) {
                    if (active.size() >= MAX_ACTIVE) throw new IOException("Too many active handoff journals");
                    active.add(e.session);
                    if (e.sourceRoom == null || activeRooms.put(e.sourceRoom, e.session) != null)
                        throw new IOException("Duplicate source room handoff journals");
                }
            }
        }
    }
    synchronized Message handle(Message m, boolean sourceOwnsRoom) throws IOException {
        Message reply = new Message(REPLY); reply.requestId = m.requestId;
        reply.sessionId = m.sessionId; reply.offerId = m.offerId;
        if (m.type == CAPABILITIES) { reply.state = SUPPORTED; return reply; }
        Entry e = load(m.sessionId);
        if (e != null) {
            if (e.state == PENDING || e.state == STAGED) {
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
            e.updated = clock.getAsLong();
            // Reserve admission before disk I/O. If a rename succeeds but fsync fails,
            // another session cannot occupy the same source room during the retry.
            active.add(e.session); activeRooms.put(e.sourceRoom, e.session); save(e);
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
        reply.ownsCurrentEpoch = current != null && current.offer == e.offer
                && current.epoch == e.epoch && same(current.owner, key);
        reply.sourceRestored = e.state == ABORTED && e.restoredRoom != null && !e.restoredRoom.isEmpty();
        reply.room = reply.sourceRestored ? e.restoredRoom
                : e.state == PENDING || e.state == STAGED || e.state == ABORTED ? e.sourceRoom : e.room;
        reply.digest = e.digest == null ? new byte[64] : e.digest.clone(); return reply;
    }
    private Entry load(UUID id) throws IOException {
        Entry e = cache.get(id);
        if (e == null && Files.isRegularFile(current(id))) { e = read(current(id)); cache.put(id, e); }
        // Return a detached copy: a failed persist must not make an uncommitted state visible.
        return e == null ? null : gson.fromJson(gson.toJson(e), Entry.class);
    }
    private void save(Entry e) throws IOException { cache.remove(e.session); persist(current(e.session), e); cache.put(e.session, e); }
    private Path current(UUID id) { return root.resolve(id.toString() + ".json"); }
    private Path history(UUID id, long offer) { return root.resolve(id + "-" + Long.toHexString(offer) + ".history"); }
    private Entry read(Path p) throws IOException {
        // A previous atomic move may have succeeded while directory fsync failed.
        // Never expose that record as a durable result until fsync succeeds on retry.
        try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) { directory.force(true); }
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
        Path tmp = Files.createTempFile(root, ".handoff-", ".tmp");
        try {
            try (FileChannel file = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = java.nio.charset.StandardCharsets.UTF_8.encode(gson.toJson(e));
                while (bytes.hasRemaining()) file.write(bytes); file.force(true);
            }
            Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) { directory.force(true); }
        } finally { Files.deleteIfExists(tmp); }
    }
    synchronized void maintenance() throws IOException {
        long cutoff = clock.getAsLong() - RETENTION;
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(root, "*.history")) {
            for (Path p : paths) if (read(p).updated < cutoff) Files.deleteIfExists(p);
        }
        // Keep only the epoch and owner needed by a future session continuation.
        // Expired attempts themselves become UNKNOWN, never implicit ABORT or COMMIT.
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(root, "*.json")) {
            for (Path p : paths) {
                Entry e = read(p);
                if (e.updated < cutoff && (e.state == COMMITTED || e.state == ROOM_READY || e.state == ABORTED)) {
                    e.state = UNKNOWN; e.host = e.owner.clone();
                    e.successor = e.owner.clone(); e.observer = new byte[32]; e.digest = null;
                    e.room = ""; e.sourceRoom = ""; e.restoredRoom = ""; e.installed = false; save(e);
                }
            }
        }
    }
    private static boolean same(byte[] a, byte[] b) { return a != null && b != null && MessageDigest.isEqual(a, b); }
    private static boolean allZero(byte[] b) { for (byte v : b) if (v != 0) return false; return true; }
}
