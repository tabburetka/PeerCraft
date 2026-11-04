package net.peercraft.network.handoff;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.nio.charset.StandardCharsets;

/** Parent lease coordinates PeerCraft; session.lock coordinates modern vanilla Minecraft. */
final class WorldPlacementLease implements AutoCloseable {
    private FileChannel parent, session;
    private FileLock parentLock, sessionLock;
    static WorldPlacementLease acquire(Path root, Path target, boolean modern, boolean changesExisting) throws IOException {
        WorldPlacementLease lease = new WorldPlacementLease();
        try {
            String id;
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(target.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
                StringBuilder b = new StringBuilder(); for (byte v : hash) b.append(String.format("%02x", v & 255)); id = b.toString();
            } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
            Path parentPath = root.resolve(".peercraft-placement-lock-" + id);
            if (Files.isSymbolicLink(parentPath)) throw new IOException("Placement lease is a symlink");
            lease.parent = FileChannel.open(parentPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lease.parentLock = lease.parent.tryLock();
            if (lease.parentLock == null) throw new IOException("Another placement owns this destination");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (changesExisting && !modern) throw new IOException("Legacy world exclusivity cannot be proved; choose a fresh folder");
                if (changesExisting && System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"))
                    throw new IOException("Locked directory replacement is unavailable on Windows; choose a fresh folder");
                if (modern) {
                    Path lock = target.resolve("session.lock");
                    if (Files.isSymbolicLink(lock)) throw new IOException("World lock is a symlink");
                    lease.session = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    lease.sessionLock = lease.session.tryLock();
                    if (lease.sessionLock == null) throw new IOException("Minecraft is using the destination world");
                }
            }
            return lease;
        } catch (IOException | RuntimeException failure) {
            try { lease.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw new IOException("Cannot obtain exclusive placement", failure);
        }
    }
    public void close() throws IOException {
        IOException failure = null;
        for (FileChannel channel : new FileChannel[] {session, parent}) {
            if (channel != null) try { channel.close(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (failure != null) throw failure;
        // Lease files must never be unlinked: another process could hold the old inode.
    }
}
