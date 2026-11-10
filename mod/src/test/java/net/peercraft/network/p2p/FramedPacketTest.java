package net.peercraft.network.p2p;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FramedPacketTest {

    @Test
    void roundTripPreservesAllFields() {
        byte[] payload = {1, 2, 3, 4, 5};
        byte[] encoded = FramedPacket.encode(123456789L, 42, payload);

        FramedPacket decoded = FramedPacket.decode(encoded, encoded.length);

        assertNotNull(decoded);
        assertEquals(123456789L, decoded.sessionId());
        assertEquals(42, decoded.seq());
        assertFalse(decoded.isFin());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void roundTripPreservesEmptyPayload() {
        byte[] encoded = FramedPacket.encode(1L, 0, new byte[0]);

        FramedPacket decoded = FramedPacket.decode(encoded, encoded.length);

        assertNotNull(decoded);
        assertEquals(0, decoded.payload().length);
    }

    @Test
    void finFlagRoundTrips() {
        byte[] encoded = FramedPacket.encode(1L, 5, (byte) FramedPacket.FLAG_FIN, new byte[0]);

        FramedPacket decoded = FramedPacket.decode(encoded, encoded.length);

        assertNotNull(decoded);
        assertTrue(decoded.isFin());
    }

    @Test
    void nonFinPacketIsNotFin() {
        byte[] encoded = FramedPacket.encode(1L, 5, new byte[]{9});

        FramedPacket decoded = FramedPacket.decode(encoded, encoded.length);

        assertFalse(decoded.isFin());
    }

    @Test
    void nackRoundTripPreservesMissingSeq() {
        byte[] encoded = FramedPacket.encodeNack(42L, 17);

        FramedPacket decoded = FramedPacket.decode(encoded, encoded.length);

        assertNotNull(decoded);
        assertEquals(FramedPacket.TYPE_NACK, decoded.type());
        assertEquals(42L, decoded.sessionId());
        assertEquals(17, decoded.nackSeq());
    }

    @Test
    void tooShortBufferIsRejected() {
        byte[] tooShort = new byte[FramedPacket.HEADER_SIZE - 1];

        assertNull(FramedPacket.decode(tooShort, tooShort.length));
    }

    @Test
    void unknownVersionIsRejected() {
        byte[] encoded = FramedPacket.encode(1L, 0, new byte[]{1});
        encoded[0] = 99; // corrupt version byte

        assertNull(FramedPacket.decode(encoded, encoded.length));
    }
}
