package net.peercraft.network.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Timeout(10)
class TelemetryGateTest {
    private DatagramSocket server;
    private final BlockingQueue<TelemetryProtocol.Event> received = new LinkedBlockingQueue<>();
    private Thread listener;
    private String host, port;
    @BeforeEach
    void setup() throws Exception {
        Telemetry.setEnabled(false);
        host = System.getProperty("peercraft.rendezvousHost");
        port = System.getProperty("peercraft.rendezvousPort");
        server = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        System.setProperty("peercraft.rendezvousHost", "127.0.0.1");
        System.setProperty("peercraft.rendezvousPort", Integer.toString(server.getLocalPort()));
        Telemetry.initialize("3.4.2", "1.21.1", "fabric", "0.19.5");
        listener = new Thread(() -> {
            while (!server.isClosed()) try {
                    byte[] bytes = new byte[512];
                    DatagramPacket p = new DatagramPacket(bytes, bytes.length);
                    server.receive(p);
                    var e = TelemetryProtocol.decodeEvent(bytes, p.getLength());
                    byte[] ack = TelemetryProtocol.encodeAck(e.eventId);
                    server.send(new DatagramPacket(ack, ack.length, p.getAddress(), p.getPort()));
                    received.add(e);
                } catch (Exception ignored) {
                }
        });
        listener.setDaemon(true);
        listener.start();
    }
    @AfterEach
    void cleanup() throws Exception {
        Telemetry.setEnabled(false);
        server.close();
        listener.join(1000);
        restore("peercraft.rendezvousHost", host);
        restore("peercraft.rendezvousPort", port);
    }
    private void restore(String key, String value) {
        if (value == null)
            System.clearProperty(key);
        else
            System.setProperty(key, value);
    }
    private TelemetryProtocol.Event await(String event) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < end) {
            var e = received.poll(200, TimeUnit.MILLISECONDS);
            if (e != null && e.event.equals(event))
                return e;
        }
        fail("Missing event " + event);
        return null;
    }
    @Test
    void noPacketsBeforeConsentAndNoRetroactiveCollection() throws Exception {
        var old = Telemetry.beginJoin("friend");
        old.stage("direct");
        old.worldJoined();
        assertNull(received.poll(150, TimeUnit.MILLISECONDS));
        Telemetry.setEnabled(true);
        old.sessionEnded("transport_failure");
        var current = Telemetry.beginJoin("code");
        current.finish("failed", "a private path /home/person/log");
        var start = await("join_started");
        var result = await("join_result");
        assertEquals(start.operationId, result.operationId);
        assertEquals("other", result.reason);
        assertEquals("failure", result.outcome);
        assertNull(received.poll(150, TimeUnit.MILLISECONDS));
    }
    @Test
    void transportCallbackCannotClaimWorldJoinAndTerminalIsExactlyOnce() throws Exception {
        Telemetry.setEnabled(true);
        var operation = Telemetry.beginJoin("public");
        await("join_started");
        operation.stage("direct");
        operation.transportReady();
        await("transport_ready");
        operation.finish("success", "none");
        assertNull(received.poll(150, TimeUnit.MILLISECONDS));
        operation.worldJoined();
        operation.worldJoined();
        operation.finish("failure", "timeout");
        await("world_joined");
        assertEquals("success", await("join_result").outcome);
        assertNull(received.poll(150, TimeUnit.MILLISECONDS));
    }
    @Test
    void turningOffInvalidatesOldOperationsEvenAfterTurningBackOn() throws Exception {
        Telemetry.setEnabled(true);
        var old = Telemetry.beginJoin("friend");
        await("join_started");
        Telemetry.setEnabled(false);
        old.finish("failed", "timeout");
        Telemetry.setEnabled(true);
        old.stage("direct");
        old.worldJoined();
        assertNull(received.poll(200, TimeUnit.MILLISECONDS));
        var fresh = Telemetry.beginJoin("code");
        fresh.finish("restart_required", "restart_required");
        await("join_started");
        assertEquals("restart_required", await("join_result").outcome);
    }
}
