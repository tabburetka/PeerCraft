package net.peercraft.network.handoff;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Properties;
import java.util.zip.*;

/** Checked staging and recoverable directory replacement, shared with Java-8 adapters. */
public final class WorldInstall {
    private static final String INSTALL_MARKER = ".peercraft-handoff-install";
    private WorldInstall() { }
    public static void unpack(Path archive, Path staging, long maxBytes) throws IOException {
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Staging already exists");
        if (maxBytes <= 0) throw new IOException("Invalid snapshot quota");
        staging = staging.toAbsolutePath().normalize();
        Files.createDirectory(staging); long total = 0; int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            byte[] bytes = new byte[65536]; ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                interrupted();
                if (++entries > 1_000_000 || e.getName().indexOf('\\') >= 0) throw new IOException("Invalid archive entries");
                Path out = staging.resolve(e.getName()).normalize();
                if (!out.startsWith(staging) || out.equals(staging)) throw new IOException("Archive escapes staging");
                if (e.isDirectory()) Files.createDirectories(out);
                else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream stream = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW)) {
                        int n;
                        while ((n = zip.read(bytes)) != -1) {
                            interrupted();
                            total += n; if (total > maxBytes) throw new IOException("Unpacked snapshot exceeds quota");
                            stream.write(bytes, 0, n);
                        }
                    }
                    force(out);
                }
                zip.closeEntry();
            }
            if (!Files.isRegularFile(staging.resolve("level.dat"))) throw new IOException("Snapshot lacks level.dat");
            Files.deleteIfExists(staging.resolve("session.lock"));
            Files.walkFileTree(staging, new SimpleFileVisitor<Path>() {
                public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    HandoffFiles.forceDirectory(dir); return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            try { delete(staging); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }
    /** Call only after durable COMMIT. A rollback copy exists even when keepBackup=false. */
    public static void replace(Path staging, Path target, Path backup, Path journal, boolean keepBackup) throws IOException {
        replace(staging, target, backup, journal, keepBackup, false, () -> { });
    }
    public interface PlacementCheck { void check() throws IOException; }
    public static void replace(Path staging, Path target, Path backup, Path journal, boolean keepBackup,
            boolean modernLock, PlacementCheck check) throws IOException {
        Path root = target.toAbsolutePath().getParent();
        validate(root, staging); validate(root, target); validate(root, backup); validate(root, journal);
        distinct(staging, target, backup, journal);
        if (!Files.isDirectory(staging) || Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid placement");
        if (Files.exists(journal)) throw new IOException("Unresolved placement journal");
        try (WorldPlacementLease lease = WorldPlacementLease.acquire(root, target, modernLock, Files.exists(target))) {
        check.check();
        Properties p = new Properties(); p.setProperty("lock", modernLock ? "filelock" : "fresh-only"); p.setProperty("target", target.getFileName().toString());
        p.setProperty("staging", staging.getFileName().toString()); p.setProperty("backup", backup.getFileName().toString());
        String installation = installationId(staging);
        p.setProperty("installation", installation);
        Files.write(staging.resolve(INSTALL_MARKER), installation.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        force(staging.resolve(INSTALL_MARKER)); HandoffFiles.forceDirectory(staging);
        p.setProperty("keep", Boolean.toString(keepBackup)); write(journal, p);
        recoverLocked(root, journal);
        }
    }
    /** Resume a committed placement; never removes the old copy until new placement exists. */
    public static void recover(Path root, Path journal) throws IOException {
        root = root.toAbsolutePath().normalize(); validate(root, journal);
        if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS) || Files.size(journal) > 16_384) throw new IOException("Invalid placement journal");
        Properties p = new Properties(); try (InputStream in = Files.newInputStream(journal)) { p.load(in); }
        Path target = resolve(root, p.getProperty("target")), staging = resolve(root, p.getProperty("staging"));
        try (WorldPlacementLease lease = WorldPlacementLease.acquire(root, target, "filelock".equals(p.getProperty("lock")), Files.exists(staging) && Files.exists(target))) {
            recoverLocked(root, journal);
        }
    }
    private static void recoverLocked(Path root, Path journal) throws IOException {
        root = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid save root");
        validate(root, journal);
        if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS) || Files.size(journal) > 16_384)
            throw new IOException("Invalid placement journal");
        Properties p = new Properties(); try (InputStream in = Files.newInputStream(journal)) { p.load(in); }
        Path target = resolve(root, p.getProperty("target")), staging = resolve(root, p.getProperty("staging")), backup = resolve(root, p.getProperty("backup"));
        String installation = p.getProperty("installation");
        distinct(target, staging, backup, journal);
        if (installation == null || !installation.matches("[a-zA-Z0-9_-]{1,64}"))
            throw new IOException("Placement lacks snapshot identity; retain all copies");
        if (!"true".equals(p.getProperty("keep")) && !"false".equals(p.getProperty("keep")))
            throw new IOException("Placement lacks backup policy; retain all copies");
        if (Files.exists(staging)) {
            if (!matches(staging, installation)) throw new IOException("Staging identity changed");
            if (Files.exists(target)) {
                if (Files.exists(backup)) throw new IOException("Ambiguous placement; retain all copies");
                Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
                HandoffFiles.forceDirectory(root);
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            HandoffFiles.forceDirectory(root);
        }
        if (!Files.isDirectory(target)) {
            if (Files.exists(backup)) {
                Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE); HandoffFiles.forceDirectory(root);
            }
            throw new IOException("Transferred world unavailable; old copy restored");
        }
        if (!matches(target, installation)) throw new IOException("Target is not the transferred snapshot; retain all copies");
        if (Boolean.parseBoolean(p.getProperty("keep")) && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            Path marker = backup.resolve(".peercraft-backup");
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) != 0)
                    throw new IOException("Invalid backup marker; retain installation journal");
            } else Files.write(marker, new byte[0], StandardOpenOption.CREATE_NEW);
            force(marker); HandoffFiles.forceDirectory(backup);
        } else if (!Boolean.parseBoolean(p.getProperty("keep"))) delete(backup);
        Files.delete(journal);
        HandoffFiles.forceDirectory(root);
    }
    public static String installationId(Path staging) throws IOException {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(staging.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format("%02x", b & 255)); return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
    public static boolean isInstalledSnapshot(Path target, Path staging) throws IOException { return matches(target, installationId(staging)); }
    private static boolean matches(Path directory, String installation) throws IOException {
        Path marker = directory.resolve(INSTALL_MARKER);
        return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && Files.size(marker) <= 64
                && installation.equals(new String(Files.readAllBytes(marker), java.nio.charset.StandardCharsets.UTF_8));
    }
    public static void recoverAll(Path root) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(root, ".peercraft-handoff-staging-*.install")) {
            IOException failures = null; int count = 0;
            for (Path journal : paths) {
                if (++count > 1000) throw new IOException("Too many unresolved placements");
                try { recover(root, journal); }
                catch (IOException failure) {
                    if (failures == null) failures = new IOException("Some placements need manual recovery; all copies retained");
                    failures.addSuppressed(failure);
                }
            }
            if (failures != null) throw failures;
        }
    }
    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Snapshot unpack interrupted");
    }
    private static void distinct(Path... paths) throws IOException {
        java.util.Set<Path> unique = new java.util.HashSet<>();
        for (Path path : paths) if (!unique.add(path.toAbsolutePath().normalize()))
            throw new IOException("Placement paths must be distinct");
    }
    private static Path resolve(Path root, String name) throws IOException {
        if (name == null) throw new IOException("Incomplete placement journal");
        Path path = root.resolve(name); validate(root, path); return path;
    }
    private static void validate(Path root, Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!root.toAbsolutePath().normalize().equals(absolute.getParent()) || Files.isSymbolicLink(path))
            throw new IOException("Placement path is outside save directory or a symlink");
    }
    private static void write(Path path, Properties p) throws IOException {
        Path tmp = Files.createTempFile(path.toAbsolutePath().getParent(), ".placement-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) { p.store(out, "Committed handoff placement"); }
            force(tmp); Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
            HandoffFiles.forceDirectory(path.toAbsolutePath().getParent());
        } finally { Files.deleteIfExists(tmp); }
    }
    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }
    public static void delete(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException { if (e != null) throw e; Files.delete(dir); return FileVisitResult.CONTINUE; }
        });
    }
}
