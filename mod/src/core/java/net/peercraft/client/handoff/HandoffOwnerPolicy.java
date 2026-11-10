package net.peercraft.client.handoff;

import net.peercraft.network.handoff.HandoffFiles;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** The world's original command owner remains its owner after the integrated server moves. */
public final class HandoffOwnerPolicy {
    private static final String FILE = "peercraft-handoff-owner.txt";
    private HandoffOwnerPolicy() { }

    public static UUID read(Path world) throws IOException {
        Path file = world.resolve(FILE);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != 39)
            throw new IOException("Invalid handoff owner record");
        String value = new String(Files.readAllBytes(file), StandardCharsets.US_ASCII);
        if (!value.startsWith("1\n") || value.charAt(38) != '\n') throw new IOException("Invalid handoff owner record");
        try {
            UUID owner = UUID.fromString(value.substring(2, 38));
            if (!value.substring(2, 38).equals(owner.toString())) throw new IllegalArgumentException();
            return owner;
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid handoff owner UUID", invalid); }
    }

    /** Write after the source server is closed, before the snapshot is archived. */
    public static void write(Path world, UUID owner) throws IOException {
        if (owner == null) throw new IOException("Source owner UUID is unavailable");
        Path existing = world.resolve(FILE);
        UUID previous = read(world);
        if (previous != null) {
            if (!previous.equals(owner)) throw new IOException("Handoff owner changed unexpectedly");
            return;
        }
        Path temporary = Files.createTempFile(world, ".peercraft-owner-", ".tmp");
        try {
            ByteBuffer bytes = ByteBuffer.wrap(("1\n" + owner + "\n").getBytes(StandardCharsets.US_ASCII));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, existing, StandardCopyOption.ATOMIC_MOVE);
            HandoffFiles.forceDirectory(world);
        } finally { Files.deleteIfExists(temporary); }
    }
}
