package net.peercraft.network.p2p;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TailProbeTest {

    private static final long MS = 1_000_000L;

    @Test
    void nothingIsDueBeforeAnySend() {
        TailProbe probe = new TailProbe(() -> 10_000 * MS);
        assertEquals(-1, probe.due());
    }

    @Test
    void lastSeqIsResentAtGrowingIdleTimesThenStops() {
        AtomicLong clock = new AtomicLong();
        TailProbe probe = new TailProbe(clock::get);
        probe.sent(7);

        assertEquals(-1, probe.due());
        for (long delay : TailProbe.PROBE_DELAYS_MILLIS) {
            clock.set(delay * MS);
            assertEquals(7, probe.due());
            assertEquals(-1, probe.due()); // once per step
        }
        clock.addAndGet(60_000 * MS);
        assertEquals(-1, probe.due());
    }

    @Test
    void newSendRestartsTheSchedule() {
        AtomicLong clock = new AtomicLong();
        TailProbe probe = new TailProbe(clock::get);
        probe.sent(1);
        clock.set(TailProbe.PROBE_DELAYS_MILLIS[0] * MS);
        assertEquals(1, probe.due());

        probe.sent(2);
        assertEquals(-1, probe.due());
        clock.addAndGet(TailProbe.PROBE_DELAYS_MILLIS[0] * MS);
        assertEquals(2, probe.due());
    }
}
