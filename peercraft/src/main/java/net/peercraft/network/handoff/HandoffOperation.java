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
        bindDigest(digest);
        Message m = request(VERIFIED); m.digest = digest.clone();
        Message answer;
        try { answer = authority.call(m); } catch (IOException lost) { answer = query(); }
        if ((answer.state != STAGED && answer.state != COMMITTED && answer.state != ROOM_READY)
                || !java.security.MessageDigest.isEqual(answer.digest, digest))
            throw new IOException("Snapshot verification rejected");
        journal.advance(HandoffJournal.Phase.VERIFIED);
    }
    public synchronized Message commit(byte[] digest) throws IOException {
        if (journal.phase != HandoffJournal.Phase.STOPPED) throw new IOException("Source server has not stopped");
        // This record MUST precede sending COMMIT, including when its reply is lost.
        bindDigest(digest);
        journal.advance(HandoffJournal.Phase.COMMIT_SENT);
        Message m = request(COMMIT); m.digest = digest.clone();
        Message result;
        try { result = authority.call(m); } catch (IOException lost) { return resolve(); }
        return record(result);
    }
    public synchronized Message resolve() throws IOException { return record(query()); }
    private Message record(Message result) throws IOException {
        if (result.state == COMMITTED || result.state == ROOM_READY) {
            if (result.epoch < 0 || (journal.phase.ordinal() < HandoffJournal.Phase.COMMITTED.ordinal()
                    && result.epoch != journal.epoch + 1) || !java.security.MessageDigest.isEqual(journal.digest, result.digest))
                throw new IOException("COMMIT does not match the journaled snapshot/epoch");
            journal.epoch = result.epoch;
            if (journal.phase.ordinal() < HandoffJournal.Phase.COMMITTED.ordinal()) journal.advance(HandoffJournal.Phase.COMMITTED);
        } else if (result.state == ABORTED && journal.phase != HandoffJournal.Phase.ABORTED) {
            journal.advance(HandoffJournal.Phase.ABORTED);
        }
        return result;
    }
    private void bindDigest(byte[] digest) throws IOException {
        if (digest == null || digest.length != 64) throw new IOException("Invalid snapshot digest");
        boolean known = false; for (byte b : journal.digest) known |= b != 0;
        if (known && !java.security.MessageDigest.isEqual(journal.digest, digest))
            throw new IOException("Snapshot identity changed within an attempt");
        journal.digest = digest.clone(); journal.save();
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
        Message result = resolve();
        int state = result.state;
        boolean known = false; for (byte b : journal.digest) known |= b != 0;
        if (!known || !result.ownsCurrentEpoch || result.currentEpoch != result.epoch || (state != COMMITTED && state != ROOM_READY)) throw new IOException("Handoff ownership is unconfirmed");
    }
    public synchronized void installed() throws IOException {
        requireCommitted();
        Message answer;
        try { answer = authority.call(request(INSTALLED)); } catch (IOException lost) { answer = query(); }
        if (!answer.installed || !answer.ownsCurrentEpoch || answer.currentEpoch != journal.epoch
                || (answer.state != COMMITTED && answer.state != ROOM_READY))
            throw new IOException("Installed world ownership is unconfirmed");
        journal.advance(HandoffJournal.Phase.INSTALLED);
    }
    public synchronized void sourceRestored(String registeredRoom, byte[] roomProof) throws IOException {
        if (journal.phase != HandoffJournal.Phase.ABORTED || roomProof == null || roomProof.length != 32)
            throw new IOException("Source restoration is not authorized");
        Message m = request(RECOVERED); m.room = registeredRoom;
        System.arraycopy(roomProof, 0, m.digest, 0, roomProof.length);
        Message answer;
        try { answer = authority.call(m); } catch (IOException lost) { answer = query(); }
        if (answer.state != ABORTED || !answer.sourceRestored || !answer.ownsCurrentEpoch
                || answer.currentEpoch != journal.epoch || answer.epoch != journal.epoch || !registeredRoom.equals(answer.room))
            throw new IOException("Restored source registration is unconfirmed");
    }
    public synchronized void ready(String registeredRoom, byte[] roomProof) throws IOException {
        if (journal.phase != HandoffJournal.Phase.INSTALLED) throw new IOException("World has not been installed");
        if (roomProof == null || roomProof.length != 32) throw new IOException("Missing registered room authority");
        Message m = request(READY); m.room = registeredRoom;
        System.arraycopy(roomProof, 0, m.digest, 0, roomProof.length);
        Message answer;
        try { answer = authority.call(m); } catch (IOException lost) { answer = query(); }
        if (answer.state != ROOM_READY || !answer.ownsCurrentEpoch || answer.currentEpoch != answer.epoch
                || answer.epoch != journal.epoch || !registeredRoom.equals(answer.room))
            throw new IOException("Room registration is unconfirmed");
        journal.advance(HandoffJournal.Phase.READY);
    }
}
