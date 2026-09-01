package net.peercraft.network.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reassembles a per-direction, per-session stream of {@link FramedPacket} payloads back
 * into send order. Datagrams that arrive early are buffered until the gap is filled;
 * datagrams that arrive late (duplicates/already-delivered) are dropped.
 *
 * On detecting a gap, {@link #accept} returns the seq that's missing (debounced) so the
 * caller can send a NACK asking the peer to resend just that one packet — this is the
 * primary loss-recovery path. A gap that still isn't resolved within
 * {@link #GAP_TIMEOUT_MILLIS}, or a backlog that grows past {@link #MAX_PENDING}, is
 * treated as an unrecoverable session error (e.g. the NACK itself got lost repeatedly,
 * or the peer is gone) so the caller can close the connection cleanly as a last resort.
 *
 * <p>The three limits below are deliberately generous and can be overridden at launch with
 * {@code -Dpeercraft.reorder.maxPending}, {@code -Dpeercraft.reorder.gapTimeoutMillis} and
 * {@code -Dpeercraft.reorder.nackDebounceMillis}. On a lossy or DPI-mangled path — e.g.
 * running ZAPRET, which by design reorders / duplicates / drops UDP datagrams — the old
 * 3 s / 128-packet ceilings were reached routinely during ordinary chunk-sync bursts and
 * tore the player out of the world; a transient burst of loss should cost a brief stutter
 * while the NACKs do their job, not a full disconnect. {@link #GAP_TIMEOUT_MILLIS} still
 * stays well under vanilla Minecraft's own 30 s read timeout, so a genuinely dead peer is
 * still dropped within a reasonable time.
 */
public final class ReorderBuffer {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    /** Max datagrams buffered behind one missing seq before the session is declared broken. */
    public static final int MAX_PENDING = (int) longProp("peercraft.reorder.maxPending", 4096, 64, 1 << 20);
    /** How long a single unfilled gap may block delivery before the session is declared broken. */
    public static final long GAP_TIMEOUT_MILLIS = longProp("peercraft.reorder.gapTimeoutMillis", 12_000L, 1_000L, 600_000L);
    /** Minimum spacing between successive NACKs for the same missing seq. */
    public static final long NACK_DEBOUNCE_MILLIS = longProp("peercraft.reorder.nackDebounceMillis", 120L, 10L, 5_000L);

    private static long longProp(String key, long def, long min, long max) {
        String raw = System.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            return def;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed < min || parsed > max) {
                LOGGER.warn("[ReorderBuffer] {}={} вне допустимого диапазона [{}, {}] — использую {}", key, parsed, min, max, def);
                return def;
            }
            LOGGER.info("[ReorderBuffer] {} переопределён на {} (по умолчанию {})", key, parsed, def);
            return parsed;
        } catch (NumberFormatException e) {
            LOGGER.warn("[ReorderBuffer] {}='{}' — не число, использую {}", key, raw, def);
            return def;
        }
    }

    private final TreeMap<Long, byte[]> pending = new TreeMap<>();
    private long expectedSeq = 0;
    private long expectedSince = System.currentTimeMillis();
    private long lastNackSentAt = 0;

    public synchronized Result accept(long seq, byte[] payload) {
        if (seq < expectedSeq) {
            // Stale/duplicate — already delivered.
            return Result.none();
        }

        if (seq > expectedSeq) {
            if (!pending.containsKey(seq)) {
                if (pending.size() >= MAX_PENDING) {
                    throw new SessionBrokenException("reorder buffer overflow (>" + MAX_PENDING + " pending packets)");
                }
                pending.put(seq, payload);
            }

            long now = System.currentTimeMillis();
            if (now - expectedSince > GAP_TIMEOUT_MILLIS) {
                throw new SessionBrokenException("sequence gap at seq=" + expectedSeq + " not resolved within " + GAP_TIMEOUT_MILLIS + "ms");
            }

            if (now - lastNackSentAt > NACK_DEBOUNCE_MILLIS) {
                lastNackSentAt = now;
                return Result.request(expectedSeq);
            }
            return Result.none();
        }

        // seq == expectedSeq: flush this payload plus any now-contiguous buffered entries.
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length);
        out.write(payload, 0, payload.length);
        expectedSeq++;

        Map.Entry<Long, byte[]> next;
        while ((next = pending.firstEntry()) != null && next.getKey() == expectedSeq) {
            pending.remove(next.getKey());
            out.write(next.getValue(), 0, next.getValue().length);
            expectedSeq++;
        }
        expectedSince = System.currentTimeMillis();
        lastNackSentAt = 0;

        return Result.deliver(out.toByteArray());
    }

    public static final class Result {
        public final byte[] deliverable;
        public final Long requestSeq;

        private Result(byte[] deliverable, Long requestSeq) {
            this.deliverable = deliverable;
            this.requestSeq = requestSeq;
        }

        static Result none() {
            return new Result(null, null);
        }

        static Result deliver(byte[] data) {
            return new Result(data, null);
        }

        static Result request(long seq) {
            return new Result(null, seq);
        }
    }

    public static final class SessionBrokenException extends RuntimeException {
        public SessionBrokenException(String message) {
            super(message);
        }
    }
}
