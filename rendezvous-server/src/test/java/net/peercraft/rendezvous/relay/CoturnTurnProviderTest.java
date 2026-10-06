package net.peercraft.rendezvous.relay;

import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoturnTurnProviderTest {
    static final String SECRET = "a1".repeat(32);
    Properties properties() { Properties p = new Properties(); p.setProperty("coturn.publicHost", "relay.example.org"); return p; }
    @Test void restCredentialsMatchCoturnTimestampAndHmacContract() throws Exception {
        CoturnTurnProvider provider = new CoturnTurnProvider(properties(), SECRET, () -> 1_780_000_000_123L);
        var credential = provider.issue(900, "account-id-not-exposed");
        assertTrue(credential.username().startsWith("1780000900:peercraft-"));
        assertEquals(1_780_000_900_000L, credential.expiresAt());
        Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        assertEquals(Base64.getEncoder().encodeToString(mac.doFinal(credential.username().getBytes(StandardCharsets.UTF_8))), credential.password());
        assertFalse(credential.username().contains("account-id")); assertFalse(credential.toString().contains(credential.password()));
        assertNotEquals(credential.username(), provider.issue(900, "same-id").username());
        assertEquals(List.of("turn:relay.example.org:3478?transport=udp"), credential.urls());
        assertFalse(provider.requiresUsageBudget()); assertFalse(provider.supportsIndividualRevocation()); assertEquals(2,provider.connectionLimit());
        assertThrows(java.io.IOException.class, () -> provider.issue(901, "id"));
        assertThrows(java.io.IOException.class, () -> provider.revoke(credential.username()));
    }
    @Test void invalidConfigurationNeverCreatesProvider() {
        assertThrows(java.io.IOException.class, () -> new CoturnTurnProvider(properties(), null, System::currentTimeMillis));
        for (String value : List.of("0", "5", "garbage")) {
            Properties p = properties(); p.setProperty("coturn.maxConnections", value);
            assertThrows(java.io.IOException.class, () -> new CoturnTurnProvider(p, SECRET, System::currentTimeMillis));
        }
        Properties p = properties(); p.setProperty("coturn.healthHost", "192.168.68.110");
        assertThrows(java.io.IOException.class, () -> new CoturnTurnProvider(p, SECRET, System::currentTimeMillis));
    }
    @Test void localHealthCheckRequiresMatchingStunSuccess() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            socket.setSoTimeout(5000); AtomicReference<Throwable> error = new AtomicReference<>();
            Thread responder = new Thread(() -> {
                try {
                    byte[] bytes = new byte[64]; DatagramPacket request = new DatagramPacket(bytes, bytes.length); socket.receive(request);
                    ByteBuffer.wrap(bytes).putShort((short)0x0101);
                    socket.send(new DatagramPacket(bytes, 20, request.getSocketAddress()));
                } catch (Throwable failure) { error.set(failure); }
            }); responder.start();
            Properties p = properties(); p.setProperty("coturn.healthPort", Integer.toString(socket.getLocalPort()));
            new CoturnTurnProvider(p, SECRET, System::currentTimeMillis).checkHealth();
            responder.join(5000); assertFalse(responder.isAlive()); assertNull(error.get());
        }
    }
}
