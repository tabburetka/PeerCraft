package net.peercraft.client.handoff;

import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipOutputStream;

/**
 * Forge 1.12.2 backport of {@code src/main/.../client/handoff/WorldArchiver.java} (cf. the
 * 1.16.5 twin). Deltas, confirmed against RFG's recompiled 1.12.2 sources:
 * <ul>
 *   <li>No {@code MinecraftServerAccessor}/{@code storageSource} needed — 1.12.2's
 *       {@code MinecraftServer.getActiveAnvilConverter().getFile(folderName, "")} (both public)
 *       already gives the world directory as a {@code File}.</li>
 *   <li>{@code saveEverything(boolean,boolean,boolean)} -&gt; {@code saveAllWorlds(boolean)}
 *       (single flag, "don't log" — matches vanilla's own flush-all-worlds call), run via
 *       {@code MinecraftServer.addScheduledTask(Runnable).get()} (there is no
 *       {@code executeBlocking} on 1.12.2's {@code IThreadListener}).</li>
 *   <li>Logs through Log4j2 directly (no SLF4J on the 1.12.2 loader classpath, same as the
 *       already-ported {@code OpenToLanMixin}).</li>
 * </ul>
 */
public final class WorldArchiver {

    private static final Logger LOGGER = LogManager.getLogger("peercraft");

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

    public static Result archive(MinecraftServer server, Path tmpDir) throws IOException {
        try {
            server.addScheduledTask(() -> server.saveAllWorlds(false)).get();
        } catch (Exception e) {
            throw new IOException("Could not flush world before handoff", e);
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
            WorldArchiveFiles.write(worldDir, zos);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(zip);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }

        long size = Files.size(zip);
        byte[] sha = md.digest();
        LOGGER.info("[Handoff] Архив мира готов: {} ({} байт)", zip.getFileName(), size);
        return new Result(zip, size, sha);
    }


    public static Path worldDir(MinecraftServer server) {
        return server.getActiveAnvilConverter().getFile(server.getFolderName(), "").toPath();
    }

    public static long estimateSize(Path worldDir) {
        try {
            return WorldArchiveFiles.estimateSize(worldDir);
        } catch (IOException e) {
            LOGGER.debug("[Handoff] Не удалось оценить размер мира: {}", e.toString());
            return 0L;
        }
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

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
