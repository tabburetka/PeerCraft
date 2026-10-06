package net.peercraft.network.transport;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SecureDatagramChannelTest {
    private final byte[] a = key(11), b = key(73);
    private final UUID link = UUID.randomUUID(), attempt = UUID.randomUUID();
    private SecureDatagramChannel sender() { return new SecureDatagramChannel(a, b, link, attempt); }
    private SecureDatagramChannel receiver() { return new SecureDatagramChannel(b, a, link, attempt); }

    @Test void fragmentsMaximumDatagramAndAcceptsReorderingOnce() throws Exception {
        byte[] payload = new byte[SecureDatagramChannel.MAX_PAYLOAD_BYTES];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        List<byte[]> frames = sender().encode(payload); Collections.reverse(frames);
        SecureDatagramChannel receiver = receiver(); byte[] result = null;
        for (byte[] frame : frames) {
            assertTrue(frame.length + 4 <= 1200); assertEquals(link, SecureDatagramChannel.peekLinkId(frame));
            assertEquals(attempt, SecureDatagramChannel.peekAttemptId(frame));
            byte[] accepted = receiver.accept(frame, 0); if (accepted != null) result = accepted;
            assertNull(receiver.accept(frame, 0));
        }
        assertArrayEquals(payload, result); assertEquals(0, receiver.pendingAssemblies());
    }
    @Test void rejectsForgeryWithoutPoisoningValidNonce() throws Exception {
        byte[] valid = sender().encode(new byte[] { 1, 2, 3 }).get(0);
        byte[] forged = valid.clone(); forged[forged.length - 1] ^= 1;
        SecureDatagramChannel receiver = receiver();
        assertNull(receiver.accept(forged, 100));
        assertArrayEquals(new byte[] { 1, 2, 3 }, receiver.accept(valid, 100));
        assertNull(receiver.accept(valid, 100));
    }
    @Test void authenticatesIdentityMetadataAndRejectsAnotherAttempt() throws Exception {
        byte[] valid = sender().encode(new byte[] { 9 }).get(0);
        byte[] changedHeader = valid.clone(); changedHeader[59] ^= 1;
        assertNull(receiver().accept(changedHeader, 0));
        assertNull(new SecureDatagramChannel(b, a, link, UUID.randomUUID()).accept(valid, 0));
        assertNull(new SecureDatagramChannel(key(51), a, UUID.randomUUID(), attempt).accept(valid, 0));
        assertFalse(SecureDatagramChannel.isPacket(new byte[2]));
        assertNull(SecureDatagramChannel.peekLinkId(new byte[2]));
    }
    @Test void allocationChangeKeepsNonceSequenceAndAllowsPacketReordering() throws Exception {
        SecureDatagramChannel sender = sender(), receiver = receiver();
        byte[] first = sender.encode(new byte[] { 1 }).get(0);
        byte[] next = sender.encode(new byte[] { 2 }).get(0);
        assertEquals(1, ByteBuffer.wrap(first).getLong(36));
        assertEquals(2, ByteBuffer.wrap(next).getLong(36));
        assertArrayEquals(new byte[] { 2 }, receiver.accept(next, 0));
        assertArrayEquals(new byte[] { 1 }, receiver.accept(first, 0));
        assertNull(receiver.accept(next, 0));
    }
    @Test void boundedReassemblyExpiresAndOversizedPayloadFails() throws Exception {
        SecureDatagramChannel sender = sender(), receiver = receiver();
        for (int i = 0; i < 65; i++) assertNull(receiver.accept(sender.encode(new byte[1121]).get(0), 0));
        assertEquals(64, receiver.pendingAssemblies());
        assertArrayEquals(new byte[] { 3 }, receiver.accept(sender.encode(new byte[] { 3 }).get(0), 10000));
        assertEquals(0, receiver.pendingAssemblies());
        assertThrows(java.io.IOException.class, () -> sender.encode(new byte[65508]));
    }
    @Test void emptyPayloadRoundTripsAndDirectionalKeysCannotCoincide() throws Exception {
        assertArrayEquals(new byte[0], receiver().accept(sender().encode(new byte[0]).get(0), 0));
        assertThrows(IllegalArgumentException.class, () -> new SecureDatagramChannel(a, a, link, attempt));
        assertThrows(IllegalArgumentException.class, () -> new SecureDatagramChannel(new byte[32], b, link, attempt));
    }
    @Test void oldPacketsFallOutsideReplayWindow() throws Exception {
        SecureDatagramChannel sender = sender(), receiver = receiver();
        byte[] first = sender.encode(new byte[] { 1 }).get(0), latest = null;
        for (int i = 0; i < 4096; i++) latest = sender.encode(new byte[] { 2 }).get(0);
        assertArrayEquals(new byte[] { 2 }, receiver.accept(latest, 0));
        assertNull(receiver.accept(first, 0));
    }
    private static byte[] key(int value) { byte[] bytes = new byte[16]; Arrays.fill(bytes, (byte) value); return bytes; }
}
