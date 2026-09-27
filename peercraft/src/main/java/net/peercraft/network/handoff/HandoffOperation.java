package net.peercraft.network.handoff;

import java.io.IOException;
import static net.peercraft.network.handoff.HandoffAuthorityProtocol.*;

/** Authority transitions shared by all Minecraft adapters. UNKNOWN never grants a right. */
public final class HandoffOperation {
    public interface Authority { Message call(Message request) throws IOException; }
    private final Authority authority;
    private final HandoffJournal journal;
    public HandoffOperation(Authority authority, HandoffJournal journal) { this.authority = authority; this.journal = journal; }
    private Message request(int type) {
        Message m = new Message(type); m.sessionId = journal.session; m.offerId = journal.offer;
        m.epoch = journal.epoch; m.key = journal.key.clone(); return m;
    }
    public synchronized Message query() throws IOException { return authority.call(request(QUERY)); }
    public synchronized void stopped() throws IOException { journal.advance(HandoffJournal.Phase.STOPPED); }
    public synchronized void verified(byte[] digest) throws IOException {
        Message m = request(VERIFIED); m.digest = digest.clone();
        Message answer = authority.call(m);
        if (answer.state != STAGED && answer.state != COMMITTED && answer.state != ROOM_READY)
            throw new IOException("Snapshot verification rejected");
        journal.advance(HandoffJournal.Phase.VERIFIED);
    }
    public synchronized Message commit(byte[] digest) throws IOException {
        if (journal.phase != HandoffJournal.Phase.STOPPED) throw new IOException("Source server has not stopped");
        // This record MUST precede sending COMMIT, including when its reply is lost.
        journal.advance(HandoffJournal.Phase.COMMIT_SENT);
        Message m = request(COMMIT); m.digest = digest.clone();
        Message result;
        try { result = authority.call(m); } catch (IOException lost) { return resolve(); }
        return record(result);
    }
    public synchronized Message resolve() throws IOException { return record(query()); }
    private Message record(Message result) throws IOException {
        if (result.state == COMMITTED || result.state == ROOM_READY) {
            journal.epoch = result.epoch;
            if (journal.phase.ordinal() < HandoffJournal.Phase.COMMITTED.ordinal()) journal.advance(HandoffJournal.Phase.COMMITTED);
        } else if (result.state == ABORTED && journal.phase != HandoffJournal.Phase.ABORTED) {
            journal.advance(HandoffJournal.Phase.ABORTED);
        }
        return result;
    }
    /** Caller may delete incoming scratch data ONLY when this returns true. */
    public synchronized boolean abort() throws IOException {
        if (journal.phase == HandoffJournal.Phase.COMMIT_SENT) {
            Message resolved = resolve();
            if (resolved.state == ABORTED) return true;
            if (resolved.state != PENDING && resolved.state != STAGED) return false;
        }
        if (journal.phase.ordinal() >= HandoffJournal.Phase.COMMITTED.ordinal() && journal.phase != HandoffJournal.Phase.ABORTED) return false;
        return record(authority.call(request(ABORT))).state == ABORTED;
    }
    public synchronized void requireCommitted() throws IOException {
        int state = resolve().state;
        if (state != COMMITTED && state != ROOM_READY) throw new IOException("Handoff ownership is unconfirmed");
    }
    public synchronized void installed() throws IOException {
        requireCommitted(); journal.advance(HandoffJournal.Phase.INSTALLED);
    }
    public synchronized void ready(String registeredRoom) throws IOException {
        if (journal.phase != HandoffJournal.Phase.INSTALLED) throw new IOException("World has not been installed");
        Message m = request(READY); m.room = registeredRoom;
        if (authority.call(m).state != ROOM_READY) throw new IOException("Room registration is unconfirmed");
        journal.advance(HandoffJournal.Phase.READY);
    }
}
