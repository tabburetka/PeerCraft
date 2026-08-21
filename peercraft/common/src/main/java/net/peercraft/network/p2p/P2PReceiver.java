package net.peercraft.network.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class P2PReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final int MAX_UDP_PAYLOAD_SIZE = 65_507;

    // Ask the OS for a larger receive buffer (Linux's default is usually ~208KB — not
    // enough for the burst of large datagrams during initial world-chunk sync, and the
    // kernel silently drops the overflow). The OS will quietly clamp this to its real
    // maximum (net.core.rmem_max) anyway, so it's safe to ask for more than we need.
    private static final int SOCKET_BUFFER_SIZE_BYTES = 4 * 1024 * 1024;
    // Capacity of the queue between the receive thread and the processing thread. On
    // overflow a datagram is dropped with a warning — the receive thread must never
    // block, or the OS will start dropping packets on the socket itself again.
    private static final int PROCESSING_QUEUE_CAPACITY = 2048;

    private DatagramSocket socket;
    private volatile boolean running = false;
    private Thread listenThread;
    private Thread processingThread;
    private final BlockingQueue<IncomingDatagram> processingQueue = new LinkedBlockingQueue<>(PROCESSING_QUEUE_CAPACITY);
    // A static instance field on P2PReceiver itself:
    public static final P2PReceiver INSTANCE = new P2PReceiver();

    public boolean start(int port) {
        stop();
        try {
            socket = new DatagramSocket(port);
            socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE_BYTES);
            running = true;

            listenThread = new Thread(this::listenLoop, "PeerCraft-UDP-Receiver");
            listenThread.setDaemon(true);
            listenThread.start();

            processingThread = new Thread(this::processingLoop, "PeerCraft-UDP-Processor");
            processingThread.setDaemon(true);
            processingThread.start();

            LOGGER.info("[PeerCraft Receiver] UDP сокет успешно запущен на 0.0.0.0:{} (буфер приёма: {} байт)", getBoundPort(), socket.getReceiveBufferSize());
            return true;
        } catch (SocketException e) {
            running = false;
            socket = null;
            LOGGER.error("[PeerCraft Receiver] Не удалось запустить UDP сокет на порту {}. Порт уже занят другим экземпляром PeerCraft или другой программой — задай другой -Dpeercraft.clientUdpPort/-Dpeercraft.hostUdpPort.", port, e);
            return false;
        }
    }

    // Only reads the socket and immediately queues a copy — does nothing that could
    // delay the next receive() (no decode/reorder/TCP-write should ever happen here,
    // or the kernel will start dropping packets again under load).
    private void listenLoop() {
        byte[] buffer = new byte[MAX_UDP_PAYLOAD_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (running) {
            try {
                packet.setLength(buffer.length);
                socket.receive(packet);

                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());

                if (!processingQueue.offer(new IncomingDatagram(data, packet.getAddress(), packet.getPort()))) {
                    LOGGER.warn("[P2PReceiver] Очередь обработки переполнена ({} элементов) — датаграмма от {}:{} отброшена", PROCESSING_QUEUE_CAPACITY, packet.getAddress(), packet.getPort());
                }

            } catch (Exception e) {
                if (running) {
                    LOGGER.error("[P2PReceiver] Ошибка чтения UDP пакета", e);
                }
            }
        }
    }

    private void processingLoop() {
        while (running) {
            try {
                IncomingDatagram datagram = processingQueue.take();
                P2PBridge.INSTANCE.handleIncomingPacket(datagram.data, datagram.data.length, datagram.address, datagram.port);
                LOGGER.info("[P2PReceiver] Получено {} байт от {}:{}", datagram.data.length, datagram.address, datagram.port);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (running) {
                    LOGGER.error("[P2PReceiver] Ошибка обработки UDP пакета", e);
                }
            }
        }
    }

    public int getBoundPort() {
        return (this.socket != null && !this.socket.isClosed()) ? this.socket.getLocalPort() : 0;
    }

    // Exposes the same socket so P2PSender can send from it too — otherwise the NAT
    // mapping punched for hole punching would be useless (see P2PBridge).
    public DatagramSocket getSocket() {
        return socket;
    }
    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (processingThread != null) {
            processingThread.interrupt();
        }
        processingQueue.clear();
    }

    private static final class IncomingDatagram {
        final byte[] data;
        final InetAddress address;
        final int port;

        IncomingDatagram(byte[] data, InetAddress address, int port) {
            this.data = data;
            this.address = address;
            this.port = port;
        }
    }
}
