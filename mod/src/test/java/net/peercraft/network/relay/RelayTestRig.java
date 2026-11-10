package net.peercraft.network.relay;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Run the same application protocol over the fake fixture or an actual coturn process. */
final class RelayTestRig implements AutoCloseable {
    final RelayPeerTransportIntegrationTest.Broker broker;
    final RelayPeerTransportIntegrationTest.TurnServer fake;
    final CoturnIntegrationTest.LocalCoturn actual;
    RelayTestRig(Path directory, boolean real) throws Exception {
        if (real) {
            fake = null; actual = new CoturnIntegrationTest.LocalCoturn(Files.createDirectory(directory.resolve("coturn")));
            broker = CoturnIntegrationTest.broker(actual);
        } else {
            actual = null; fake = new RelayPeerTransportIntegrationTest.TurnServer();
            broker = new RelayPeerTransportIntegrationTest.Broker(fake);
        }
    }
    void assertHealthy() {
        if (fake != null) { assertTrue(fake.maxFrame.get() <= 1200); assertNull(fake.failure.get()); }
        else assertTrue(actual.process.isAlive());
    }
    public void close() { if (actual != null) actual.close(); if (fake != null) fake.close(); }
}
