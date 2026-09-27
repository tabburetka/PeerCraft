package net.peercraft.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Stages every edit before commit; retained originals permit rollback after interrupted commit. */
final class MigrationTransaction {
    private final Path world, directory;
    private final Properties journal = new Properties();
    private int count;

    MigrationTransaction(Path world) throws IOException {
        this.world = world;
        Path base = world.resolve(".peercraft-player-migration");
        Files.createDirectories(base);
        directory = Files.createTempDirectory(base, "backup-");
    }

    Path temporary() throws IOException { return Files.createTempFile(directory, "stage-", ".tmp"); }

    void stage(Path target, byte[] bytes) throws IOException {
        Path staged = temporary(); Files.write(staged, bytes); stage(target, staged);
    }

    void stage(Path target, Path staged) throws IOException {
        String key = Integer.toString(count++);
        Path relative = world.relativize(target);
        if (relative.isAbsolute() || relative.startsWith("..")) throw new IOException("Migration path escapes world");
        for (int i = 0; i < count - 1; i++) {
            if (relative.toString().equals(journal.getProperty(Integer.toString(i))))
                throw new IOException("Migration attempted to stage the same target twice: " + relative);
        }
        boolean exists = Files.exists(target);
        if (exists) Files.copy(target, directory.resolve(key + ".original"));
        Files.move(staged, directory.resolve(key + ".new"));
        journal.setProperty(key, relative.toString());
        journal.setProperty(key + ".existed", Boolean.toString(exists));
    }

    void commit() throws IOException {
        journal.setProperty("count", Integer.toString(count));
        try (OutputStream out = Files.newOutputStream(directory.resolve("journal.properties"))) {
            journal.store(out, "PeerCraft player migration backups; keep for recovery");
        }
        Files.createFile(directory.resolve("pending"));
        try {
            for (int i = 0; i < count; i++) {
                Path target = world.resolve(journal.getProperty(Integer.toString(i)));
                Files.createDirectories(target.getParent());
                replace(directory.resolve(i + ".new"), target);
            }
            Files.createFile(directory.resolve("committed"));
        } catch (IOException failure) {
            try { rollback(world, directory); } catch (IOException recovery) { failure.addSuppressed(recovery); }
            throw failure;
        }
    }

    static void recover(Path world) throws IOException {
        Path base = world.resolve(".peercraft-player-migration");
        if (!Files.isDirectory(base)) return;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(base, "backup-*")) {
            for (Path dir : dirs) {
                if (Files.exists(dir.resolve("pending")) && !Files.exists(dir.resolve("committed"))) rollback(world, dir);
            }
        }
    }

    private static void rollback(Path world, Path dir) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve("journal.properties"))) { p.load(in); }
        int count = Integer.parseInt(p.getProperty("count"));
        for (int i = 0; i < count; i++) {
            Path target = world.resolve(p.getProperty(Integer.toString(i))).normalize();
            if (!target.startsWith(world.normalize())) throw new IOException("Invalid recovery path");
            if (Boolean.parseBoolean(p.getProperty(i + ".existed"))) {
                Path temp = Files.createTempFile(dir, "restore-", ".tmp");
                Files.copy(dir.resolve(i + ".original"), temp, StandardCopyOption.REPLACE_EXISTING);
                replace(temp, target);
            } else Files.deleteIfExists(target);
        }
        Files.delete(dir.resolve("pending"));
    }

    private static void replace(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
