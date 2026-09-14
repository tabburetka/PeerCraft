package net.peercraft.client.handoff;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.peercraft.mixin.MinecraftServerAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Flushes the running world and zips its save directory into a single archive for the handoff
 * successor. Runs on the host; the successor unzips it and loads it (see {@code SuccessorLauncher}).
 *
 * <p>Excludes {@code session.lock} (the successor's own load takes its own lock) and any
 * PeerCraft scratch files. Uses light zip compression — region files and NBT are already
 * compressed, so store-ish is faster and barely larger.
 */
public final class WorldArchiver {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    public static final class Result {
        private final Path zip;
        private final long size;
        private final byte[] sha512;

        Result(Path zip, long size, byte[] sha512) {
            this.zip = zip;
            this.size = size;
            this.sha512 = sha512;
        }

        public Path zip() {
            return zip;
        }

        public long size() {
            return size;
        }

        public byte[] sha512() {
            return sha512;
        }
    }

    private WorldArchiver() {
    }

    /**
     * @param server the running integrated server
     * @param tmpDir a directory to write the archive into (created if missing)
     * @return the finished archive, its byte length and its SHA-512
     */
    public static Result archive(MinecraftServer server, Path tmpDir) throws IOException {
        // Flush on the server thread so the save is consistent on disk before we read it.
        try {
            server.executeBlocking(() -> server.saveEverything(true, true, true));
        } catch (RuntimeException e) {
            LOGGER.warn("[Handoff] saveEverything перед архивацией не удался: {}", e.toString());
        }

        Path worldDir = worldDir(server);
        Files.createDirectories(tmpDir);
        Path zip = tmpDir.resolve("peercraft-handoff-" + System.currentTimeMillis() + ".zip");

        MessageDigest md = sha512();
        long[] total = {0L};
        try (OutputStream fileOut = Files.newOutputStream(zip);
             DigestOutputStream digestOut = new DigestOutputStream(fileOut, md, total);
             ZipOutputStream zos = new ZipOutputStream(digestOut)) {
            zos.setLevel(1);
            List<Path> files = new ArrayList<Path>();
            try (Stream<Path> walk = Files.walk(worldDir)) {
                walk.filter(Files::isRegularFile).filter(p -> keep(worldDir, p)).forEach(files::add);
            }
            byte[] buf = new byte[1 << 16];
            for (Path p : files) {
                String rel = worldDir.relativize(p).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(rel));
                try (var in = Files.newInputStream(p)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        zos.write(buf, 0, n);
                    }
                } catch (IOException perFile) {
                    // A file that vanished or is briefly locked mid-walk — skip it rather than
                    // failing the whole archive; the world stays playable, this is a snapshot.
                    LOGGER.debug("[Handoff] Пропущен файл {} при архивации: {}", rel, perFile.toString());
                }
                zos.closeEntry();
            }
        }

        long size = Files.size(zip);
        byte[] sha = md.digest();
        LOGGER.info("[Handoff] Архив мира готов: {} ({} байт)", zip.getFileName(), size);
        return new Result(zip, size, sha);
    }

    private static boolean keep(Path worldDir, Path file) {
        String name = file.getFileName().toString();
        if (name.equals("session.lock")) {
            return false;
        }
        String rel = worldDir.relativize(file).toString().replace('\\', '/');
        // Skip PeerCraft's own scratch trees if they ever live under the world dir.
        return !rel.contains("/.peercraft") && !rel.startsWith(".peercraft");
    }

    /** The on-disk directory of the world {@code server} is running. */
    public static Path worldDir(MinecraftServer server) {
        return ((MinecraftServerAccessor) (Object) server).peercraft$storageSource().getLevelPath(LevelResource.ROOT);
    }

    /**
     * Rough size estimate for the offer screen — the raw on-disk size of the same files
     * {@link #archive} would zip (region files/NBT are already compressed, so light zip
     * compression barely shrinks this; good enough for a "примерно" figure). Called before the
     * successor has accepted, so nothing has been flushed or zipped yet.
     */
    public static long estimateSize(Path worldDir) {
        long[] total = {0L};
        try (Stream<Path> walk = Files.walk(worldDir)) {
            walk.filter(Files::isRegularFile).filter(p -> keep(worldDir, p)).forEach(p -> {
                try {
                    total[0] += Files.size(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            LOGGER.debug("[Handoff] Не удалось оценить размер мира: {}", e.toString());
        }
        return total[0];
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

    /** Wraps an OutputStream, updating a digest and a running byte count as bytes pass through. */
    private static final class DigestOutputStream extends OutputStream {
        private final OutputStream out;
        private final MessageDigest md;
        private final long[] total;

        DigestOutputStream(OutputStream out, MessageDigest md, long[] total) {
            this.out = out;
            this.md = md;
            this.total = total;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            md.update((byte) b);
            total[0]++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            md.update(b, off, len);
            total[0] += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }
}
