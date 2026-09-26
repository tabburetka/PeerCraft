package net.peercraft.client.handoff;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.peercraft.client.mixin.MinecraftServerInvoker;
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
 * Forge 1.7.10 backport of {@code src/main/.../client/handoff/WorldArchiver.java} (cf. the
 * 1.12.2 twin, almost mechanical from there). Deltas from 1.12.2:
 * <ul>
 *   <li>{@code saveAllWorlds(boolean)} is {@code protected} on 1.7.10 (public on 1.12.2) —
 *       invoked through {@link MinecraftServerInvoker}, still via
 *       {@code addScheduledTask(Runnable).get()} (obfuscated to {@code func_152344_a} — no
 *       readable stable_12 mapping, same as the already-ported {@code OpenToLanMixin}).</li>
 *   <li>{@code getActiveAnvilConverter()}/{@code getFolderName()} are unchanged (both public).</li>
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
        // 1.7.10's MinecraftServer has no IThreadListener/addScheduledTask at all (added ~1.8) —
        // this predates any cross-thread scheduling primitive, so the direct invoker call below
        // is period-correct, not a shortcut.
        try {
            ((MinecraftServerInvoker) (Object) server).peercraft$saveAllWorlds(false);
        } catch (RuntimeException e) {
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


    // 1.7.10's ISaveFormat has no getFile(String,String) (added later) — go through
    // Minecraft.mcDataDir directly, same derivation SuccessorLauncher already uses. Only ever
    // called with the client's own IntegratedServer, so Minecraft.getMinecraft() is correct.
    public static Path worldDir(MinecraftServer server) {
        return Minecraft.getMinecraft().mcDataDir.toPath().resolve("saves").resolve(server.getFolderName());
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
