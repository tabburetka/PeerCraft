package net.peercraft.network.telemetry;

import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import net.peercraft.config.PeerCraftConfig;

/** Opt-in diagnostics. Bounded RAM queue, no disk spool, identity or shutdown wait. Java 8. */
public final class Telemetry {
    private static final Object LOCK = new Object();
    private static final ArrayDeque<Pending> QUEUE = new ArrayDeque<Pending>();
    private static volatile boolean enabled;
    private static volatile long generation;
    private static volatile DatagramSocket socket;
    private static volatile String mod = "unknown", mc = "unknown", loader = "unknown",
                                   loaderVersion = "unknown";
    private static boolean workerStarted, launchReported;
    private static final long TTL = TimeUnit.MINUTES.toNanos(5);
    private static LongSupplier clock = System::nanoTime;
    private Telemetry() {}
    public static void initialize(String modVersion, String mcVersion, String loaderName, String loaderVer) {
        mod = TelemetryProtocol.version(modVersion);
        mc = TelemetryProtocol.version(mcVersion);
        loader = Arrays.asList("fabric", "neoforge", "forge").contains(loaderName) ? loaderName : "unknown";
        loaderVersion = TelemetryProtocol.version(loaderVer);
    }
    public static void setEnabled(boolean value) {
        synchronized (LOCK) {
            if (enabled == value)
                return;
            generation++;
            enabled = value;
            QUEUE.clear();
            if (socket != null)
                socket.close();
            if (value && !workerStarted) {
                workerStarted = true;
                Thread t = new Thread(Telemetry::work, "PeerCraft-Diagnostics");
                t.setDaemon(true);
                t.start();
            }
            if (value && !launchReported) {
                launchReported = true;
                enqueue(event(UUID.randomUUID(), "client_started", "none", "none", "none", "none", 0, 0, 0),
                    generation);
            }
            LOCK.notifyAll();
        }
    }
    public static boolean isEnabled() {
        return enabled;
    }
    public static Operation beginJoin(String entry) {
        return new Operation(entry);
    }
    public static final class Operation {
        private final UUID id = UUID.randomUUID();
        private final long ownerGeneration = generation, start = clock.getAsLong();
        private final boolean permitted = enabled;
        private final String entry;
        private String stage = "rendezvous", disconnect = "unknown";
        private long stageStart = start, joinedAt;
        private boolean terminal, joined, ended, transport;
        private Operation(String entry) {
            this.entry = Arrays.asList("code", "friend", "public", "lan").contains(entry) ? entry : "none";
            emit("join_started", "none", "none", 0);
        }
        public synchronized void stage(String value) {
            String next = TelemetryProtocol.stage(value);
            if (next == null || next.equals(stage) || terminal)
                return;
            emit("stage_finished", "success", "none", elapsed(stageStart));
            stage = next;
            stageStart = clock.getAsLong();
        }
        public synchronized void transportReady() {
            if (transport || terminal)
                return;
            transport = true;
            emit("transport_ready", "success", "none", elapsed(start));
        }
        public synchronized void finish(String outcome, String reason) {
            if (terminal)
                return;
            // Only worldJoined(), called after vanilla installs player AND world, may report success.
            String result = TelemetryProtocol.outcome(outcome);
            if ("success".equals(result))
                return;
            terminal = true;
            emit("join_result", result, reason, elapsed(start));
        }
        public synchronized void worldJoined() {
            if (terminal || joined)
                return;
            joined = true;
            terminal = true;
            joinedAt = clock.getAsLong();
            emit("stage_finished", "success", "none", elapsed(stageStart));
            stage = "playing";
            emit("world_joined", "success", "none", elapsed(start));
            emit("join_result", "success", "none", elapsed(start));
        }
        public synchronized void markTransportFailure(String reason) {
            disconnect = "transport_failure";
        }
        public synchronized void sessionEnded(String reason) {
            if (ended || !joined)
                return;
            ended = true;
            if ("transport_failure".equals(reason))
                disconnect = "transport_failure";
            emit("session_summary", "transport_failure".equals(disconnect) ? "failure" : "unknown",
                disconnect, elapsed(joinedAt));
        }
        private void emit(String event, String outcome, String reason, long duration) {
            if (permitted)
                enqueue(Telemetry.event(id, event, entry, stage, outcome, TelemetryProtocol.reason(reason),
                            duration, 0, 0),
                    ownerGeneration);
        }
    }
    public static void modSync(
        Operation owner, String outcome, String reason, long duration, long bytes, int count) {
        if (owner == null || !owner.permitted)
            return;
        enqueue(event(owner.id, "modsync_result", owner.entry, "modsync", TelemetryProtocol.outcome(outcome),
                    TelemetryProtocol.reason(reason), duration, bytes, count),
            owner.ownerGeneration);
    }
    private static long elapsed(long start) {
        return Math.max(0,
            Math.min(
                TelemetryProtocol.MAX_DURATION, TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - start)));
    }
    private static TelemetryProtocol.Event event(UUID operation, String event, String entry, String stage,
        String outcome, String reason, long duration, long bytes, int count) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        os = os.contains("mac") || os.contains("darwin") ? "macos"
            : os.contains("win")                         ? "windows"
            : os.contains("linux")                       ? "linux"
                                                         : "other";
        int java = 8;
        try {
            String v = System.getProperty("java.specification.version", "8");
            java = Integer.parseInt(v.startsWith("1.") ? v.substring(2) : v);
        } catch (RuntimeException ignored) {
        }
        return new TelemetryProtocol.Event(UUID.randomUUID(), operation, event, mod, mc, loader,
            loaderVersion, Math.max(8, Math.min(99, java)), os, entry, stage, outcome, reason,
            "client_started".equals(event) ? "client" : "guest",
            Math.max(0, Math.min(TelemetryProtocol.MAX_DURATION, duration)),
            Math.max(0, Math.min(1L << 40, bytes)), Math.max(0, Math.min(10000, count)));
    }
    private static void enqueue(TelemetryProtocol.Event event, long owner) {
        synchronized (LOCK) {
            if (!enabled || generation != owner || QUEUE.size() >= 64)
                return;
            QUEUE.addLast(new Pending(event, owner, clock.getAsLong()));
            LOCK.notifyAll();
        }
    }
    private static final class Pending {
        final TelemetryProtocol.Event event;
        final long generation, created;
        Pending(TelemetryProtocol.Event e, long g, long c) {
            event = e;
            generation = g;
            created = c;
        }
    }
    private static void work() {
        for (;;) {
            Pending p;
            synchronized (LOCK) {
                while (QUEUE.isEmpty()) {
                    try {
                        LOCK.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                p = QUEUE.removeFirst();
            }
            if (!current(p))
                continue;
            try {
                // Diagnostics follow the configured rendezvous service; never a peer-supplied endpoint.
                InetAddress address = InetAddress.getByName(PeerCraftConfig.rendezvousHost());
                byte[] bytes = TelemetryProtocol.encodeEvent(p.event);
                for (int attempt = 0; attempt < 3 && current(p); attempt++) {
                    try (DatagramSocket s = net.peercraft.network.control.SecureControlSocket.forServer()) {
                        s.connect(address, PeerCraftConfig.rendezvousPort());
                        s.setSoTimeout(1000);
                        synchronized (LOCK) {
                            if (!current(p))
                                break;
                            socket = s;
                            s.send(new DatagramPacket(bytes, bytes.length));
                        }
                        byte[] ack = new byte[32];
                        DatagramPacket reply = new DatagramPacket(ack, ack.length);
                        s.receive(reply);
                        if (TelemetryProtocol.decodeAck(ack, reply.getLength()).equals(p.event.eventId))
                            break;
                    } catch (java.io.IOException | IllegalArgumentException ignored) {
                    } finally {
                        socket = null;
                    }
                }
            } catch (RuntimeException
                | java.io.IOException ignored) { /* Diagnostic failure never changes gameplay. */
            }
        }
    }
    private static boolean current(Pending p) {
        return enabled && p.generation == generation && clock.getAsLong() - p.created <= TTL;
    }
}
