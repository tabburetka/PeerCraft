package net.peercraft.network.handoff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;
class SnapshotValidationTest {
    @TempDir Path root;
    Path level(boolean data) throws Exception {
        Path file = root.resolve("level.dat");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            out.writeByte(10); out.writeUTF("");
            if (data) { out.writeByte(10); out.writeUTF("Data"); out.writeByte(0); }
            out.writeByte(0);
        } return file;
    }
    @Test void validLevelAndEmptyRegionPassWithoutLoadingMinecraft() throws Exception {
        level(true); Path region = Files.createDirectories(root.resolve("region")).resolve("r.0.0.mca"); Files.write(region, new byte[8192]);
        SnapshotValidation.validate(root);
    }
    @Test void missingDataAndTruncatedGzipAreRejected() throws Exception {
        assertThrows(IOException.class, () -> SnapshotValidation.validateLevel(level(false)));
        Path file = level(true); byte[] bytes = Files.readAllBytes(file); Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 3));
        assertThrows(IOException.class, () -> SnapshotValidation.validateLevel(file));
    }
    @Test void overlappingRegionsAndMissingExternalChunksAreRejected() throws Exception {
        Path region = root.resolve("r.0.0.mca"); ByteBuffer bytes = ByteBuffer.allocate(3 * 4096);
        bytes.putInt(0, 2 << 8 | 1); bytes.putInt(4, 2 << 8 | 1); bytes.putInt(8192, 1); bytes.put(8196, (byte)3);
        Files.write(region, bytes.array()); assertThrows(IOException.class, () -> SnapshotValidation.validateRegion(region));
        bytes.putInt(4, 0); bytes.put(8196, (byte)(128 | 2)); Files.write(region, bytes.array());
        assertThrows(IOException.class, () -> SnapshotValidation.validateRegion(region));
        Files.write(root.resolve("c.0.0.mcc"), new byte[]{1}); SnapshotValidation.validateRegion(region);
    }
}
