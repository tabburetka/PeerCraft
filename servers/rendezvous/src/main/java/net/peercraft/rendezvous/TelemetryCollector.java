package net.peercraft.rendezvous;

import java.net.InetAddress;
import java.util.*;
import java.util.function.LongSupplier;
import net.peercraft.network.telemetry.TelemetryProtocol;

/** In-memory retry/operation correlation; only bounded aggregates reach AnalyticsStore. */
final class TelemetryCollector {
    private static final long TTL = 24 * 60 * 60_000L;
    private final AnalyticsStore analytics;
    private final LongSupplier clock;
    private long nextMaintenance;
    private final LinkedHashMap<UUID, Long> events = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Operation> operations = new LinkedHashMap<>();
    private final LinkedHashMap<InetAddress, Window> rates = new LinkedHashMap<>();
    private static final class Window {
        long start;
        int count;
        Window(long time) {
            start = time;
        }
    }
    private static final class Operation {
        final String date;
        final long created;
        final Set<String> seen = new HashSet<>();
        Operation(String date, long now) {
            this.date = date;
            created = now;
        }
    }
    TelemetryCollector(AnalyticsStore analytics, LongSupplier clock) {
        this.analytics = analytics;
        this.clock = clock;
    }
    synchronized byte[] accept(byte[] packet, InetAddress address) {
        if (analytics == null)
            return null;
        long now = clock.getAsLong();
        if (now >= nextMaintenance)
            maintenance();
        Window w = rates.get(address);
        if (w == null) {
            if (rates.size() >= 4096) {
                rates.entrySet().removeIf(e -> now - e.getValue().start >= 60_000);
                if (rates.size() >= 4096)
                    return null;
            }
            w = new Window(now);
            rates.put(address, w);
        }
        if (now - w.start >= 60_000) {
            w.start = now;
            w.count = 0;
        }
        if (++w.count > 600)
            return null;
        TelemetryProtocol.Event e;
        try {
            e = TelemetryProtocol.decodeEvent(packet, packet.length);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (events.containsKey(e.eventId))
            return TelemetryProtocol.encodeAck(e.eventId);
        if (events.size() >= 50000)
            return null;
        Operation op = operations.get(e.operationId);
        if (op == null) {
            if (operations.size() >= 50000)
                return null;
            op = new Operation(analytics.telemetryDate(), now);
            operations.put(e.operationId, op);
        }
        events.put(e.eventId, now);
        String date = op.date;
        // Different packet IDs may describe the same lifecycle transition: count once per operation.
        String transition = e.event.equals("stage_finished") ? e.event + ":" + e.stage : e.event;
        if (!op.seen.add(transition))
            return TelemetryProtocol.encodeAck(e.eventId);
        add(date, "events", e.event);
        if (e.event.equals("client_started")) {
            add(date, "mod_versions", e.modVersion);
            add(date, "mc_versions", e.mcVersion);
            add(date, "loaders", e.loader + ":" + e.loaderVersion);
            add(date, "java", Integer.toString(e.javaMajor));
            add(date, "os", e.os);
        }
        if (e.event.startsWith("join_") || e.event.equals("transport_ready") || e.event.equals("world_joined")
            || e.event.equals("stage_finished")) {
            if (op.seen.add("observed_join")) {
                add(date, "joins", "observed");
                add(date, "entries", e.entry);
            }
        }
        if (e.event.equals("join_result")) {
            add(date, "joins", e.outcome);
            add(date, "results_by_mc", e.mcVersion + ":" + e.outcome);
            add(date, "results_by_mod", e.modVersion + ":" + e.outcome);
            add(date, "results_by_loader", e.loader + ":" + e.outcome);
            if (e.outcome.equals("failure")) {
                add(date, "errors", e.stage + ":" + e.reason);
                add(date, "error_mod_versions", e.modVersion);
            }
            add(date, "join_time_" + e.outcome, bucket(e.durationMillis));
        }
        if (e.event.equals("stage_finished"))
            add(date, "time_" + e.stage, bucket(e.durationMillis));
        if (e.event.equals("modsync_result")) {
            add(date, "modsync", e.outcome + ":" + e.reason);
            add(date, "time_modsync", bucket(e.durationMillis));
        }
        if (e.event.equals("session_summary")) {
            add(date, "disconnects", e.reason);
            add(date, "session_duration", bucket(e.durationMillis));
        }
        return TelemetryProtocol.encodeAck(e.eventId);
    }
    synchronized void maintenance() {
        long now = clock.getAsLong();
        nextMaintenance = now + 30_000;
        rates.entrySet().removeIf(e -> now - e.getValue().start >= 60_000);
        while (!events.isEmpty() && now - events.entrySet().iterator().next().getValue() > TTL)
            events.remove(events.keySet().iterator().next());
        while (!operations.isEmpty() && now - operations.values().iterator().next().created > TTL)
            operations.remove(operations.keySet().iterator().next());
    }
    private void add(String date, String dimension, String value) {
        analytics.telemetry(date, dimension, value);
    }
    private static String bucket(long ms) {
        return ms < 1000   ? "lt_1s"
            : ms < 5000    ? "1_5s"
            : ms < 15000   ? "5_15s"
            : ms < 60000   ? "15_60s"
            : ms < 300000  ? "1_5m"
            : ms < 1800000 ? "5_30m"
                           : "gte_30m";
    }
}
