package net.peercraft.client.handoff;

import net.peercraft.network.handoff.HandoffOperation;
import net.peercraft.network.handoff.HostExecutionManifest;
import net.peercraft.network.handoff.WorldInstall;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Preflight target/backup selection, reused by modern and Java-8 adapters. No Minecraft calls. */
public final class WorldTargetPlan {
    public static final long UNPACK_LIMIT = 32L * 1024 * 1024 * 1024;
    public final Path root, target, backup;
    public final boolean keepBackup;
    private final boolean existed;
    private final Object fileKey;
    private final byte[] levelDigest;
    private final String worldId;
    private WorldTargetPlan(Path root, Path target, Path backup, boolean keep, String worldId) throws IOException {
        this.root = root.toRealPath(); this.target = target.toAbsolutePath().normalize();
        this.backup = backup.toAbsolutePath().normalize(); this.keepBackup = keep; this.worldId = worldId;
        if (!this.root.equals(this.target.getParent()) || !this.root.equals(this.backup.getParent()) || target.equals(backup))
            throw new IOException("Target must be a direct save directory");
        existed = Files.exists(this.target, LinkOption.NOFOLLOW_LINKS);
        if (existed) {
            BasicFileAttributes attrs = Files.readAttributes(this.target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || attrs.isSymbolicLink() || !Files.isRegularFile(this.target.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Target is not an independent saved world");
            PeercraftWorldMeta meta = PeercraftWorldMeta.loadOrNull(this.target);
            if (meta == null || !worldId.equals(meta.worldId())) throw new IOException("Return target belongs to another world");
            fileKey = attrs.fileKey(); levelDigest = HostExecutionManifest.hash(this.target.resolve("level.dat"));
        } else { fileKey = null; levelDigest = null; }
        if (Files.exists(this.backup, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Backup destination already exists");
    }
    public static List<Path> candidates(Path root, String worldId) throws IOException {
        Files.createDirectories(root); root = root.toRealPath(); List<Path> matches = new ArrayList<>();
        try (DirectoryStream<Path> saves = Files.newDirectoryStream(root)) {
            for (Path path : saves) {
                String name = path.getFileName().toString();
                if (name.startsWith(".peercraft-handoff-") || name.contains(" (до возврата ")
                        || Files.exists(path.resolve(".peercraft-backup")) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                PeercraftWorldMeta meta = PeercraftWorldMeta.loadOrNull(path);
                if (meta != null && worldId.equals(meta.worldId()) && Files.isRegularFile(path.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS)) matches.add(path);
            }
        }
        matches.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return Collections.unmodifiableList(matches);
    }
    public static WorldTargetPlan selected(Path root, Path target, boolean keepBackup, String worldId) throws IOException {
        Path logicalRoot = root.toAbsolutePath().normalize();
        Path actualRoot = root.toRealPath();
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (!logicalRoot.equals(parent) && !actualRoot.equals(parent)) throw new IOException("Target is outside saves");
        target = actualRoot.resolve(target.getFileName()); root = actualRoot;
        return new WorldTargetPlan(root, target, root.resolve(backupName(target, keepBackup)), keepBackup, worldId);
    }
    public static String backupName(Path target, boolean keep) {
        if (!keep) return ".peercraft-handoff-backup-" + UUID.randomUUID();
        String base = target.getFileName().toString();
        while (base.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 150)
            base = base.substring(0, base.offsetByCodePoints(base.length(), -1));
        return base + " (до возврата " + new java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(new Date())
                + "-" + UUID.randomUUID().toString().substring(0, 8) + ")";
    }
    public static WorldTargetPlan selected(Path root, Path target, Path backup, boolean keep, String worldId) throws IOException {
        Path logical = root.toAbsolutePath().normalize(), actual = root.toRealPath();
        Path parent = target.toAbsolutePath().normalize().getParent(), backupParent = backup.toAbsolutePath().normalize().getParent();
        if ((!logical.equals(parent) && !actual.equals(parent)) || (!logical.equals(backupParent) && !actual.equals(backupParent)))
            throw new IOException("Placement leaves saves");
        return new WorldTargetPlan(actual, actual.resolve(target.getFileName()), actual.resolve(backup.getFileName()), keep, worldId);
    }
    public static WorldTargetPlan fresh(Path root, String label, String worldId) throws IOException {
        Files.createDirectories(root); root = root.toRealPath();
        String base = label == null ? "world" : label.replaceAll("[^\\p{L}\\p{N} _-]", "_").trim();
        if (base.isEmpty()) base = "world";
        if (base.length() > 48) base = base.substring(0, 48);
        Path target = root.resolve(base + "-peercraft"); int suffix = 2;
        while (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) target = root.resolve(base + "-peercraft-" + suffix++);
        return selected(root, target, false, worldId);
    }
    public void requireSpace(long estimatedWorldBytes) throws IOException {
        if (estimatedWorldBytes < 0 || estimatedWorldBytes > UNPACK_LIMIT) throw new IOException("World exceeds snapshot quota");
        long required = estimatedWorldBytes * 2 + 64L * 1024 * 1024;
        if (Files.getFileStore(root).getUsableSpace() < required) throw new IOException("Insufficient space for snapshot and staging");
    }
    public void revalidate() throws IOException {
        if (!root.equals(root.toRealPath()) || Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Placement paths changed");
        if (!existed) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Fresh destination is now occupied");
            return;
        }
        BasicFileAttributes attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        PeercraftWorldMeta meta = PeercraftWorldMeta.loadOrNull(target);
        if (!attrs.isDirectory() || attrs.isSymbolicLink() || (fileKey != null && !fileKey.equals(attrs.fileKey()))
                || meta == null || !worldId.equals(meta.worldId())
                || !java.security.MessageDigest.isEqual(levelDigest, HostExecutionManifest.hash(target.resolve("level.dat"))))
            throw new IOException("Selected return world changed since preflight");
    }
    public void install(Path staging, HandoffOperation operation) throws IOException {
        revalidate(); operation.requireCommitted();
        WorldInstall.replace(staging, target, backup,
                root.resolve(staging.getFileName().toString() + ".install"), keepBackup);
        if (keepBackup) Files.write(backup.resolve(".peercraft-backup"), new byte[0], StandardOpenOption.CREATE_NEW);
    }
}
