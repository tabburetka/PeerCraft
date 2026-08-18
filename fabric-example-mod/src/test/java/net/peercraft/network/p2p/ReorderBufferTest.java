package net.peercraft.network.p2p;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReorderBufferTest {

    @Test
    void inOrderDeliveryPassesThroughImmediately() {
        ReorderBuffer buf = new ReorderBuffer();

        ReorderBuffer.Result result = buf.accept(0, new byte[]{1, 2, 3});

        assertArrayEquals(new byte[]{1, 2, 3}, result.deliverable);
        assertNull(result.requestSeq);
    }

    @Test
    void outOfOrderPacketRequestsMissingSeqThenFlushesOnceGapFills() {
        ReorderBuffer buf = new ReorderBuffer();

        ReorderBuffer.Result first = buf.accept(1, new byte[]{2}); // arrives early, buffered
        assertNull(first.deliverable);
        assertEquals(0L, first.requestSeq.longValue()); // asks for the missing seq=0

        ReorderBuffer.Result second = buf.accept(0, new byte[]{1}); // fills the gap
        assertArrayEquals(new byte[]{1, 2}, second.deliverable);
        assertNull(second.requestSeq);
    }

    @Test
    void repeatedGapArrivalsAreDebouncedNotReRequestedEveryTime() {
        ReorderBuffer buf = new ReorderBuffer();

        ReorderBuffer.Result first = buf.accept(1, new byte[]{2});
        assertEquals(0L, first.requestSeq.longValue());

        // Same still-missing gap arrives again immediately — should not re-request yet.
        ReorderBuffer.Result second = buf.accept(2, new byte[]{3});
        assertNull(second.requestSeq);
    }

    @Test
    void multipleBufferedPacketsFlushInOrderOnceContiguous() {
        ReorderBuffer buf = new ReorderBuffer();

        buf.accept(2, new byte[]{3});
        buf.accept(1, new byte[]{2});
        ReorderBuffer.Result result = buf.accept(0, new byte[]{1});

        assertArrayEquals(new byte[]{1, 2, 3}, result.deliverable);
    }

    @Test
    void staleDuplicateSeqIsDroppedWithoutReflush() {
        ReorderBuffer buf = new ReorderBuffer();
        buf.accept(0, new byte[]{1});

        ReorderBuffer.Result result = buf.accept(0, new byte[]{1});

        assertNull(result.deliverable);
        assertNull(result.requestSeq);
    }

    @Test
    void overflowPastMaxPendingThrows() {
        ReorderBuffer buf = new ReorderBuffer();
        // seq 0 is never delivered, so every out-of-order packet stays buffered.
        for (int i = 1; i <= ReorderBuffer.MAX_PENDING; i++) {
            buf.accept(i, new byte[]{(byte) i});
        }

        assertThrows(ReorderBuffer.SessionBrokenException.class,
                () -> buf.accept(ReorderBuffer.MAX_PENDING + 1, new byte[]{0}));
    }
}
