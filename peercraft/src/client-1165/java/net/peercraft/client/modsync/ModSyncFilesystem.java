package net.peercraft.client.modsync;

// Minecraft 1.16.5 Fabric backport of src/main/.../client/modsync/ModSyncFilesystem.java —
// the `record Installed` is lowered to a static final class with the same accessor names;
// everything else (java.nio.file, DirectoryStream) is Java 8. Keep in sync with the original.

import net.peercraft.network.modsync.FileReassembler;
import net.peercraft.network.modsync.ModEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/**
 * Where mod-sync's downloaded jars land: a dot-prefixed temp dir the loader never scans, then
 * an atomic move into {@code mods/}. Deliberately <b>purely additive</b> — it never moves,
 * deletes or overwrites a jar the player already had.
 */
public final class ModSyncFilesystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    static final String TEMP_DIR_NAME = ".peercraft-modsync-tmp";
    static final String SERVING_DIR_NAME = "serving";

    private ModSyncFilesystem() {
    }

    public static Path tempDir(Path modsDir) {
        return modsDir.resolve(TEMP_DIR_NAME);
    }

    public static Path servingDir(Path modsDir) {
        return tempDir(modsDir).resolve(SERVING_DIR_NAME);
    }

    /** Streaming SHA-512 of a file. */
    public static byte[] hashFile(Path file) throws IOException {
        MessageDigest md = FileReassembler.sha512();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return md.digest();
    }

    public static final class Installed {
        private final String modId;
        private final String fileName;

        public Installed(String modId, String fileName) {
            this.modId = modId;
            this.fileName = fileName;
        }

        public String modId() {
            return modId;
        }

        public String fileName() {
            return fileName;
        }
    }

    /**
     * Moves a hash-verified jar into {@code mods/}. If a file with the same name is somehow
     * already there it's left alone and this is a no-op — mod-sync never clobbers.
     */
    public static Installed install(Path verifiedPart, ModEntry entry, Path modsDir) throws IOException {
        if (!entry.hasSafeFileName()) {
            throw new IOException("unsafe file name: " + entry.fileName());
        }
        Files.createDirectories(modsDir);
        Path target = modsDir.resolve(entry.fileName());
        if (Files.exists(target)) {
            LOGGER.info("[ModSync] {} уже есть в mods/ — пропускаем, ничего не трогаем.", entry.fileName());
            ModSyncFilesystem.deleteQuietly(verifiedPart);
            return new Installed(entry.id(), entry.fileName());
        }
        try {
            Files.move(verifiedPart, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(verifiedPart, target);
        }
        return new Installed(entry.id(), entry.fileName());
    }

    /**
     * Best-effort startup housekeeping: delete leftover {@code *.jar.part} temp files and the
     * {@code serving/} scratch dir. Never touches real {@code .jar} files. Never throws.
     */
    public static void sweepOnStartup(Path modsDir) {
        try {
            Path tmp = tempDir(modsDir);
            if (Files.isDirectory(tmp)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(tmp, "*.part")) {
                    for (Path part : ds) {
                        deleteQuietly(part);
                    }
                }
                deleteRecursiveQuietly(servingDir(modsDir));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("[ModSync] Уборка временных файлов пропущена: {}", e.toString());
        }
    }

    static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private static void deleteRecursiveQuietly(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                deleteQuietly(p);
            }
        } catch (IOException ignored) {
        }
        deleteQuietly(dir);
    }
}
