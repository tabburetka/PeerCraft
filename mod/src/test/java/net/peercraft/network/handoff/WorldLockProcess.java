package net.peercraft.network.handoff;
import java.nio.channels.FileChannel;
import java.nio.file.*;
public final class WorldLockProcess {
    public static void main(String[] args) throws Exception {
        try (FileChannel file = FileChannel.open(Paths.get(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             java.nio.channels.FileLock lock = file.lock()) {
            System.out.println("LOCKED"); System.out.flush(); System.in.read();
        }
    }
}
