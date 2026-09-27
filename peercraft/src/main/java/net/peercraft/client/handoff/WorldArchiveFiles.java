package net.peercraft.client.handoff;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Complete world-local snapshot, including plugin data, datapacks and server configs. */
public final class WorldArchiveFiles {
    private WorldArchiveFiles() { }

    private static boolean scratch(Path root, Path path) {
        String rel = root.relativize(path).toString().replace('\\', '/');
        String first = rel.split("/", 2)[0];
        return rel.equals("session.lock") || first.equals(".peercraft-handoff-install") || first.equals(".peercraft-player-migration")
                || first.equals(".peercraft-handoff-tmp") || first.startsWith(".peercraft-handoff-staging-");
    }

    public static void write(final Path root, final ZipOutputStream zip) throws IOException {
        final byte[] buffer = new byte[65536];
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (scratch(root, dir)) return FileVisitResult.SKIP_SUBTREE;
                if (!dir.equals(root)) {
                    zip.putNextEntry(new ZipEntry(root.relativize(dir).toString().replace('\\', '/') + "/"));
                    zip.closeEntry();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (scratch(root, file)) return FileVisitResult.CONTINUE;
                // Never dereference links outside the save or silently drop linked plugin data.
                if (!attrs.isRegularFile()) throw new IOException("Unsupported world file: " + file);
                zip.putNextEntry(new ZipEntry(root.relativize(file).toString().replace('\\', '/')));
                try (InputStream in = Files.newInputStream(file)) {
                    int n;
                    while ((n = in.read(buffer)) != -1) zip.write(buffer, 0, n);
                }
                zip.closeEntry();
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static long estimateSize(final Path root) throws IOException {
        final long[] total = {0L};
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return scratch(root, dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!scratch(root, file)) {
                    if (!attrs.isRegularFile()) throw new IOException("Unsupported world file: " + file);
                    total[0] += attrs.size();
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }
}
