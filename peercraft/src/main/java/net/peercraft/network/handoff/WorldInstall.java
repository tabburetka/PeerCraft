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
        Files.createDirectory(staging); long total = 0; int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            byte[] bytes = new byte[65536]; ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (++entries > 1_000_000 || e.getName().indexOf('\\') >= 0) throw new IOException("Invalid archive entries");
                Path out = staging.resolve(e.getName()).normalize();
                if (!out.startsWith(staging) || out.equals(staging)) throw new IOException("Archive escapes staging");
                if (e.isDirectory()) Files.createDirectories(out);
                else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream stream = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW)) {
                        int n;
                        while ((n = zip.read(bytes)) != -1) {
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
        Path root = target.toAbsolutePath().getParent();
        validate(root, staging); validate(root, target); validate(root, backup);
        if (!Files.isDirectory(staging) || Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid placement");
        if (Files.exists(journal)) throw new IOException("Unresolved placement journal");
        Properties p = new Properties(); p.setProperty("target", target.getFileName().toString());
        p.setProperty("staging", staging.getFileName().toString()); p.setProperty("backup", backup.getFileName().toString());
        String installation = java.util.UUID.randomUUID().toString();
        p.setProperty("installation", installation);
        Files.write(staging.resolve(INSTALL_MARKER), installation.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        force(staging.resolve(INSTALL_MARKER)); HandoffFiles.forceDirectory(staging);
        p.setProperty("keep", Boolean.toString(keepBackup)); write(journal, p);
        recover(root, journal);
    }
    /** Resume a committed placement; never removes the old copy until new placement exists. */
    public static void recover(Path root, Path journal) throws IOException {
        Properties p = new Properties(); try (InputStream in = Files.newInputStream(journal)) { p.load(in); }
        Path target = resolve(root, p.getProperty("target")), staging = resolve(root, p.getProperty("staging")), backup = resolve(root, p.getProperty("backup"));
        String installation = p.getProperty("installation");
        if (installation == null) throw new IOException("Placement lacks snapshot identity; retain all copies");
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
        if (!Boolean.parseBoolean(p.getProperty("keep"))) delete(backup);
        Files.delete(journal);
        HandoffFiles.forceDirectory(root);
    }
    private static boolean matches(Path directory, String installation) throws IOException {
        Path marker = directory.resolve(INSTALL_MARKER);
        return Files.isRegularFile(marker) && Files.size(marker) <= 64
                && installation.equals(new String(Files.readAllBytes(marker), java.nio.charset.StandardCharsets.UTF_8));
    }
    public static void recoverAll(Path root) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(root, ".peercraft-handoff-staging-*.install")) {
            for (Path journal : paths) recover(root, journal);
        }
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
