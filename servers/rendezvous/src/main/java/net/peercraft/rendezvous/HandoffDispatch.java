package net.peercraft.rendezvous;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;

/** Bounded ingress and a single fair durable writer. Unverified traffic cannot consume trusted slots. */
final class HandoffDispatch {
    private static final int MAX_SESSIONS = 1000, MAX_TOTAL = 1000, MAX_PER_SESSION = 16, MAX_UNVERIFIED = 64;
    private final LinkedHashMap<UUID, ArrayDeque<Task>> sessions = new LinkedHashMap<>();
    private final ArrayDeque<Task> unverified = new ArrayDeque<>();
    private final LinkedHashMap<String, Bucket> buckets = new LinkedHashMap<>(16, .75f, true);
    private final LongSupplier clock;
    private int queued;
    private Runnable maintenance;
    private long oldestWaitMillis;
    private static final class Task {
        final Runnable run; final long created;
        final String duplicate; final boolean control;
        Task(Runnable run, long created, String duplicate, boolean control) {
            this.run = run; this.created = created; this.duplicate = duplicate; this.control = control;
        }
    }
    private static final class Bucket { double tokens; long time; Bucket(long time, int capacity) { this.time = time; tokens = capacity; } }
    HandoffDispatch(LongSupplier clock) { this(clock, true); }
    HandoffDispatch(LongSupplier clock, boolean startWorker) {
        this.clock = clock;
        if (startWorker) { Thread t = new Thread(this::work, "handoff-journal"); t.setDaemon(true); t.start(); }
    }
    synchronized boolean ingress(InetAddress ip, boolean trusted, UUID session, byte[] key, int type) {
        // Token buckets are constant-size per key, with a bounded LRU.
        if (!allow("ip:" + ip.getHostAddress(), 500, 100)) return false;
        if (!trusted) return allow("unverified:" + ip.getHostAddress(), 40, 2);
        String role = session + ":" + java.util.HexFormat.of().formatHex(key);
        boolean control = type != HandoffAuthorityProtocol.QUERY;
        return allow(role + (control ? ":control" : ":query"), control ? 60 : 120, control ? 5 : 20);
    }
    private boolean allow(String id, int capacity, double perSecond) {
        long now = clock.getAsLong(); Bucket b = buckets.get(id);
        if (b == null) {
            if (buckets.size() >= 4096) buckets.remove(buckets.keySet().iterator().next());
            b = new Bucket(now, capacity); buckets.put(id, b);
        }
        b.tokens = Math.min(capacity, b.tokens + Math.max(0, now - b.time) * perSecond / 1000); b.time = now;
        if (b.tokens < 1) return false; b.tokens--; return true;
    }
    synchronized void execute(HandoffAuthorityProtocol.Message m, boolean trusted, Runnable run) {
        String duplicate = m.requestId + ":" + m.offerId + ":" + m.type + ":" + java.util.HexFormat.of().formatHex(m.key);
        Task task = new Task(run, clock.getAsLong(), duplicate, m.type != HandoffAuthorityProtocol.QUERY);
        if (!trusted) {
            for (Task old : unverified) if (old.duplicate.equals(duplicate)) return;
            if (unverified.size() >= MAX_UNVERIFIED) throw new RejectedExecutionException();
            unverified.add(task);
        } else {
            ArrayDeque<Task> queue = sessions.get(m.sessionId);
            if (queue == null) {
                if (sessions.size() >= MAX_SESSIONS || queued >= MAX_TOTAL) throw new RejectedExecutionException();
                queue = new ArrayDeque<>(); sessions.put(m.sessionId, queue);
            }
            for (Task old : queue) if (old.duplicate.equals(duplicate)) return;
            // Preserve room for state transitions when query traffic is saturated.
            int cap = task.control ? MAX_PER_SESSION : MAX_PER_SESSION - 4;
            if (queue.size() >= cap || queued >= MAX_TOTAL) throw new RejectedExecutionException();
            if (task.control) queue.addFirst(task); else queue.addLast(task);
            queued++;
        }
        notifyAll();
    }
    synchronized void execute(Runnable upkeep) { maintenance = upkeep; notifyAll(); }
    private int turns;
    synchronized Runnable next() {
        Task task = null;
        // Upkeep and unknown requests get bounded turns even under sustained trusted traffic.
        if (++turns % 16 == 0 && maintenance != null) { Runnable r = maintenance; maintenance = null; return r; }
        if (turns % 8 == 0 && !unverified.isEmpty()) task = unverified.removeFirst();
        else if (!sessions.isEmpty()) {
            UUID id = sessions.keySet().iterator().next(); ArrayDeque<Task> q = sessions.remove(id);
            task = q.removeFirst(); queued--; if (!q.isEmpty()) sessions.put(id, q);
        } else if (!unverified.isEmpty()) task = unverified.removeFirst();
        else if (maintenance != null) { Runnable r = maintenance; maintenance = null; return r; }
        if (task == null) return null;
        oldestWaitMillis = Math.max(0, clock.getAsLong() - task.created); return task.run;
    }
    private void work() {
        while (true) {
            Runnable run;
            synchronized (this) {
                while ((run = next()) == null) {
                    try { wait(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            }
            try { run.run(); } catch (RuntimeException failure) { System.err.println("Handoff worker task failed: " + failure.getClass().getSimpleName()); }
        }
    }
    synchronized String metrics() { return "queue=" + queued + " unverified=" + unverified.size() + " waitMillis=" + oldestWaitMillis + " buckets=" + buckets.size(); }
}
