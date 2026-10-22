package net.peercraft.rendezvous;
import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.peercraft.network.telemetry.TelemetryProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class TelemetryCollectorTest {
    @TempDir Path dir;
    private TelemetryProtocol.Event event(UUID id, UUID op, String kind, String outcome) {
        return new TelemetryProtocol.Event(id, op, kind, "3.4.2", "1.21.1", "fabric", "0.19.5", 21, "linux",
            "friend", "minecraft_login", outcome, "none", "guest", 1200, 0, 0);
    }
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Long>> metrics(AnalyticsStore store) {
        return (Map<String, Map<String, Long>>) ((List<Map<String, Object>>) store.snapshot().get("days"))
            .getFirst()
            .get("telemetry");
    }
    @Test
    void deduplicatesRetriesAndOperationResultsWithoutPersistingIdentifiers() throws Exception {
        var clock = new AtomicLong(Instant.parse("2026-10-22T12:00:00Z").toEpochMilli());
        var store = new AnalyticsStore(dir, clock::get);
        var collector = new TelemetryCollector(store, clock::get);
        UUID op = UUID.randomUUID(), id = UUID.randomUUID();
        var address = InetAddress.getByName("198.51.100.77");
        byte[] start = TelemetryProtocol.encodeEvent(event(id, op, "join_started", "none"));
        assertEquals(id, TelemetryProtocol.decodeAck(collector.accept(start, address), 19));
        collector.accept(start, address);
        collector.accept(
            TelemetryProtocol.encodeEvent(event(UUID.randomUUID(), op, "world_joined", "success")), address);
        collector.accept(
            TelemetryProtocol.encodeEvent(event(UUID.randomUUID(), op, "join_result", "success")), address);
        collector.accept(
            TelemetryProtocol.encodeEvent(event(UUID.randomUUID(), op, "join_result", "failure")), address);
        assertEquals(Map.of("observed", 1L, "success", 1L), metrics(store).get("joins"));
        store.flush();
        String disk = Files.readString(dir.resolve("analytics.json"));
        assertFalse(disk.contains(op.toString()));
        assertFalse(disk.contains(id.toString()));
        assertFalse(disk.contains(address.getHostAddress()));
        assertFalse(disk.contains("visitors\":[\""));
    }
    @Test
    void acceptsLateOutcomeInOriginalDailyCohortAndKeepsCancellationSeparate() throws Exception {
        var clock = new AtomicLong(Instant.parse("2026-10-22T23:59:59Z").toEpochMilli());
        var store = new AnalyticsStore(dir, clock::get);
        var collector = new TelemetryCollector(store, clock::get);
        var op = UUID.randomUUID();
        var address = InetAddress.getLoopbackAddress();
        collector.accept(
            TelemetryProtocol.encodeEvent(event(UUID.randomUUID(), op, "join_started", "none")), address);
        clock.addAndGet(5000);
        collector.accept(
            TelemetryProtocol.encodeEvent(event(UUID.randomUUID(), op, "join_result", "cancelled")), address);
        assertEquals(Map.of("observed", 1L, "cancelled", 1L), metrics(store).get("joins"));
    }
    @Test
    void rejectsMalformedFieldsAndPacketsAndBoundsReflection() throws Exception {
        var store = new AnalyticsStore(dir, System::currentTimeMillis);
        var collector = new TelemetryCollector(store, System::currentTimeMillis);
        var e = event(UUID.randomUUID(), UUID.randomUUID(), "join_result", "failure");
        byte[] packet = TelemetryProtocol.encodeEvent(e);
        for (int n = 0; n < packet.length; n++)
            assertNull(collector.accept(Arrays.copyOf(packet, n), InetAddress.getLoopbackAddress()));
        byte[] trailing = Arrays.copyOf(packet, packet.length + 1);
        assertNull(collector.accept(trailing, InetAddress.getLoopbackAddress()));
        byte[] ack = collector.accept(packet, InetAddress.getLoopbackAddress());
        assertNotNull(ack);
        assertTrue(ack.length < packet.length);
        var invalid = event(UUID.randomUUID(), UUID.randomUUID(), "chat_message", "failure");
        assertThrows(IllegalArgumentException.class, () -> TelemetryProtocol.encodeEvent(invalid));
    }
    @Test
    void actualUdpServerAcknowledgesDiagnosticsWithoutCountingVisitors() throws Exception {
        var server = new RendezvousServer(0, System::currentTimeMillis, dir, true);
        Thread t = new Thread(() -> {
            try {
                server.run();
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        try (DatagramSocket client = new DatagramSocket()) {
            long end = System.currentTimeMillis() + 2000;
            while (server.getBoundPort() == 0 && System.currentTimeMillis() < end) Thread.sleep(5);
            byte[] packet = TelemetryProtocol.encodeEvent(
                event(UUID.randomUUID(), UUID.randomUUID(), "join_result", "failure"));
            client.setSoTimeout(2000);
            client.send(new DatagramPacket(
                packet, packet.length, InetAddress.getLoopbackAddress(), server.getBoundPort()));
            byte[] b = new byte[32];
            DatagramPacket reply = new DatagramPacket(b, b.length);
            client.receive(reply);
            assertEquals(19, reply.getLength());
        } finally {
            server.close();
            t.join(2000);
        }
        var snapshot = new AnalyticsStore(dir.resolve("analytics"), System::currentTimeMillis).snapshot();
        assertFalse(snapshot.toString().contains("visitors=1"));
        assertTrue(snapshot.toString().contains("failure=1"));
    }
    @Test
    void rateLimitRecoversAfterWindowAndPersistedDimensionsStayBounded() throws Exception {
        var clock = new AtomicLong(Instant.parse("2026-10-22T12:00:00Z").toEpochMilli());
        var store = new AnalyticsStore(dir, clock::get);
        var collector = new TelemetryCollector(store, clock::get);
        var address = InetAddress.getLoopbackAddress();
        byte[] packet = TelemetryProtocol.encodeEvent(
            event(UUID.randomUUID(), UUID.randomUUID(), "join_started", "none"));
        for (int i = 0; i < 600; i++) assertNotNull(collector.accept(packet, address));
        assertNull(collector.accept(packet, address));
        clock.addAndGet(60_001);
        collector.maintenance();
        assertNotNull(collector.accept(packet, address));
        for (int i = 0; i < 100; i++) store.telemetry(store.telemetryDate(), "mod_versions", "3.4." + i);
        assertEquals(65, metrics(store).get("mod_versions").size());
        assertEquals(36L, metrics(store).get("mod_versions").get("other"));
    }
}
