package net.peercraft.network.handoff;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;

/** Authenticated, phase-checked control channel independent of game TCP and bridge host/client role. */
public final class HandoffControlChannel {
    public interface Handler { void receive(HandoffParticipant participant, HandoffControlProtocol.Message message); }
    private final UUID session;
    private final long offer;
    private volatile long epoch;
    private final HandoffPhases phases;
    private final List<HandoffParticipant> participants;
    private final Handler handler;
    public HandoffControlChannel(UUID session, long offer, long epoch, HandoffPhases phases,
                                 Collection<HandoffParticipant> participants, Handler handler) {
        if (session == null || epoch < 0 || phases == null || participants.isEmpty() || participants.size() > 1024 || handler == null)
            throw new IllegalArgumentException("Invalid control channel");
        this.session = session; this.offer = offer; this.epoch = epoch; this.phases = phases;
        this.participants = Collections.unmodifiableList(new ArrayList<>(participants)); this.handler = handler;
        Set<String> keys = new HashSet<>();
        for (HandoffParticipant participant : this.participants) {
            String key = Base64.getEncoder().encodeToString(participant.message(HandoffControlProtocol.HEARTBEAT, session, offer, epoch, new byte[0]).authority);
            if (!keys.add(key)) throw new IllegalArgumentException("Participant authorities must be distinct");
        }
    }
    /** Call only with the epoch from a confirmed rendezvous COMMIT. */
    public synchronized void committed(long confirmedEpoch) {
        if (confirmedEpoch != epoch + 1) throw new IllegalStateException("Unexpected committed epoch");
        if (phases.phase() != HandoffPhases.Phase.COMMIT_SENT && phases.phase() != HandoffPhases.Phase.UNKNOWN
                && phases.phase() != HandoffPhases.Phase.COMMITTED)
            throw new IllegalStateException("COMMIT was not requested");
        epoch = confirmedEpoch;
        if (phases.phase() != HandoffPhases.Phase.COMMITTED) phases.advance(HandoffPhases.Phase.COMMITTED);
    }
    public boolean onPacket(byte[] bytes, int length, InetSocketAddress sender) {
        final HandoffControlProtocol.Message message;
        try { message = HandoffControlProtocol.decode(bytes, length); } catch (IOException invalid) { return false; }
        if (!session.equals(message.session) || offer != message.offer || epoch != message.epoch) return false;
        HandoffPhases.Phase phase = phases.phase();
        for (HandoffParticipant participant : participants) {
            if (!phases.permits(participant.role, message.type)) continue;
            boolean canMove = participant.role == HandoffParticipant.Role.SUCCESSOR
                    && (phase == HandoffPhases.Phase.STARTING || phase == HandoffPhases.Phase.ROOM_REGISTERED);
            if (participant.authenticates(message.authority, sender, canMove)) {
                handler.receive(participant, message); return true;
            }
        }
        return false;
    }
}
