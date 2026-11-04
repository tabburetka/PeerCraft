package net.peercraft.network.control;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SecureControlChannelTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final byte[] SECRET = "sessionToken=correct-horse-battery-staple".getBytes(StandardCharsets.US_ASCII);

    @Test void handshakeDerivesMatchingKeysAndAuthenticatesBothDirections() throws Exception {
        ControlIdentity server = ControlIdentity.generate();
        ControlProtocol.ClientHello hello = new ControlProtocol.ClientHello();
        assertEquals(ControlProtocol.REPLY_SIZE, hello.packet().length, "HELLO is never smaller than its reply");
        ControlProtocol.ServerReply reply = ControlProtocol.answer(server.keyPair, hello.packet(), hello.packet().length);
        ControlProtocol.Established client = ControlProtocol.finishClient(hello, reply.packet, reply.packet.length);
        assertNotNull(client);
        assertArrayEquals(server.publicKey, client.serverStatic);

        byte[] up = ControlProtocol.seal(client.keys, true, 7, SECRET, 0, SECRET.length);
        assertFalse(contains(up, SECRET), "ciphertext must not carry the plaintext");
        assertArrayEquals(SECRET, ControlProtocol.open(reply.keys, true, up, up.length));
        assertNull(ControlProtocol.open(reply.keys, false, up, up.length), "direction is bound into the keys");

        byte[] down = ControlProtocol.seal(reply.keys, false, 0, SECRET, 0, SECRET.length);
        assertArrayEquals(SECRET, ControlProtocol.open(client.keys, false, down, down.length));
        for (int i = 0; i < down.length; i++) {
            byte[] tampered = down.clone();
            tampered[i] ^= 1;
            assertNull(ControlProtocol.open(client.keys, false, tampered, tampered.length), "flipped byte " + i);
        }
    }

    @Test void replyFromAnotherKeyOrTamperedReplyIsRejected() throws Exception {
        ControlProtocol.ClientHello hello = new ControlProtocol.ClientHello();
        ControlProtocol.ServerReply reply = ControlProtocol.answer(ControlIdentity.generate().keyPair, hello.packet(), hello.packet().length);
        byte[] forged = reply.packet.clone();
        forged[forged.length - 1] ^= 1;
        assertNull(ControlProtocol.finishClient(hello, forged, forged.length));
        // A reply computed for a different hello must not verify either.
        assertNull(ControlProtocol.finishClient(new ControlProtocol.ClientHello(), reply.packet, reply.packet.length));
        // Short hellos are ignored, so the server can never amplify spoofed traffic.
        assertNull(ControlProtocol.answer(ControlIdentity.generate().keyPair, hello.packet(), ControlProtocol.HELLO_BODY));
    }

    @Test void pointsOffTheCurveAreRefused() {
        byte[] point = new byte[ControlCrypto.PUBLIC_KEY_BYTES];
        point[0] = 4; point[64] = 1;
        assertThrows(java.security.GeneralSecurityException.class, () -> ControlCrypto.decodePublicKey(point));
    }

    @Test void replayWindowRejectsRepeatsAndStaleCounters() {
        ReplayWindow window = new ReplayWindow();
        assertTrue(window.accept(0));
        assertTrue(window.accept(5));
        assertTrue(window.accept(3), "reordering inside the window is fine");
        assertFalse(window.accept(3));
        assertFalse(window.accept(5));
        assertTrue(window.accept(200));
        assertFalse(window.accept(100), "too old to tell apart from a replay");
    }

    @Test void identityFileRoundTrips(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("control-identity.key");
        ControlIdentity created = ControlIdentity.loadOrCreate(file);
        ControlIdentity loaded = ControlIdentity.loadOrCreate(file);
        assertArrayEquals(created.publicKey, loaded.publicKey);
        Files.write(file, "public=AAAA\nprivate=AAAA\n".getBytes(StandardCharsets.US_ASCII));
        assertThrows(java.io.IOException.class, () -> ControlIdentity.load(file));
    }

    @Test void trustOnFirstUseRemembersAndRefusesAChangedKey(@TempDir Path dir) throws Exception {
        ControlTrust.useStore(dir.resolve("known-servers"));
        try {
            InetAddress address = InetAddress.getByAddress("rdv.example.test", new byte[]{10, 1, 2, 3});
            byte[] first = ControlIdentity.generate().publicKey;
            assertFalse(ControlTrust.requiresEncryption("rdv.example.test", 51000, address));
            assertTrue(ControlTrust.accept("rdv.example.test", 51000, address, first));
            assertTrue(ControlTrust.requiresEncryption("rdv.example.test", 51000, address), "seen once, never plaintext again");
            assertFalse(ControlTrust.accept("rdv.example.test", 51000, address, ControlIdentity.generate().publicKey));
            ControlTrust.useStore(dir.resolve("known-servers")); // reload from disk
            assertTrue(ControlTrust.accept("rdv.example.test", 51000, address, first));
            assertTrue(ControlTrust.requiresEncryption("connection.peercraft.ru", 51000, address), "official server is pinned");
        } finally {
            ControlTrust.useStore(java.nio.file.Paths.get("config", "peercraft", "known-servers"));
        }
    }

    @Test void secretsNeverCrossTheWireInPlaintext() throws Exception {
        ControlIdentity identity = ControlIdentity.generate();
        try (SecureServerSocket server = new SecureServerSocket(0, identity, false);
             Wiretap tap = new Wiretap(server.getLocalPort());
             SecureControlSocket client = SecureControlSocket.forServer()) {
            Thread echo = echo(server);
            client.setSoTimeout(5_000);
            client.send(new DatagramPacket(SECRET, SECRET.length, LOOPBACK, tap.port()));
            byte[] got = receive(client);
            assertArrayEquals(reversed(SECRET), got);
            assertEquals(1, server.sessionCount());
            for (byte[] datagram : tap.seen) assertFalse(contains(datagram, SECRET), "secret leaked on the wire");
            assertFalse(contains(tap.all(), reversed(SECRET)), "reply leaked on the wire");
            echo.interrupt();
        }
    }

    @Test void serverRestartIsHealedByResetAndResend() throws Exception {
        ControlIdentity identity = ControlIdentity.generate();
        SecureServerSocket first = new SecureServerSocket(0, identity, false);
        int port = first.getLocalPort();
        try (SecureControlSocket client = SecureControlSocket.forServer()) {
            client.setSoTimeout(5_000);
            echo(first);
            client.send(new DatagramPacket(new byte[]{1}, 1, LOOPBACK, port));
            assertArrayEquals(new byte[]{1}, receive(client));
            first.close();
            try (SecureServerSocket second = new SecureServerSocket(port, identity, false)) {
                echo(second);
                client.send(new DatagramPacket(new byte[]{2, 3}, 2, LOOPBACK, port));
                assertArrayEquals(new byte[]{3, 2}, receive(client), "the datagram the new server could not open is resent");
                assertEquals(1, second.sessionCount());
            }
        }
    }

    @Test void unknownServerThatNeverHandshakesFallsBackToPlaintext() throws Exception {
        try (DatagramSocket legacy = new DatagramSocket(0, LOOPBACK);
             SecureControlSocket client = SecureControlSocket.forServer()) {
            legacy.setSoTimeout(5_000);
            client.setSoTimeout(5_000);
            client.send(new DatagramPacket(new byte[]{42}, 1, LOOPBACK, legacy.getLocalPort()));
            DatagramPacket in = new DatagramPacket(new byte[512], 512);
            do legacy.receive(in); while (in.getData()[0] == ControlProtocol.MAGIC);
            assertEquals(1, in.getLength());
            assertEquals(42, in.getData()[0]);
            legacy.send(new DatagramPacket(new byte[]{43}, 1, in.getSocketAddress()));
            assertArrayEquals(new byte[]{43}, receive(client));
        }
    }

    @Test void pinnedServerWithTheWrongKeyGetsNothing() throws Exception {
        System.setProperty("peercraft.rendezvous.serverKey", Base64.getEncoder().encodeToString(ControlIdentity.generate().publicKey));
        try (SecureServerSocket server = new SecureServerSocket(0, ControlIdentity.generate(), true);
             SecureControlSocket client = SecureControlSocket.forServer()) {
            List<byte[]> delivered = new CopyOnWriteArrayList<>();
            Thread reader = new Thread(() -> {
                try { while (true) { DatagramPacket in = new DatagramPacket(new byte[512], 512); server.receive(in); delivered.add(Arrays.copyOf(in.getData(), in.getLength())); } }
                catch (Exception ignored) { }
            });
            reader.setDaemon(true);
            reader.start();
            client.send(new DatagramPacket(SECRET, SECRET.length, LOOPBACK, server.getLocalPort()));
            Thread.sleep(1_500);
            assertTrue(delivered.isEmpty(), "nothing may reach an impostor, not even as plaintext");
        } finally {
            System.clearProperty("peercraft.rendezvous.serverKey");
        }
    }

    @Test void sharedSocketPassesPeerTrafficThroughUntouched() throws Exception {
        try (SecureServerSocket server = new SecureServerSocket(0, ControlIdentity.generate(), false);
             SecureControlSocket shared = SecureControlSocket.shared(0);
             DatagramSocket peer = new DatagramSocket(0, LOOPBACK)) {
            SecureControlSocket.registerServer(LOOPBACK, server.getLocalPort());
            echo(server);
            shared.setSoTimeout(5_000);
            peer.setSoTimeout(5_000);
            shared.send(new DatagramPacket(new byte[]{9, 9}, 2, LOOPBACK, peer.getLocalPort()));
            DatagramPacket in = new DatagramPacket(new byte[64], 64);
            peer.receive(in);
            assertArrayEquals(new byte[]{9, 9}, Arrays.copyOf(in.getData(), in.getLength()));
            peer.send(new DatagramPacket(new byte[]{(byte) 0xEC, 3}, 2, in.getSocketAddress()));
            assertArrayEquals(new byte[]{(byte) 0xEC, 3}, receive(shared), "peer bytes are never interpreted");

            shared.send(new DatagramPacket(new byte[]{5, 6}, 2, LOOPBACK, server.getLocalPort()));
            assertArrayEquals(new byte[]{6, 5}, receive(shared));
        }
    }

    @Test void plaintextIsDroppedWhenTheServerRequiresEncryption() throws Exception {
        try (SecureServerSocket server = new SecureServerSocket(0, ControlIdentity.generate(), false);
             DatagramSocket legacy = new DatagramSocket()) {
            server.setSoTimeout(300);
            legacy.send(new DatagramPacket(new byte[]{1, 2}, 2, LOOPBACK, server.getLocalPort()));
            assertThrows(SocketTimeoutException.class, () -> server.receive(new DatagramPacket(new byte[64], 64)));
        }
    }

    private static Thread echo(DatagramSocket server) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                while (!server.isClosed()) {
                    packet.setLength(buffer.length);
                    server.receive(packet);
                    byte[] reply = reversed(Arrays.copyOf(buffer, packet.getLength()));
                    server.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                }
            } catch (Exception ignored) { }
        }, "secure-echo");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static byte[] receive(DatagramSocket socket) throws Exception {
        DatagramPacket in = new DatagramPacket(new byte[2048], 2048);
        socket.receive(in);
        return Arrays.copyOf(in.getData(), in.getLength());
    }

    private static byte[] reversed(byte[] data) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) out[i] = data[data.length - 1 - i];
        return out;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    /** Loopback UDP relay that records every datagram in both directions. */
    private static final class Wiretap implements AutoCloseable {
        final List<byte[]> seen = new CopyOnWriteArrayList<>();
        private final DatagramSocket front = new DatagramSocket(0, LOOPBACK);
        private final DatagramSocket back = new DatagramSocket(0, LOOPBACK);
        private volatile InetSocketAddress client;

        Wiretap(int serverPort) throws Exception {
            pump(front, () -> new InetSocketAddress(LOOPBACK, serverPort), back, true);
            pump(back, () -> client, front, false);
        }

        int port() { return front.getLocalPort(); }

        byte[] all() {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (byte[] datagram : seen) out.write(datagram, 0, datagram.length);
            return out.toByteArray();
        }

        private void pump(DatagramSocket from, java.util.function.Supplier<InetSocketAddress> to, DatagramSocket via, boolean upstream) {
            Thread thread = new Thread(() -> {
                byte[] buffer = new byte[65_536];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    while (!from.isClosed()) {
                        packet.setLength(buffer.length);
                        from.receive(packet);
                        if (upstream) client = (InetSocketAddress) packet.getSocketAddress();
                        byte[] copy = Arrays.copyOf(buffer, packet.getLength());
                        seen.add(copy);
                        InetSocketAddress target = to.get();
                        if (target != null) via.send(new DatagramPacket(copy, copy.length, target));
                    }
                } catch (Exception ignored) { }
            }, "wiretap");
            thread.setDaemon(true);
            thread.start();
        }

        @Override public void close() {
            front.close();
            back.close();
        }
    }
}
