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
import java.util.zip.ZipOutputStream;

/**
 * Flushes the running world and zips its save directory into a single archive for the handoff
 * successor. Runs on the host; the successor unzips it and loads it (see {@code SuccessorLauncher}).
 *
 * <p>Excludes {@code session.lock} (the successor's own load takes its own lock) and any
 * PeerCraft migration/transfer scratch files. All other world-local extension data is included. Uses light zip compression — region files and NBT are already
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
        Path path = worldDir(server);
        net.peercraft.network.p2p.LocalPlayerIdentity.ArchiveIdentity identity =
                net.peercraft.network.p2p.LocalPlayerIdentity.captureForArchive(path);
        flush(server);
        identity.prepare(path);
        return archiveClosed(path, tmpDir, java.util.UUID.randomUUID().toString());
    }

    /** Flush running server state; call before requesting its normal shutdown. */
    public static void flush(MinecraftServer server) throws IOException {
        // Flush on the server thread so the save is consistent on disk before we read it.
        try {
            net.peercraft.network.handoff.ServerThreadTasks.executeOn(server::execute, () -> {
                server.getPlayerList().saveAll();
                server.saveEverything(true, true, true);
            });
        } catch (RuntimeException e) {
            throw new IOException("Could not flush world before handoff", e);
        }

    }

    /** Worker-thread operation: save, request normal shutdown, and verify its thread has exited. */
    public static void saveAndStop(MinecraftServer server, long stopTimeoutMillis) throws IOException {
        flush(server);
        java.util.concurrent.atomic.AtomicReference<Thread> serverThread = new java.util.concurrent.atomic.AtomicReference<>();
        net.peercraft.network.handoff.ServerThreadTasks.executeOn(server::execute, () -> {
            serverThread.set(Thread.currentThread());
            server.halt(false);
        });
        net.peercraft.network.handoff.ServerThreadTasks.awaitTermination(serverThread.get(), stopTimeoutMillis);
    }

    /** Archive a fully closed save without submitting tasks to a Minecraft server. */
    public static Result archiveClosed(Path worldDir, Path tmpDir, String attemptId) throws IOException {
        if (!attemptId.matches("[a-zA-Z0-9_-]{1,64}")) throw new IOException("Invalid snapshot attempt id");
        Files.createDirectories(tmpDir);
        Path zip = tmpDir.resolve("peercraft-handoff-" + attemptId + ".zip");

        Files.createFile(zip); // CREATE_NEW failure must never remove a previous attempt's archive.
        MessageDigest md = sha512();
        long[] total = {0L};
        try (OutputStream fileOut = Files.newOutputStream(zip, java.nio.file.StandardOpenOption.WRITE);
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

        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(zip, java.nio.file.StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        net.peercraft.network.handoff.HandoffFiles.forceDirectory(tmpDir);
        long size = Files.size(zip);
        byte[] sha = md.digest();
        LOGGER.info("[Handoff] Архив мира готов: {} ({} байт)", zip.getFileName(), size);
        return new Result(zip, size, sha);
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
