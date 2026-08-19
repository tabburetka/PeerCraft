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
            client.registerRoom((code, changed) -> roomCode.complete(code), new RendezvousClient.MatchCallback() {
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
}
