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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the real RendezvousClient/PunchCoordinator client-side flow against the real
 * rendezvous-server jar (built as a sibling project, see rendezvous-server/build.gradle)
 * running as a subprocess — end-to-end, no mocks, without needing full Minecraft clients.
 * This is the layer P2PBridge.startHostViaRendezvous/startClientViaRendezvous orchestrate;
 * P2PBridge itself is a singleton so it can't host several simulated peers in one JVM, but
 * RendezvousClient/PunchCoordinator have no such restriction.
 *
 * The host-side demux loop here ({@link #startHostDemuxLoop}) mirrors P2PBridge's own
 * demux for the host role: always feed the persistent RendezvousClient, and additionally
 * fan out to every currently in-flight PunchCoordinator (one per joiner) rather than a
 * single replaceable slot — this is what lets several joiners punch through concurrently
 * without one match's punch attempt cancelling another's.
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
            byte[] register = RendezvousProtocol.encodeRegister(1, 0);
            probe.send(new DatagramPacket(register, register.length, InetAddress.getByName("127.0.0.1"), SERVER_PORT));
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

            Map<RendezvousProtocol.Address, PunchCoordinator> hostActivePunches = new ConcurrentHashMap<>();
            AtomicReference<RendezvousClient> hostClientRef = new AtomicReference<>();
            AtomicReference<RawPacketListener> joinerListener = new AtomicReference<>();
            Thread hostRecvThread = startHostDemuxLoop(hostSocket, hostClientRef, hostActivePunches);
            Thread joinerRecvThread = startDemuxLoop(joinerSocket, joinerListener);

            try {
                CompletableFuture<String> roomCode = new CompletableFuture<>();
                CompletableFuture<int[]> hostResult = new CompletableFuture<>(); // [success(1/0), port]
                CompletableFuture<int[]> joinerResult = new CompletableFuture<>();

                RendezvousClient hostClient = new RendezvousClient(hostSender, loopback, SERVER_PORT);
                hostClientRef.set(hostClient);
                hostClient.registerRoom(1, () -> 0,
                        (code, changed) -> roomCode.complete(code),
                        new RendezvousClient.MatchCallback() {
                            @Override
                            public void onMatched(RendezvousProtocol.Address peer, long token) {
                                PunchCoordinator punch = new PunchCoordinator(hostSender, peer, token, new PunchCoordinator.Callback() {
                                    @Override
                                    public void onSuccess(String ip, int port) {
                                        hostActivePunches.remove(peer);
                                        hostResult.complete(new int[]{1, port});
                                    }

                                    @Override
                                    public void onFailure(String reason) {
                                        hostActivePunches.remove(peer);
                                        hostResult.complete(new int[]{0, 0});
                                    }
                                });
                                hostActivePunches.put(peer, punch);
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

    /**
     * The actual point of this feature: two distinct joiners matching and punching through
     * to the same host room concurrently, neither interrupting the other's in-flight punch
     * attempt (the old single-slot rendezvousListener/PunchCoordinator bookkeeping in
     * P2PBridge used to make the second match cancel the first's punch entirely).
     */
    @Test
    @Timeout(25)
    void hostAndTwoJoinersPunchThroughConcurrently() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket hostSocket = new DatagramSocket();
             DatagramSocket joinerASocket = new DatagramSocket();
             DatagramSocket joinerBSocket = new DatagramSocket()) {

            P2PSender hostSender = new P2PSender(hostSocket);
            P2PSender joinerASender = new P2PSender(joinerASocket);
            P2PSender joinerBSender = new P2PSender(joinerBSocket);

            Map<RendezvousProtocol.Address, PunchCoordinator> hostActivePunches = new ConcurrentHashMap<>();
            Map<RendezvousProtocol.Address, CompletableFuture<int[]>> hostResults = new ConcurrentHashMap<>();
            AtomicReference<RendezvousClient> hostClientRef = new AtomicReference<>();
            AtomicReference<RawPacketListener> joinerAListener = new AtomicReference<>();
            AtomicReference<RawPacketListener> joinerBListener = new AtomicReference<>();

            Thread hostRecvThread = startHostDemuxLoop(hostSocket, hostClientRef, hostActivePunches);
            Thread joinerARecvThread = startDemuxLoop(joinerASocket, joinerAListener);
            Thread joinerBRecvThread = startDemuxLoop(joinerBSocket, joinerBListener);

            try {
                CompletableFuture<String> roomCode = new CompletableFuture<>();

                RendezvousClient hostClient = new RendezvousClient(hostSender, loopback, SERVER_PORT);
                hostClientRef.set(hostClient);
                hostClient.registerRoom(2, () -> 0,
                        (code, changed) -> roomCode.complete(code),
                        new RendezvousClient.MatchCallback() {
                            @Override
                            public void onMatched(RendezvousProtocol.Address peer, long token) {
                                CompletableFuture<int[]> result = hostResults.computeIfAbsent(peer, p -> new CompletableFuture<>());
                                PunchCoordinator punch = new PunchCoordinator(hostSender, peer, token, new PunchCoordinator.Callback() {
                                    @Override
                                    public void onSuccess(String ip, int port) {
                                        hostActivePunches.remove(peer);
                                        result.complete(new int[]{1, port});
                                    }

                                    @Override
                                    public void onFailure(String reason) {
                                        hostActivePunches.remove(peer);
                                        result.complete(new int[]{0, 0});
                                    }
                                });
                                hostActivePunches.put(peer, punch);
                                punch.start();
                            }

                            @Override
                            public void onFailed(String reason) {
                                roomCode.completeExceptionally(new AssertionError("host registerRoom failed: " + reason));
                            }
                        }
                );

                String code = roomCode.get(10, TimeUnit.SECONDS);

                CompletableFuture<int[]> joinerAResult = joinAndPunch(joinerASender, loopback, code, joinerAListener);
                CompletableFuture<int[]> joinerBResult = joinAndPunch(joinerBSender, loopback, code, joinerBListener);

                int[] joinerAOutcome = joinerAResult.get(15, TimeUnit.SECONDS);
                int[] joinerBOutcome = joinerBResult.get(15, TimeUnit.SECONDS);
                assertEquals(1, joinerAOutcome[0], "joiner A hole punch should succeed");
                assertEquals(1, joinerBOutcome[0], "joiner B hole punch should succeed");

                RendezvousProtocol.Address peerA = new RendezvousProtocol.Address(loopback, joinerASocket.getLocalPort());
                RendezvousProtocol.Address peerB = new RendezvousProtocol.Address(loopback, joinerBSocket.getLocalPort());
                int[] hostAOutcome = hostResults.get(peerA).get(15, TimeUnit.SECONDS);
                int[] hostBOutcome = hostResults.get(peerB).get(15, TimeUnit.SECONDS);

                assertEquals(1, hostAOutcome[0], "host should punch through to joiner A");
                assertEquals(1, hostBOutcome[0], "host should punch through to joiner B");
                assertEquals(joinerASocket.getLocalPort(), hostAOutcome[1]);
                assertEquals(joinerBSocket.getLocalPort(), hostBOutcome[1]);
            } finally {
                hostRecvThread.interrupt();
                joinerARecvThread.interrupt();
                joinerBRecvThread.interrupt();
            }
        }
    }

    private static CompletableFuture<int[]> joinAndPunch(P2PSender sender, InetAddress loopback, String code, AtomicReference<RawPacketListener> listenerRef) {
        CompletableFuture<int[]> result = new CompletableFuture<>();
        RendezvousClient joinerClient = new RendezvousClient(sender, loopback, SERVER_PORT);
        listenerRef.set(joinerClient);
        joinerClient.joinRoom(code, new RendezvousClient.MatchCallback() {
            @Override
            public void onMatched(RendezvousProtocol.Address peer, long token) {
                PunchCoordinator punch = new PunchCoordinator(sender, peer, token, new PunchCoordinator.Callback() {
                    @Override
                    public void onSuccess(String ip, int port) {
                        result.complete(new int[]{1, port});
                    }

                    @Override
                    public void onFailure(String reason) {
                        result.complete(new int[]{0, 0});
                    }
                });
                listenerRef.set(punch);
                punch.start();
            }

            @Override
            public void onFailed(String reason) {
                result.completeExceptionally(new AssertionError("joiner joinRoom failed: " + reason));
            }
        });
        return result;
    }

    /** Mirrors P2PBridge's joiner-role demux: hand every datagram to whatever listener is currently active. */
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

    /**
     * Mirrors P2PBridge's host-role demux: always feed the persistent RendezvousClient,
     * plus fan out to every currently in-flight PunchCoordinator (keyed by peer) instead of
     * a single replaceable slot — see class docs.
     */
    private static Thread startHostDemuxLoop(DatagramSocket socket, AtomicReference<RendezvousClient> hostClientRef,
                                              Map<RendezvousProtocol.Address, PunchCoordinator> activePunches) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while (!Thread.currentThread().isInterrupted() && !socket.isClosed()) {
                try {
                    packet.setLength(buffer.length);
                    socket.receive(packet);
                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());

                    RendezvousClient hostClient = hostClientRef.get();
                    if (hostClient != null) {
                        hostClient.onPacket(data, data.length, packet.getAddress(), packet.getPort());
                    }
                    for (PunchCoordinator punch : activePunches.values()) {
                        punch.onPacket(data, data.length, packet.getAddress(), packet.getPort());
                    }
                } catch (IOException e) {
                    break;
                }
            }
        }, "test-host-demux-loop");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
