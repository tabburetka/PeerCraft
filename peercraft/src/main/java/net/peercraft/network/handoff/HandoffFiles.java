package net.peercraft.network.handoff;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;

/** Durability helpers; directory fsync is available on POSIX file stores. */
public final class HandoffFiles {
    private HandoffFiles() { }
    public static void forceDirectory(Path directory) throws IOException {
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        }
    }
}
