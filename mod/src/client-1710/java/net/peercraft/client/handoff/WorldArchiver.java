package net.peercraft.client.handoff;

import net.minecraft.client.Minecraft;
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

/** Minecraft 1.7.10 adapter: save tasks run through the injected server-tick queue. */
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

    private static final java.util.Map<MinecraftServer, Thread> closingThreads =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<MinecraftServer, Thread>());
    public static void awaitClosed(MinecraftServer server, long timeoutMillis) throws IOException {
        Thread thread = closingThreads.get(server);
        if (thread == null) throw new IOException("Source server thread termination is not established");
        net.peercraft.network.handoff.ServerThreadTasks.awaitTermination(thread, timeoutMillis);
    }
    private WorldArchiver() {
    }

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
        net.peercraft.network.handoff.ServerThreadTasks.execute(server, () -> {
            server.getConfigurationManager().saveAllPlayerData();
            if (server.worldServers == null) throw new IllegalStateException("Server has no loaded worlds");
            for (net.minecraft.world.WorldServer world : server.worldServers) {
                if (world == null) continue;
                try { world.saveAllChunks(true, null); }
                catch (net.minecraft.world.MinecraftException e) { throw new IllegalStateException("Could not save dimension", e); }
            }
        });
        try { net.minecraft.world.storage.ThreadedFileIOBase.threadedIOInstance.waitForFinish(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted world flush", e); }

    }

    /** Worker-thread operation: save, request normal shutdown, and verify its thread has exited. */
    public static void saveAndStop(MinecraftServer server, long stopTimeoutMillis) throws IOException {
        java.util.concurrent.atomic.AtomicReference<Thread> serverThread = new java.util.concurrent.atomic.AtomicReference<>();
        net.peercraft.network.handoff.ServerThreadTasks.execute(server, () -> {
            server.getConfigurationManager().saveAllPlayerData();
            if (server.worldServers == null) throw new IllegalStateException("Server has no loaded worlds");
            for (net.minecraft.world.WorldServer world : server.worldServers) {
                if (world == null) continue;
                try { world.saveAllChunks(true, null); }
                catch (net.minecraft.world.MinecraftException e) { throw new IllegalStateException("Could not save dimension", e); }
            }
            serverThread.set(Thread.currentThread()); closingThreads.put(server, Thread.currentThread());
            server.initiateShutdown();
        });
        net.peercraft.network.handoff.ServerThreadTasks.awaitTermination(serverThread.get(), stopTimeoutMillis);
        try { net.minecraft.world.storage.ThreadedFileIOBase.threadedIOInstance.waitForFinish(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted world flush", e); }
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
