package net.peercraft.network.rendezvous;

import net.peercraft.network.p2p.P2PSender;
import net.peercraft.network.p2p.RawPacketListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the real RendezvousClient/PunchCoordinator client-side flow against the real
 * rendezvous-server jar (built as a sibling project, see rendezvous-server/build.gradle)
 * running as a subprocess — end-to-end, no mocks, without needing two full Minecraft
 * clients. This is the layer P2PBridge.startHostViaRendezvous/startClientViaRendezvous
 * orchestrate; P2PBridge itself is a singleton so it can't host two simulated peers in
 * one JVM, but RendezvousClient/PunchCoordinator have no such restriction.
 *
 * Skips itself (rather than failing) if the sibling project's jar hasn't been built yet
 * — run `../rendezvous-server/gradlew jar` (or the project's own build) first.
 */
class RendezvousEndToEndTest {

    private static final int SERVER_PORT = 51091;

    private Process serverProcess;

    @BeforeEach
    void startServer() throws Exception {
        Path jar = findServerJar();
        org.junit.jupiter.api.Assumptions.assumeTrue(jar != null,
                "rendezvous-server jar not built — run its own `./gradlew jar` first to enable this test");

        serverProcess = new ProcessBuilder("java", "-jar", jar.toString(), String.valueOf(SERVER_PORT))
                .redirectErrorStream(true)
                .start();

        // Give the server a moment to bind before hammering it with test traffic.
        long deadline = System.currentTimeMillis() + 3000;
        while (!isServerUp() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(isServerUp(), "rendezvous-server subprocess did not come up in time");
    }

    @AfterEach
    void stopServer() {
        if (serverProcess != null) {
            serverProcess.destroyForcibly();
        }
    }

    private static boolean isServerUp() {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.setSoTimeout(200);
            probe.send(new DatagramPacket(RendezvousProtocol.encodeRegister(), 2, InetAddress.getByName("127.0.0.1"), SERVER_PORT));
            byte[] buf = new byte[64];
            probe.receive(new DatagramPacket(buf, buf.length));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path findServerJar() throws IOException {
        Path libsDir = Path.of("../rendezvous-server/build/libs");
        if (!Files.isDirectory(libsDir)) {
            return null;
        }
        try (var stream = Files.list(libsDir)) {
            return stream.filter(p -> p.toString().endsWith(".jar")).findFirst().orElse(null);
        }
    }

    @Test
    @Timeout(20)
    void hostAndJoinerPunchThroughToEachOther() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket hostSocket = new DatagramSocket();
             DatagramSocket joinerSocket = new DatagramSocket()) {

            P2PSender hostSender = new P2PSender(hostSocket);
            P2PSender joinerSender = new P2PSender(joinerSocket);

            AtomicReference<RawPacketListener> hostListener = new AtomicReference<>();
            AtomicReference<RawPacketListener> joinerListener = new AtomicReference<>();
            Thread hostRecvThread = startDemuxLoop(hostSocket, hostListener);
            Thread joinerRecvThread = startDemuxLoop(joinerSocket, joinerListener);

            try {
                CompletableFuture<String> roomCode = new CompletableFuture<>();
                CompletableFuture<int[]> hostResult = new CompletableFuture<>(); // [success(1/0), port]
                CompletableFuture<int[]> joinerResult = new CompletableFuture<>();

                RendezvousClient hostClient = new RendezvousClient(hostSender, loopback, SERVER_PORT);
                hostListener.set(hostClient);
                hostClient.registerRoom(
                        (code, changed) -> roomCode.complete(code),
                        new RendezvousClient.MatchCallback() {
                            @Override
                            public void onMatched(RendezvousProtocol.Address peer, long token) {
                                PunchCoordinator punch = new PunchCoordinator(hostSender, peer, token, new PunchCoordinator.Callback() {
                                    @Override
                                    public void onSuccess(String ip, int port) {
                                        hostResult.complete(new int[]{1, port});
                                    }

                                    @Override
                                    public void onFailure(String reason) {
                                        hostResult.complete(new int[]{0, 0});
                                    }
                                });
                                hostListener.set(punch);
                                punch.start();
                            }

                            @Override
                            public void onFailed(String reason) {
                                hostResult.completeExceptionally(new AssertionError("host registerRoom failed: " + reason));
                            }
                        }
                );

                String code = roomCode.get(10, TimeUnit.SECONDS);
                assertEquals(6, code.length());

                RendezvousClient joinerClient = new RendezvousClient(joinerSender, loopback, SERVER_PORT);
                joinerListener.set(joinerClient);
                joinerClient.joinRoom(code, new RendezvousClient.MatchCallback() {
                    @Override
                    public void onMatched(RendezvousProtocol.Address peer, long token) {
                        PunchCoordinator punch = new PunchCoordinator(joinerSender, peer, token, new PunchCoordinator.Callback() {
                            @Override
                            public void onSuccess(String ip, int port) {
                                joinerResult.complete(new int[]{1, port});
                            }

                            @Override
                            public void onFailure(String reason) {
                                joinerResult.complete(new int[]{0, 0});
                            }
                        });
                        joinerListener.set(punch);
                        punch.start();
                    }

                    @Override
                    public void onFailed(String reason) {
                        joinerResult.completeExceptionally(new AssertionError("joiner joinRoom failed: " + reason));
                    }
                });

                int[] hostOutcome = hostResult.get(15, TimeUnit.SECONDS);
                int[] joinerOutcome = joinerResult.get(15, TimeUnit.SECONDS);

                assertEquals(1, hostOutcome[0], "host-side hole punch should succeed on loopback");
                assertEquals(1, joinerOutcome[0], "joiner-side hole punch should succeed on loopback");
                assertEquals(joinerSocket.getLocalPort(), hostOutcome[1], "host should have punched through to the joiner's real port");
                assertEquals(hostSocket.getLocalPort(), joinerOutcome[1], "joiner should have punched through to the host's real port");
            } finally {
                hostRecvThread.interrupt();
                joinerRecvThread.interrupt();
            }
        }
    }

    /** Mirrors P2PBridge's demux: hand every datagram to whatever listener is currently active. */
    private static Thread startDemuxLoop(DatagramSocket socket, AtomicReference<RawPacketListener> listenerRef) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while (!Thread.currentThread().isInterrupted() && !socket.isClosed()) {
                try {
                    packet.setLength(buffer.length);
                    socket.receive(packet);
                    RawPacketListener listener = listenerRef.get();
                    if (listener != null) {
                        byte[] data = new byte[packet.getLength()];
                        System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
                        listener.onPacket(data, data.length, packet.getAddress(), packet.getPort());
                    }
                } catch (IOException e) {
                    break;
                }
            }
        }, "test-demux-loop");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
