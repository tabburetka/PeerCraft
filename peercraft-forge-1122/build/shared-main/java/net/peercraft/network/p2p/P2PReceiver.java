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
    // Captured once in start(), before the listen thread can grab the socket's monitor in
    // receive() — see the comment there. getBoundPort() returns this instead of calling
    // socket.getLocalPort() (which is synchronized on the socket and would block for the whole
    // duration of an in-flight receive()).
    private volatile int boundPort = 0;
    private Thread listenThread;
    private Thread processingThread;
    private final BlockingQueue<IncomingDatagram> processingQueue = new LinkedBlockingQueue<>(PROCESSING_QUEUE_CAPACITY);

    // Per-datagram receive logging used to be a single LOGGER.info per packet, which floods
    // latest.log (100k+ lines in a session) and can fill the disk during chunk sync. Now the
    // per-packet line is DEBUG only, and processingLoop emits one aggregated INFO summary at
    // most once per this interval — enough to see traffic is flowing without the spam.
    private static final long RECV_SUMMARY_INTERVAL_NANOS = 10_000_000_000L;
    // Only touched by the single processing thread — no synchronisation needed.
    private boolean recvSummaryWindowOpen = false;
    private long recvSummaryWindowStartNanos = 0L;
    private long recvSummaryCount = 0L;
    private long recvSummaryBytes = 0L;
    // A static instance field on P2PReceiver itself:
    public static final P2PReceiver INSTANCE = new P2PReceiver();

    public boolean start(int port) {
        stop();
        try {
            socket = new DatagramSocket(port);
            socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE_BYTES);
            // P2PSender borrows this exact socket (P2PBridge.restartReceiver constructs it right
            // after this call). Its send buffer has to be sized HERE, before the listen thread
            // starts, for the same reason as the getters below — once listenLoop is in
            // socket.receive() it holds the socket's monitor, and setSendBufferSize() (also
            // synchronized on the socket) called from the P2PSender constructor would block
            // forever, hanging the client/host start path.
            socket.setSendBufferSize(SOCKET_BUFFER_SIZE_BYTES);
            // Read anything off the socket that we want for the log line BEFORE starting the
            // listen thread: DatagramSocket's getters are synchronized on the socket, and
            // listenLoop's socket.receive() holds that same monitor for its entire (unbounded)
            // blocking wait — so a getReceiveBufferSize()/getLocalPort() call made after
            // listenThread.start() can lose the race and block this thread forever (it did, on
            // the slow first-run path). Seen from initClient() that means the game never
            // finishes Minecraft.<init> and hangs at a black screen.
            int actualBufferSizeBytes = socket.getReceiveBufferSize();
            this.boundPort = socket.getLocalPort();
            running = true;

            listenThread = new Thread(this::listenLoop, "PeerCraft-UDP-Receiver");
            listenThread.setDaemon(true);
            listenThread.start();

            processingThread = new Thread(this::processingLoop, "PeerCraft-UDP-Processor");
            processingThread.setDaemon(true);
            processingThread.start();

            LOGGER.info("[PeerCraft Receiver] UDP сокет успешно запущен на 0.0.0.0:{} (буфер приёма: {} байт)", boundPort, actualBufferSizeBytes);
            return true;
        } catch (SocketException e) {
            running = false;
            socket = null;
            boundPort = 0;
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
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("[P2PReceiver] Получено {} байт от {}:{}", datagram.data.length, datagram.address, datagram.port);
                }
                logReceiveSummary(datagram.data.length);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (running) {
                    LOGGER.error("[P2PReceiver] Ошибка обработки UDP пакета", e);
                }
            }
        }
    }

    // Aggregates per-datagram counts and flushes a single INFO line per RECV_SUMMARY_INTERVAL_NANOS.
    private void logReceiveSummary(int datagramBytes) {
        long now = System.nanoTime();
        if (!recvSummaryWindowOpen) {
            recvSummaryWindowOpen = true;
            recvSummaryWindowStartNanos = now;
        }
        recvSummaryCount++;
        recvSummaryBytes += datagramBytes;

        long elapsed = now - recvSummaryWindowStartNanos;
        if (elapsed < RECV_SUMMARY_INTERVAL_NANOS) {
            return;
        }
        LOGGER.info("[P2PReceiver] За {} с: {} датаграмм, {} КиБ",
                elapsed / 1_000_000_000L, recvSummaryCount, recvSummaryBytes / 1024);
        recvSummaryWindowStartNanos = now;
        recvSummaryCount = 0L;
        recvSummaryBytes = 0L;
        recvSummaryWindowOpen = true;
    }

    public int getBoundPort() {
        // Returns the port cached in start() rather than calling socket.getLocalPort() — that
        // getter is synchronized on the socket, and the listen thread holds that monitor for
        // the entire (open-ended) duration of socket.receive(), so calling it from another
        // thread while a receive is in flight blocks that thread indefinitely. Callers on the
        // client/host start path (P2PBridge.startClient/startHost log lines) hit exactly that.
        return running ? boundPort : 0;
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
        recvSummaryWindowOpen = false;
        recvSummaryWindowStartNanos = 0L;
        recvSummaryCount = 0L;
        recvSummaryBytes = 0L;
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
