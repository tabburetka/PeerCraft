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
    public Phase phase;
    public String source = "", archive = "", staging = "", target = "";
    private final Path file;
    public HandoffJournal(Path file, UUID session, long offer, long epoch, byte[] key) {
        if (file == null || session == null || epoch < 0 || key == null || key.length != 32)
            throw new IllegalArgumentException("Invalid handoff journal identity");
        this.file = file; this.session = session; this.offer = offer; this.epoch = epoch;
        this.key = key.clone(); this.phase = Phase.ACCEPTED;
    }
    public synchronized void advance(Phase next) throws IOException {
        if (phase == Phase.ABORTED || (phase.ordinal() >= Phase.COMMITTED.ordinal() && next == Phase.ABORTED))
            throw new IOException("Cannot roll back a committed handoff");
        if (next != Phase.ABORTED && next.ordinal() < phase.ordinal()) throw new IOException("Late handoff transition");
        Phase previous = phase; phase = next;
        try { save(); } catch (IOException e) { phase = previous; throw e; }
    }
    public synchronized void save() throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x50434832); out.writeLong(session.getMostSignificantBits()); out.writeLong(session.getLeastSignificantBits());
            out.writeLong(offer); out.writeLong(epoch); out.write(key); out.writeUTF(phase.name());
            out.writeUTF(source); out.writeUTF(archive); out.writeUTF(staging); out.writeUTF(target);
        }
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), ".handoff-journal-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ByteBuffer b = ByteBuffer.wrap(bytes.toByteArray()); while (b.hasRemaining()) channel.write(b); channel.force(true);
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            HandoffFiles.forceDirectory(file.toAbsolutePath().getParent());
        } finally { Files.deleteIfExists(tmp); }
    }
    public static HandoffJournal read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != 0x50434832) throw new IOException("Unknown handoff journal");
            UUID session = new UUID(in.readLong(), in.readLong()); long offer = in.readLong(), epoch = in.readLong();
            byte[] key = new byte[32]; in.readFully(key);
            HandoffJournal j = new HandoffJournal(file, session, offer, epoch, key);
            j.phase = Phase.valueOf(in.readUTF()); j.source = in.readUTF(); j.archive = in.readUTF();
            j.staging = in.readUTF(); j.target = in.readUTF();
            if (in.read() != -1) throw new IOException("Trailing handoff journal data"); return j;
        } catch (RuntimeException e) { throw new IOException("Corrupt handoff journal", e); }
    }
}
