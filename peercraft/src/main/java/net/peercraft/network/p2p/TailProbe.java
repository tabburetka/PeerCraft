package net.peercraft.network.p2p;

/**
 * Sender-side recovery for the last DATA datagram of a burst. The receiver only notices a loss
 * when a later seq arrives, so if the final datagram before a pause (a login packet, the reply
 * the other side is waiting on) is lost, nothing would ever ask for it again. After the sender
 * goes quiet it resends that last datagram a few times with growing gaps: a receiver that
 * already has it drops the duplicate, one that lost it gets it (or, if several were lost,
 * sees the gap and NACKs the rest). Needs no wire change, so older peers handle it as-is.
 */
final class TailProbe {

    /** Idle times (since the last send) at which the last datagram is resent. */
    static final long[] PROBE_DELAYS_MILLIS = {200L, 1_000L, 4_000L};

    private final java.util.function.LongSupplier nanoClock;
    private long lastSeq = -1;
    private long lastSentAt;
    private int probesSent;

    TailProbe() {
        this(System::nanoTime);
    }

    TailProbe(java.util.function.LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    synchronized void sent(long seq) {
        lastSeq = seq;
        lastSentAt = nanoClock.getAsLong();
        probesSent = 0;
    }

    /** @return the seq to resend now, or -1 when nothing is due. */
    synchronized long due() {
        if (lastSeq < 0 || probesSent >= PROBE_DELAYS_MILLIS.length) return -1;
        if (nanoClock.getAsLong() - lastSentAt < PROBE_DELAYS_MILLIS[probesSent] * 1_000_000L) return -1;
        probesSent++;
        return lastSeq;
    }
}
