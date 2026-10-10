package net.peercraft.world;

import net.peercraft.client.handoff.WorldArchiveFiles;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** A verified full world snapshot retained locally before a player identity changes. */
public final class WorldProgressBackup {
    private WorldProgressBackup() { }
    public static Path create(Path world) throws IOException {
        Path directory = world.resolve(".peercraft-backup"); Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid world backup directory");
        Path temporary = Files.createTempFile(directory, "progress-", ".tmp");
        Path destination = directory.resolve("progress-" + UUID.randomUUID() + ".zip");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                WorldArchiveFiles.write(world, zip);
            }
            verify(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, destination); }
            return destination;
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void verify(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries(); byte[] buffer = new byte[65536];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement(); if (entry.isDirectory()) continue;
                CRC32 crc = new CRC32(); long size = 0;
                try (InputStream input = zip.getInputStream(entry)) {
                    int count; while ((count = input.read(buffer)) != -1) { crc.update(buffer, 0, count); size += count; }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("World backup verification failed");
            }
        }
    }
}
