package net.peercraft.network.handoff;

import java.util.concurrent.atomic.AtomicBoolean;

/** Shared terminal gate for queued native launch actions and their completion callbacks. */
public final class HandoffLaunchGuard {
    private final AtomicBoolean terminal = new AtomicBoolean();

    public boolean active() { return !terminal.get(); }

    /** Only the first cancellation, failure or successful registration wins. */
    public boolean finish() { return terminal.compareAndSet(false, true); }
}
