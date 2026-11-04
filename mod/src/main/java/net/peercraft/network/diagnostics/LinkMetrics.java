package net.peercraft.network.diagnostics;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Bounded, monotonic-time probe window. Loss counts only probes whose deadline expired. */
public final class LinkMetrics {
    private static final long TIMEOUT = 3_000_000_000L;
    private final Map<Long, Long> pending = new HashMap<Long, Long>();
    private final ArrayDeque<Double> samples = new ArrayDeque<Double>();
    private final ArrayDeque<Boolean> outcomes = new ArrayDeque<Boolean>();
    private long sentBytes, receivedBytes, previousSent, previousReceived, sampledAt;
    private double upload, download;
    private int queuedBytes;
    private long retries;
    public synchronized void sent(long nonce, long now) {
        expire(now);
        if (pending.size() < 8) pending.put(nonce, now);
    }
    public synchronized void reply(long nonce, long now) {
        expire(now);
        Long start = pending.remove(nonce);
        if (start == null || now < start) return;
        samples.add((now - start) / 1_000_000.0);
        while (samples.size() > 30) samples.removeFirst();
        outcome(true);
    }
    private void outcome(boolean ok) {
        outcomes.add(ok);
        while (outcomes.size() > 30) outcomes.removeFirst();
    }
    private void expire(long now) {
        java.util.Iterator<Map.Entry<Long, Long>> it = pending.entrySet().iterator();
        while (it.hasNext()) if (now - it.next().getValue() >= TIMEOUT) { it.remove(); outcome(false); }
    }
    public synchronized void traffic(boolean outbound, int bytes) {
        if (outbound) sentBytes += Math.max(0, bytes); else receivedBytes += Math.max(0, bytes);
    }
    public synchronized void queued(int delta) { queuedBytes = Math.max(0, queuedBytes + delta); }
    public synchronized void retry() { retries++; }
    public synchronized Snapshot snapshot(long now) {
        expire(now);
        if (sampledAt == 0) { sampledAt = now; previousSent = sentBytes; previousReceived = receivedBytes; }
        long elapsed = now - sampledAt;
        if (elapsed >= 1_000_000_000L) {
            upload = (sentBytes - previousSent) * 1_000_000_000.0 / elapsed;
            download = (receivedBytes - previousReceived) * 1_000_000_000.0 / elapsed;
            previousSent = sentBytes; previousReceived = receivedBytes; sampledAt = now;
        }
        double sum = 0, jitter = 0, prev = -1;
        for (double value : samples) { sum += value; if (prev >= 0) jitter += Math.abs(value - prev); prev = value; }
        int lost = 0; for (boolean ok : outcomes) if (!ok) lost++;
        return new Snapshot(samples.isEmpty() ? -1 : sum / samples.size(),
                samples.size() < 2 ? -1 : jitter / (samples.size() - 1),
                outcomes.isEmpty() ? -1 : 100.0 * lost / outcomes.size(), outcomes.size(),
                upload, download, queuedBytes, retries);
    }
    public static final class Snapshot {
        public final double rtt, jitter, loss, upload, download;
        public final int samples, queuedBytes;
        public final long retries;
        Snapshot(double rtt, double jitter, double loss, int samples, double upload, double download, int queuedBytes, long retries) {
            this.rtt=rtt; this.jitter=jitter; this.loss=loss; this.samples=samples;
            this.upload=upload; this.download=download; this.queuedBytes=queuedBytes; this.retries=retries;
        }
    }
}
