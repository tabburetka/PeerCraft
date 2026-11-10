package net.peercraft.relay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import net.peercraft.network.ownrelay.OwnRelayProtocol;
import org.junit.jupiter.api.Test;

class OwnRelayProtocolTest {
    private static final byte[] SECRET = new byte[32];
    static { Arrays.fill(SECRET, (byte) 3); }

    @Test void codeRoundTripsForHostnamesAndIpv6() {
        String code = OwnRelayProtocol.formatCode("relay.example.org", 52100, SECRET);
        OwnRelayProtocol.Code parsed = OwnRelayProtocol.parseCode("  " + code + " ");
        assertNotNull(parsed);
        assertEquals("relay.example.org", parsed.host);
        assertEquals(52100, parsed.port);
        assertArrayEquals(SECRET, parsed.secret());

        OwnRelayProtocol.Code v6 = OwnRelayProtocol.parseCode(OwnRelayProtocol.formatCode("2001:db8::5", 6000, SECRET));
        assertNotNull(v6);
        assertEquals("2001:db8::5", v6.host);
        assertEquals(6000, v6.port);
    }

    @Test void rejectsMalformedCodes() {
        assertNull(OwnRelayProtocol.parseCode(""));
        assertNull(OwnRelayProtocol.parseCode("http://1.2.3.4:5#abc"));
        assertNull(OwnRelayProtocol.parseCode("pcrelay://1.2.3.4:5"));
        assertNull(OwnRelayProtocol.parseCode("pcrelay://1.2.3.4:5#c2hvcnQ"));
        assertNull(OwnRelayProtocol.parseCode("pcrelay://user@1.2.3.4:5#" + "A".repeat(43)));
        assertNotNull(OwnRelayProtocol.parseCode("pcrelay://1.2.3.4#" + "A".repeat(43)));
    }

    @Test void tamperedSignedMessagesAreRejected() {
        byte[] nonce = new byte[16];
        byte[] open = OwnRelayProtocol.encodeAuth(OwnRelayProtocol.OPEN, SECRET, nonce, 1000);
        assertEquals(0, OwnRelayProtocol.verifyAuth(open, open.length, SECRET, 1000));
        open[5] ^= 1;
        assertEquals(OwnRelayProtocol.REASON_AUTH, OwnRelayProtocol.verifyAuth(open, open.length, SECRET, 1000));

        byte[] opened = OwnRelayProtocol.encodeOpened(SECRET, nonce, 42);
        assertEquals(42, OwnRelayProtocol.openedSession(opened, opened.length, SECRET, nonce));
        byte[] otherNonce = new byte[16]; otherNonce[0] = 1;
        assertEquals(0, OwnRelayProtocol.openedSession(opened, opened.length, SECRET, otherNonce));
        opened[4] ^= 1;
        assertEquals(0, OwnRelayProtocol.openedSession(opened, opened.length, SECRET, nonce));
    }

    @Test void foreignDatagramsAreNotRelayPackets() {
        assertEquals(-1, OwnRelayProtocol.type(new byte[] { (byte) 0xE1, 1, 1 }, 3));
        assertEquals(-1, OwnRelayProtocol.type(new byte[] { (byte) 0xEB, 9, 1 }, 3));
        assertNull(OwnRelayProtocol.dataPayload(OwnRelayProtocol.encodeSession(OwnRelayProtocol.KEEPALIVE, 1), 11));
    }
}
