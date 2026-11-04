package net.peercraft.network.handoff;

/** All adapters use the same monotonic phases; incoming packets cannot move them implicitly. */
public final class HandoffPhases {
    public enum Phase { CAPABILITIES, OFFER, PREFLIGHT, PREPARE, SAVE_AND_STOP, TRANSFER, VERIFIED_STAGING,
        COMMIT_SENT, COMMITTED, STARTING, ROOM_REGISTERED, READY, ABORTED, UNKNOWN }
    private Phase phase = Phase.CAPABILITIES;
    public synchronized Phase phase() { return phase; }
    public synchronized void advance(Phase next) {
        if (phase == Phase.ABORTED || phase == Phase.READY) throw new IllegalStateException("Attempt has ended");
        if (next == Phase.ABORTED) {
            if (phase.ordinal() >= Phase.COMMIT_SENT.ordinal()) throw new IllegalStateException("Resolve COMMIT before abort");
        } else if (next == Phase.UNKNOWN) {
            if (phase != Phase.COMMIT_SENT) throw new IllegalStateException("No unresolved COMMIT");
        } else if (phase == Phase.UNKNOWN) {
            if (next != Phase.COMMITTED && next != Phase.ABORTED) throw new IllegalStateException("Unknown ownership");
        } else if (next.ordinal() != phase.ordinal() + 1) throw new IllegalStateException("Invalid handoff transition");
        phase = next;
    }
    /** A confirmed rendezvous ABORT is the only exception to COMMIT_SENT's local abort prohibition. */
    public synchronized void confirmedAbort() {
        if (phase == Phase.COMMITTED || phase == Phase.STARTING || phase == Phase.ROOM_REGISTERED || phase == Phase.READY)
            throw new IllegalStateException("Committed attempt cannot be cancelled");
        phase = Phase.ABORTED;
    }
    public synchronized boolean permits(HandoffParticipant.Role sender, int type) {
        if (phase == Phase.ABORTED || phase == Phase.READY || phase == Phase.UNKNOWN) return false;
        if (type == HandoffControlProtocol.HEARTBEAT) return true;
        if (sender == HandoffParticipant.Role.OBSERVER)
            return type == HandoffControlProtocol.PREPARED && phase == Phase.PREPARE;
        if (sender == HandoffParticipant.Role.SUCCESSOR) {
            switch (type) {
                case HandoffControlProtocol.ACCEPT:
                case HandoffControlProtocol.DECLINE: return phase == Phase.OFFER;
                case HandoffControlProtocol.MANIFEST:
                case HandoffControlProtocol.MANIFEST_ACK: return phase == Phase.PREFLIGHT;
                case HandoffControlProtocol.PREFLIGHT: return phase == Phase.PREFLIGHT;
                case HandoffControlProtocol.PREPARED: return phase == Phase.PREPARE;
                case HandoffControlProtocol.VERIFIED: return phase == Phase.TRANSFER || phase == Phase.VERIFIED_STAGING;
                case HandoffControlProtocol.INSTALLED: return phase == Phase.COMMITTED || phase == Phase.STARTING;
                case HandoffControlProtocol.READY: return phase == Phase.STARTING || phase == Phase.ROOM_REGISTERED;
                default: return false;
            }
        }
        switch (type) {
            case HandoffControlProtocol.OFFER: return phase == Phase.OFFER;
            case HandoffControlProtocol.MANIFEST:
            case HandoffControlProtocol.MANIFEST_ACK: return phase == Phase.PREFLIGHT;
            case HandoffControlProtocol.PREFLIGHT: return phase == Phase.OFFER || phase == Phase.PREFLIGHT;
            case HandoffControlProtocol.PREPARE: return phase == Phase.PREFLIGHT || phase == Phase.PREPARE;
            case HandoffControlProtocol.START: return phase == Phase.COMMITTED;
            // No packet alone proves a durable abort. Handler must resolve it before cleanup.
            case HandoffControlProtocol.ABORT: return phase.ordinal() < Phase.COMMITTED.ordinal();
            default: return false;
        }
    }
}
