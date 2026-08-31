package net.peercraft.network.modsync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileReassemblerTest {

    private static byte[] payload(int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (i * 31 + 7);
        }
        return b;
    }

    private static List<byte[]> chunks(byte[] data, int chunkSize) {
        List<byte[]> out = new ArrayList<>();
        for (int off = 0; off < data.length; off += chunkSize) {
            int len = Math.min(chunkSize, data.length - off);
            byte[] c = new byte[len];
            System.arraycopy(data, off, c, 0, len);
            out.add(c);
        }
        return out;
    }

    @Test
    void reassemblesFromShuffledAndDuplicatedChunks(@TempDir Path dir) throws Exception {
        byte[] data = payload(5000);
        int chunkSize = 600;
        List<byte[]> cs = chunks(data, chunkSize);
        Path part = dir.resolve("x.jar.part");

        try (FileReassembler r = new FileReassembler(part, data.length, cs.size(), chunkSize, 1 << 20)) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < cs.size(); i++) order.add(i);
            Collections.shuffle(order, new java.util.Random(42));
            for (int idx : order) {
                r.accept(idx, cs.get(idx));
                r.accept(idx, cs.get(idx)); // duplicate — must be ignored
            }
            assertTrue(r.isComplete());
            byte[] hash = r.finishAndHash();
            assertArrayEquals(sha512(data), hash);
        }
        assertArrayEquals(data, Files.readAllBytes(part));
    }

    @Test
    void reportsMissingIndicesForASelectiveAck(@TempDir Path dir) throws Exception {
        byte[] data = payload(3000);
        int chunkSize = 500; // 6 chunks
        List<byte[]> cs = chunks(data, chunkSize);
        Path part = dir.resolve("y.jar.part");

        try (FileReassembler r = new FileReassembler(part, data.length, cs.size(), chunkSize, 1 << 20)) {
            r.accept(0, cs.get(0));
            r.accept(1, cs.get(1));
            r.accept(3, cs.get(3));
            assertEquals(2, r.nextContiguous());
            assertArrayEquals(new int[]{2, 4, 5}, r.missingIndices(10));
            assertFalse(r.isComplete());
        }
    }

    @Test
    void rejectsAChunkThatWouldOverrunTheDeclaredSize(@TempDir Path dir) throws Exception {
        Path part = dir.resolve("z.jar.part");
        try (FileReassembler r = new FileReassembler(part, 100, 2, 60, 1 << 20)) {
            assertThrows(IOException.class, () -> r.accept(1, new byte[60])); // 60 + 60 > 100
        }
    }

    @Test
    void rejectsADeclaredSizeAboveTheCap(@TempDir Path dir) {
        assertThrows(IOException.class,
                () -> new FileReassembler(dir.resolve("big.part"), 10_000, 1, 10_000, 4_096));
    }

    private static byte[] sha512(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-512").digest(data);
    }
}
