package net.peercraft.network.handoff;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;

/** Local recovery record. Write COMMIT_SENT before network I/O; UNKNOWN retains all data. */
public final class HandoffJournal {
    public enum Phase { ACCEPTED, STOPPED, VERIFIED, COMMIT_SENT, COMMITTED, INSTALLED, READY, ABORTED }
    public final UUID session;
    public final long offer;
    public long epoch;
    public final byte[] key;
    public byte[] digest = new byte[64];
    public Phase phase;
    public String source = "", archive = "", staging = "", target = "";
    public String role = "", authorityHost = "", backup = "";
    public int authorityPort;
    public boolean keepBackup;
    private final Path file;
    public interface WriteHook { void at(boolean afterMove) throws IOException; }
    private final WriteHook hook;

    public HandoffJournal(Path file, UUID session, long offer, long epoch, byte[] key) {
        this(file, session, offer, epoch, key, afterMove -> { });
    }
    public HandoffJournal(Path file, UUID session, long offer, long epoch, byte[] key, WriteHook hook) {
        if (file == null || session == null || epoch < 0 || key == null || key.length != 32)
            throw new IllegalArgumentException("Invalid handoff journal identity");
        this.hook = hook; this.file = file; this.session = session; this.offer = offer; this.epoch = epoch;
        this.key = key.clone(); this.phase = Phase.ACCEPTED;
    }
    public Path path() { return file; }
    public synchronized void advance(Phase next) throws IOException {
        if (phase == Phase.ABORTED || (phase.ordinal() >= Phase.COMMITTED.ordinal() && next == Phase.ABORTED))
            throw new IOException("Cannot roll back a committed handoff");
        if (next != Phase.ABORTED && next.ordinal() < phase.ordinal()) throw new IOException("Late handoff transition");
        Phase previous = phase; phase = next;
        try { save(); } catch (IOException e) { phase = previous; throw e; }
    }
    public synchronized void committed(long nextEpoch) throws IOException {
        long previous = epoch;
        epoch = nextEpoch;
        try { advance(Phase.COMMITTED); } catch (IOException failure) { epoch = previous; throw failure; }
    }
    public synchronized void save() throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x50434834); out.writeLong(session.getMostSignificantBits()); out.writeLong(session.getLeastSignificantBits());
            out.writeLong(offer); out.writeLong(epoch); out.write(key); out.writeUTF(phase.name());
            out.writeUTF(source); out.writeUTF(archive); out.writeUTF(staging); out.writeUTF(target);
            if (digest == null || digest.length != 64) throw new IOException("Invalid snapshot digest");
            out.write(digest);
            out.writeUTF(role); out.writeUTF(authorityHost); out.writeInt(authorityPort); out.writeUTF(backup); out.writeBoolean(keepBackup);
        }
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), ".handoff-journal-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ByteBuffer b = ByteBuffer.wrap(bytes.toByteArray()); while (b.hasRemaining()) channel.write(b); channel.force(true);
            }
            hook.at(false);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            hook.at(true);
            HandoffFiles.forceDirectory(file.toAbsolutePath().getParent());
        } finally { Files.deleteIfExists(tmp); }
    }
    public static HandoffJournal read(Path file) throws IOException {
        HandoffFiles.forceDirectory(file.toAbsolutePath().getParent());
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            int format = in.readInt();
            if (format != 0x50434832 && format != 0x50434833 && format != 0x50434834) throw new IOException("Unknown handoff journal");
            UUID session = new UUID(in.readLong(), in.readLong()); long offer = in.readLong(), epoch = in.readLong();
            byte[] key = new byte[32]; in.readFully(key);
            HandoffJournal j = new HandoffJournal(file, session, offer, epoch, key);
            j.phase = Phase.valueOf(in.readUTF()); j.source = in.readUTF(); j.archive = in.readUTF();
            j.staging = in.readUTF(); j.target = in.readUTF();
            if (format >= 0x50434833) in.readFully(j.digest);
            if (format == 0x50434834) {
                j.role = in.readUTF(); j.authorityHost = in.readUTF(); j.authorityPort = in.readInt(); j.backup = in.readUTF(); j.keepBackup = in.readBoolean();
            }
            if (in.read() != -1) throw new IOException("Trailing handoff journal data"); return j;
        } catch (RuntimeException e) { throw new IOException("Corrupt handoff journal", e); }
    }
}
