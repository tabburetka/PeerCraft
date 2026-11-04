package net.peercraft.network.handoff;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
class ManifestExchangeTest {
    @Test void repairsLostHeaderAndAckWithoutGrowingOffer() throws Exception {
        HostExecutionManifest manifest = new HostExecutionManifest("1", "fabric", "1", Collections.emptyList(), Collections.emptyMap());
        ManifestExchange[] peers = new ManifestExchange[2];
        AtomicBoolean headerLost = new AtomicBoolean(), ackLost = new AtomicBoolean();
        peers[0] = new ManifestExchange((type, payload) -> {
            assertTrue(payload.length <= HandoffControlProtocol.MAX_PAYLOAD);
            if (type == HandoffControlProtocol.MANIFEST && java.nio.ByteBuffer.wrap(payload).getInt() == -1 && !headerLost.getAndSet(true)) return;
            peers[1].receive(type, payload);
        });
        peers[1] = new ManifestExchange((type, payload) -> {
            if (type == HandoffControlProtocol.MANIFEST_ACK && !ackLost.getAndSet(true)) return;
            peers[0].receive(type, payload);
        });
        try {
            peers[0].send(manifest).get(3, TimeUnit.SECONDS);
            assertTrue(manifest.differences(peers[1].incoming().get(3, TimeUnit.SECONDS), HostExecutionManifest.Profile.strict()).isEmpty());
        } finally { peers[0].close(); peers[1].close(); }
    }
}
