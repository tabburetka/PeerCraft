package net.peercraft.network.p2p;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

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

    private static final long MS = 1_000_000L;

    @Test
    void longQuietStretchDoesNotCountAgainstTheNextGap() {
        AtomicLong clock = new AtomicLong();
        ReorderBuffer buf = new ReorderBuffer(clock::get);
        assertNotNull(buf.accept(0, new byte[]{1}).deliverable);

        clock.addAndGet((ReorderBuffer.GAP_TIMEOUT_MILLIS + 5_000) * MS); // no traffic at all
        ReorderBuffer.Result reordered = buf.accept(2, new byte[]{3});
        assertEquals(1L, reordered.requestSeq.longValue()); // a NACK, not a broken session

        assertArrayEquals(new byte[]{2, 3}, buf.accept(1, new byte[]{2}).deliverable);
    }

    @Test
    void pollRepeatsTheNackWhenNothingElseArrives() {
        AtomicLong clock = new AtomicLong();
        ReorderBuffer buf = new ReorderBuffer(clock::get);
        assertEquals(0L, buf.accept(1, new byte[]{2}).requestSeq.longValue()); // this NACK is "lost"

        assertNull(buf.poll().requestSeq); // too soon to ask again
        clock.addAndGet((ReorderBuffer.NACK_RETRY_MILLIS + 1) * MS);
        assertEquals(0L, buf.poll().requestSeq.longValue());
        clock.addAndGet((ReorderBuffer.NACK_RETRY_MILLIS + 1) * MS);
        assertEquals(0L, buf.poll().requestSeq.longValue());
    }

    @Test
    void pollIsQuietWithoutAGap() {
        AtomicLong clock = new AtomicLong();
        ReorderBuffer buf = new ReorderBuffer(clock::get);
        buf.accept(0, new byte[]{1});
        clock.addAndGet((ReorderBuffer.GAP_TIMEOUT_MILLIS + 1) * MS);
        assertNull(buf.poll().requestSeq);
    }

    @Test
    void gapTimesOutFromWhenItAppearedEvenWithoutNewDatagrams() {
        AtomicLong clock = new AtomicLong();
        ReorderBuffer buf = new ReorderBuffer(clock::get);
        buf.accept(0, new byte[]{1});
        clock.addAndGet(60_000 * MS);
        buf.accept(2, new byte[]{3}); // gap at seq=1 starts here
        clock.addAndGet((ReorderBuffer.GAP_TIMEOUT_MILLIS - 1) * MS);
        buf.poll(); // still within the limit
        clock.addAndGet(2 * MS);
        assertThrows(ReorderBuffer.SessionBrokenException.class, buf::poll);
    }

    @Test
    void secondGapGetsItsOwnTimeout() {
        AtomicLong clock = new AtomicLong();
        ReorderBuffer buf = new ReorderBuffer(clock::get);
        buf.accept(1, new byte[]{2});
        buf.accept(3, new byte[]{4});
        clock.addAndGet((ReorderBuffer.GAP_TIMEOUT_MILLIS - 100) * MS);
        assertArrayEquals(new byte[]{1, 2}, buf.accept(0, new byte[]{1}).deliverable); // first gap filled late
        clock.addAndGet(200 * MS);
        assertEquals(2L, buf.poll().requestSeq.longValue()); // seq=2 is a fresh gap, not expired
    }
}
