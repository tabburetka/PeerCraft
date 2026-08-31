package net.peercraft.network.modsync;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModSyncProtocolTest {

    private static final byte M = (byte) 0xE2;

    @Test
    void magicAndTypeAreReadable() {
        byte[] none = ModSyncProtocol.encodeManifestNone();
        assertEquals(ModSyncProtocol.T_MANIFEST_NONE, ModSyncProtocol.messageType(none, none.length));
        assertEquals(-1, ModSyncProtocol.messageType(new byte[]{0x00, 0x01}, 2));
        assertEquals(-1, ModSyncProtocol.messageType(new byte[]{M}, 1));
    }

    @Test
    void fixedEncodings() {
        assertArrayEquals(new byte[]{M, ModSyncProtocol.T_MANIFEST_NONE}, ModSyncProtocol.encodeManifestNone());
        assertArrayEquals(new byte[]{M, ModSyncProtocol.T_PING}, ModSyncProtocol.encodePing());
        assertEquals(ModSyncProtocol.T_PING, ModSyncProtocol.messageType(ModSyncProtocol.encodePing(), 2));

        assertArrayEquals(
                new byte[]{M, ModSyncProtocol.T_HELLO, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 'a', 0x00, 0x01, '1'},
                ModSyncProtocol.encodeHello(ModSyncProtocol.Loader.FABRIC, List.of(new ModSyncProtocol.ModRef("a", "1"))));

        assertArrayEquals(
                new byte[]{M, ModSyncProtocol.T_REQUEST_FILE, 0x00, 0x01, 'x'},
                ModSyncProtocol.encodeRequestFile("x"));

        assertArrayEquals(
                new byte[]{M, ModSyncProtocol.T_FILE_DONE, 0x00, 0x01, 'x', 0x01},
                ModSyncProtocol.encodeFileDone("x", true));

        assertArrayEquals(
                new byte[]{M, ModSyncProtocol.T_ABORT, 0x00, 0x01, 'k'},
                ModSyncProtocol.encodeAbort("k"));

        assertArrayEquals(
                new byte[]{M, ModSyncProtocol.T_FILE_ACK, 0x00, 0x01, 'x', 0x00, 0x00, 0x00, 0x05, 0x00, 0x00},
                ModSyncProtocol.encodeFileAck("x", 5, new int[0]));

        assertArrayEquals(
                new byte[]{M, ModSyncProtocol.T_FILE_CHUNK, 0x00, 0x01, 'x', 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 1, 2, 3},
                ModSyncProtocol.encodeFileChunk("x", 0, new byte[]{1, 2, 3}, 0, 3));
    }

    @Test
    void helloRoundTrip() {
        byte[] enc = ModSyncProtocol.encodeHello(ModSyncProtocol.Loader.NEOFORGE,
                List.of(new ModSyncProtocol.ModRef("alpha", "1.2.3"), new ModSyncProtocol.ModRef("beta", "0.0.1")));
        ModSyncProtocol.Hello h = ModSyncProtocol.decodeHello(enc, enc.length);
        assertEquals(ModSyncProtocol.PROTO_VERSION, h.protoVersion());
        assertEquals(ModSyncProtocol.Loader.NEOFORGE, h.loader());
        assertEquals(2, h.mods().size());
        assertEquals("alpha", h.mods().get(0).id());
        assertEquals("0.0.1", h.mods().get(1).version());
    }

    @Test
    void manifestRoundTripIncludingHashAndEnv() {
        byte[] sha = new byte[64];
        Arrays.fill(sha, (byte) 0x7);
        ModEntry e = new ModEntry("cool", "3.0", 123456, sha, "cool-3.0.jar", ModEntry.Env.SERVER, "https://x", "");
        byte[] enc = ModSyncProtocol.encodeManifest(0, List.of(e));
        ModSyncProtocol.Manifest m = ModSyncProtocol.decodeManifest(enc, enc.length);
        assertFalse(m.streamed());
        assertEquals(1, m.mods().size());
        ModEntry d = m.mods().get(0);
        assertEquals("cool", d.id());
        assertEquals(123456, d.sizeBytes());
        assertArrayEquals(sha, d.sha512());
        assertEquals("cool-3.0.jar", d.fileName());
        assertEquals(ModEntry.Env.SERVER, d.env());
        assertEquals("https://x", d.homepageUrl());
    }

    @Test
    void fileBeginRoundTrip() {
        byte[] sha = new byte[64];
        Arrays.fill(sha, (byte) 1);
        byte[] enc = ModSyncProtocol.encodeFileBegin("m", 10_000, 2, sha, 6000);
        ModSyncProtocol.FileBegin fb = ModSyncProtocol.decodeFileBegin(enc, enc.length);
        assertEquals("m", fb.modId());
        assertEquals(10_000, fb.size());
        assertEquals(2, fb.chunkCount());
        assertEquals(6000, fb.chunkSize());
        assertArrayEquals(sha, fb.sha512());
    }

    @Test
    void fileAckRoundTripWithGaps() {
        byte[] enc = ModSyncProtocol.encodeFileAck("m", 4, new int[]{4, 7, 9});
        ModSyncProtocol.FileAck ack = ModSyncProtocol.decodeFileAck(enc, enc.length);
        assertEquals(4, ack.nextContiguous());
        assertArrayEquals(new int[]{4, 7, 9}, ack.gapIndices());
    }

    @Test
    void truncatedHelloDecodesWhatItCanWithoutThrowing() {
        byte[] enc = ModSyncProtocol.encodeHello(ModSyncProtocol.Loader.FABRIC,
                List.of(new ModSyncProtocol.ModRef("a", "1"), new ModSyncProtocol.ModRef("b", "2")));
        byte[] cut = Arrays.copyOf(enc, enc.length - 3); // lop off the tail of the 2nd ref
        ModSyncProtocol.Hello h = assertDoesNotThrow(() -> ModSyncProtocol.decodeHello(cut, cut.length));
        assertEquals("a", h.mods().get(0).id());
    }

    @Test
    void manifestWithExtraTrailingBytesStillDecodes() {
        ModEntry e = new ModEntry("x", "1", 1, new byte[64], "x.jar", ModEntry.Env.BOTH, "", "");
        byte[] enc = ModSyncProtocol.encodeManifest(0, List.of(e));
        byte[] padded = Arrays.copyOf(enc, enc.length + 5); // simulate a future appended field
        ModSyncProtocol.Manifest m = assertDoesNotThrow(() -> ModSyncProtocol.decodeManifest(padded, padded.length));
        assertEquals("x", m.mods().get(0).id());
    }
}
