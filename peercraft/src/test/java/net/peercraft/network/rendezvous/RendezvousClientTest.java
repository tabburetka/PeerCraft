package net.peercraft.network.rendezvous;

import net.peercraft.network.p2p.P2PSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for a real bug: the retry loop used to keep resending REGISTER every
 * 500ms for the full 10s connect-timeout window even after ROOM_CREATED had already
 * arrived, because it checked "are we still in some pending state" instead of "are we
 * still on this specific step" — flooding the rendezvous server for no reason, and (had
 * the wait for a peer taken longer than 10s) would have spuriously timed out a
 * perfectly healthy WAITING_FOR_PEER room.
 */
class RendezvousClientTest {

    @Test
    @Timeout(15)
    void stopsResendingRegisterOnceRoomCreatedArrives() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket fakeServerSocket = new DatagramSocket();
             DatagramSocket clientSocket = new DatagramSocket()) {

            P2PSender clientSender = new P2PSender(clientSocket);
            AtomicInteger registerCount = new AtomicInteger(0);
            CompletableFuture<InetAddress> clientAddrFuture = new CompletableFuture<>();
            CompletableFuture<Integer> clientPortFuture = new CompletableFuture<>();

            Thread fakeServer = new Thread(() -> {
                byte[] buffer = new byte[64];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                boolean replied = false;
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        packet.setLength(buffer.length);
                        fakeServerSocket.receive(packet);
                        if (RendezvousProtocol.messageType(packet.getData(), packet.getLength()) == RendezvousProtocol.TYPE_REGISTER) {
                            registerCount.incrementAndGet();
                            if (!replied) {
                                replied = true;
                                clientAddrFuture.complete(packet.getAddress());
                                clientPortFuture.complete(packet.getPort());
                                RendezvousProtocol.Address observed = new RendezvousProtocol.Address(packet.getAddress(), packet.getPort());
                                byte[] reply = RendezvousProtocol.encodeRoomCreated("ABCDEF", observed);
                                fakeServerSocket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // socket closed on teardown
                }
            }, "fake-rendezvous-server");
            fakeServer.setDaemon(true);
            fakeServer.start();

            RendezvousClient client = new RendezvousClient(clientSender, loopback, fakeServerSocket.getLocalPort());
            CompletableFuture<String> roomCode = new CompletableFuture<>();
            client.registerRoom(4, () -> 0, (code, changed) -> roomCode.complete(code), new RendezvousClient.MatchCallback() {
                @Override
                public void onMatched(RendezvousProtocol.Address peer, long token) {
                }

                @Override
                public void onFailed(String reason) {
                    roomCode.completeExceptionally(new AssertionError("unexpected failure: " + reason));
                }
            });

            // Feed the fake server's replies back into the client manually (no P2PReceiver in this test).
            Thread clientRecvLoop = new Thread(() -> {
                byte[] buffer = new byte[64];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        packet.setLength(buffer.length);
                        clientSocket.receive(packet);
                        byte[] data = new byte[packet.getLength()];
                        System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
                        client.onPacket(data, data.length, packet.getAddress(), packet.getPort());
                    }
                } catch (Exception ignored) {
                    // socket closed on teardown
                }
            }, "test-client-recv-loop");
            clientRecvLoop.setDaemon(true);
            clientRecvLoop.start();

            assertEquals("ABCDEF", roomCode.get(5, TimeUnit.SECONDS));

            int countRightAfterRoomCreated = registerCount.get();
            // Wait well past several retry intervals (500ms) — if the bug were still
            // present, more REGISTERs would keep arriving during this window.
            Thread.sleep(2000);
            int countAfterWaiting = registerCount.get();

            fakeServer.interrupt();
            clientRecvLoop.interrupt();

            assertEquals(countRightAfterRoomCreated, countAfterWaiting,
                    "no further REGISTER packets should be sent once ROOM_CREATED has been received");
        }
    }

    /**
     * A host's RendezvousClient must be able to match several DIFFERENT joiners
     * concurrently — before the fix this dedup'd on a single shared lastMatchedToken
     * field, so a second joiner's PEER_FOUND would overwrite the first's token and a
     * late retransmit for the first joiner would then wrongly slip past the dedup check
     * and spawn a redundant punch.
     */
    @Test
    @Timeout(15)
    void matchesForDifferentPeersDoNotCrossDedupe() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket fakeServerSocket = new DatagramSocket();
             DatagramSocket clientSocket = new DatagramSocket()) {

            P2PSender clientSender = new P2PSender(clientSocket);
            RendezvousClient client = new RendezvousClient(clientSender, loopback, fakeServerSocket.getLocalPort());

            java.util.List<RendezvousProtocol.Address> matchedPeers = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            CompletableFuture<Void> roomCreated = new CompletableFuture<>();
            client.registerRoom(4, () -> 0, (code, changed) -> roomCreated.complete(null), new RendezvousClient.MatchCallback() {
                @Override
                public void onMatched(RendezvousProtocol.Address peer, long token) {
                    matchedPeers.add(peer);
                }

                @Override
                public void onFailed(String reason) {
                    roomCreated.completeExceptionally(new AssertionError("unexpected failure: " + reason));
                }
            });

            Thread fakeServer = new Thread(() -> {
                byte[] buffer = new byte[64];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        packet.setLength(buffer.length);
                        fakeServerSocket.receive(packet);
                        if (RendezvousProtocol.messageType(packet.getData(), packet.getLength()) == RendezvousProtocol.TYPE_REGISTER) {
                            RendezvousProtocol.Address observed = new RendezvousProtocol.Address(packet.getAddress(), packet.getPort());
                            byte[] reply = RendezvousProtocol.encodeRoomCreated("ABCDEF", observed);
                            fakeServerSocket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                        }
                    }
                } catch (Exception ignored) {
                    // socket closed on teardown
                }
            }, "fake-rendezvous-server");
            fakeServer.setDaemon(true);
            fakeServer.start();

            Thread clientRecvLoop = new Thread(() -> {
                byte[] buffer = new byte[64];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        packet.setLength(buffer.length);
                        clientSocket.receive(packet);
                        byte[] data = new byte[packet.getLength()];
                        System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
                        client.onPacket(data, data.length, packet.getAddress(), packet.getPort());
                    }
                } catch (Exception ignored) {
                    // socket closed on teardown
                }
            }, "test-client-recv-loop");
            clientRecvLoop.setDaemon(true);
            clientRecvLoop.start();

            roomCreated.get(5, TimeUnit.SECONDS);

            RendezvousProtocol.Address peerA = new RendezvousProtocol.Address(loopback, 40001);
            RendezvousProtocol.Address peerB = new RendezvousProtocol.Address(loopback, 40002);

            sendPeerFound(fakeServerSocket, loopback, clientSocket.getLocalPort(), peerA, 111L);
            sendPeerFound(fakeServerSocket, loopback, clientSocket.getLocalPort(), peerB, 222L);
            // Late retransmit of peer A's original match — must NOT fire onMatched again.
            sendPeerFound(fakeServerSocket, loopback, clientSocket.getLocalPort(), peerA, 111L);

            Thread.sleep(500);

            fakeServer.interrupt();
            clientRecvLoop.interrupt();

            assertEquals(2, matchedPeers.size(), "expected exactly one onMatched call per distinct peer, not per PEER_FOUND datagram");
            assertTrue(matchedPeers.contains(peerA));
            assertTrue(matchedPeers.contains(peerB));
        }
    }

    private static void sendPeerFound(DatagramSocket from, InetAddress toAddress, int toPort, RendezvousProtocol.Address peer, long token) throws Exception {
        byte[] msg = RendezvousProtocol.encodePeerFound(peer, token);
        from.send(new DatagramPacket(msg, msg.length, toAddress, toPort));
    }

    /**
     * Phase 5: a host must be able to look up which joiner (by accountId) matched at which
     * peer address — this is what P2PBridge.beginHostPunch threads through to
     * PlayerIdentityRegistry for save-data isolation. Anonymous joiners (no account attached
     * to their PEER_FOUND) must not leave a stale entry behind.
     */
    @Test
    @Timeout(15)
    void accountIdForPeerTracksTheMostRecentPeerFoundPerPeer() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");

        try (DatagramSocket fakeServerSocket = new DatagramSocket();
             DatagramSocket clientSocket = new DatagramSocket()) {

            P2PSender clientSender = new P2PSender(clientSocket);
            RendezvousClient client = new RendezvousClient(clientSender, loopback, fakeServerSocket.getLocalPort());

            CompletableFuture<Void> roomCreated = new CompletableFuture<>();
            client.registerRoom(4, () -> 0, (code, changed) -> roomCreated.complete(null), new RendezvousClient.MatchCallback() {
                @Override
                public void onMatched(RendezvousProtocol.Address peer, long token) {
                }

                @Override
                public void onFailed(String reason) {
                    roomCreated.completeExceptionally(new AssertionError("unexpected failure: " + reason));
                }
            });

            Thread fakeServer = new Thread(() -> {
                byte[] buffer = new byte[64];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        packet.setLength(buffer.length);
                        fakeServerSocket.receive(packet);
                        if (RendezvousProtocol.messageType(packet.getData(), packet.getLength()) == RendezvousProtocol.TYPE_REGISTER) {
                            RendezvousProtocol.Address observed = new RendezvousProtocol.Address(packet.getAddress(), packet.getPort());
                            byte[] reply = RendezvousProtocol.encodeRoomCreated("ABCDEF", observed);
                            fakeServerSocket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                        }
                    }
                } catch (Exception ignored) {
                    // socket closed on teardown
                }
            }, "fake-rendezvous-server");
            fakeServer.setDaemon(true);
            fakeServer.start();

            Thread clientRecvLoop = new Thread(() -> {
                byte[] buffer = new byte[64];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        packet.setLength(buffer.length);
                        clientSocket.receive(packet);
                        byte[] data = new byte[packet.getLength()];
                        System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());
                        client.onPacket(data, data.length, packet.getAddress(), packet.getPort());
                    }
                } catch (Exception ignored) {
                    // socket closed on teardown
                }
            }, "test-client-recv-loop");
            clientRecvLoop.setDaemon(true);
            clientRecvLoop.start();

            roomCreated.get(5, TimeUnit.SECONDS);

            RendezvousProtocol.Address peerWithAccount = new RendezvousProtocol.Address(loopback, 40001);
            RendezvousProtocol.Address anonymousPeer = new RendezvousProtocol.Address(loopback, 40002);
            java.util.UUID accountId = java.util.UUID.randomUUID();

            byte[] withAccount = RendezvousProtocol.encodePeerFoundWithAccount(peerWithAccount, 111L, accountId);
            fakeServerSocket.send(new DatagramPacket(withAccount, withAccount.length, loopback, clientSocket.getLocalPort()));
            sendPeerFound(fakeServerSocket, loopback, clientSocket.getLocalPort(), anonymousPeer, 222L);

            Thread.sleep(500);

            fakeServer.interrupt();
            clientRecvLoop.interrupt();

            assertEquals(java.util.Optional.of(accountId), client.accountIdForPeer(peerWithAccount));
            assertEquals(java.util.Optional.empty(), client.accountIdForPeer(anonymousPeer));
        }
    }
}
