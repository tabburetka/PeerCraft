package net.peercraft.network.control;

/** Sliding 64-entry window over received counters: rejects repeats and anything too old. */
final class ReplayWindow {
    private static final int SIZE = 64;
    private long highest = -1;
    private long seen;

    /** Call only after the datagram authenticated; returns false for a replay. */
    synchronized boolean accept(long counter) {
        if (counter < 0) return false;
        if (counter > highest) {
            long shift = counter - highest;
            seen = shift >= SIZE ? 1L : (seen << shift) | 1L;
            highest = counter;
            return true;
        }
        long age = highest - counter;
        if (age >= SIZE) return false;
        long bit = 1L << age;
        if ((seen & bit) != 0) return false;
        seen |= bit;
        return true;
    }
}
