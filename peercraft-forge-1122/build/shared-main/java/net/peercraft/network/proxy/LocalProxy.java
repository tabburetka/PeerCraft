package net.peercraft.network.proxy;

import net.peercraft.network.p2p.P2PBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class LocalProxy {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    // Each inbound P2P packet arrives as its own sendToClient() call and used to go straight
    // out as its own write()+flush() — one TCP segment per UDP datagram the other side
    // happened to split a message into. That's finer-grained than a raw LAN socket would
    // ever deliver the same bytes (Minecraft's own writes tend to land in one read() on a
    // direct connection), and on a fast/low-latency link (loopback, or just a quick peer)
    // it reliably reproduces a pre-existing Forge 1.7.10 handshake race — client- and
    // Netty-IO-thread bookkeeping (FMLClientHandler.waitForPlayClient's lazily-initialized
    // CountDownLatch, NetworkManager's packet-listener tuples) isn't safe against a burst of
    // same-millisecond channelRead calls, only against network-paced ones. Coalescing
    // back-to-back chunks into fewer, larger writes here (below) closes most of that gap
    // without touching the wire protocol or packet ordering at all — pure local-loopback
    // transport smoothing, invisible to the rest of the mod.
    private static final long COALESCE_WINDOW_MILLIS = 3;

    // Coalescing alone wasn't the whole story: even a single write lands on the local
    // Minecraft client's socket essentially instantly over loopback, and Forge 1.7.10's own
    // client-side handshake code (FMLClientHandler.playClientBlock, set up from the main
    // render thread's tick loop) assumes it always gets scheduled at least once before the
    // Netty IO thread reaches the point where it waits on that latch — true on any real
    // network (the round trip alone buys it that), never true on loopback. Holding back the
    // very first burst of bytes to a freshly-connected local client for a short, fixed grace
    // period gives that thread the real wall-clock time a network connection would have given
    // it for free — after which forwarding proceeds at full speed as before. A no-op past the
    // grace window, and per-connection only, so it can't turn into a creeping per-packet delay.
    private static final long CONNECT_GRACE_MILLIS = 200;

    private ServerSocket serverSocket;
    private Socket activeClientSocket;
    private OutputStream clientOut;
    private volatile boolean running = false;
    private volatile int boundPort = 0;
    private volatile long clientConnectedAtMillis = 0;
    // Separate from `running` — that flag only flips true once runProxy() has actually bound
    // the socket (a different thread, asynchronously), so gating the coalescer's loop on it
    // too would race it into exiting immediately on every start().
    private volatile boolean flusherRunning = false;

    private final Object pendingLock = new Object();
    private ByteArrayOutputStream pending;

    private final P2PBridge p2pBridge;

    public LocalProxy(P2PBridge p2pBridge) {
        this.p2pBridge = p2pBridge;
    }

    public void start(int port) {
        if (running) {
            LOGGER.warn("[LocalProxy] TCP-прокси уже запущен на 127.0.0.1:{}", boundPort);
            return;
        }

        Thread proxyThread = new Thread(() -> runProxy(port), "PeerCraft-LocalProxy");
        proxyThread.setDaemon(true);
        proxyThread.start();

        flusherRunning = true;
        Thread flusherThread = new Thread(this::runFlusher, "PeerCraft-LocalProxy-Coalescer");
        flusherThread.setDaemon(true);
        flusherThread.start();
    }

    private void runProxy(int port) {
        // A local variable, not a field — only ever read/written by this thread, so unlike
        // serverSocket/running there's no race at all with the thread calling stop(). The
        // catch block below used to check `serverSocket == null` to tell a real error apart
        // from an intentional stop — but closeServerSocket() also nulls out serverSocket as
        // part of that same stop, so under unlucky timing an intentional stop() would
        // sometimes get logged as an ERROR.
        boolean bindSucceeded = false;
        try {
            serverSocket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
            bindSucceeded = true;
            running = true;
            boundPort = serverSocket.getLocalPort();
            LOGGER.info("[LocalProxy] Локальный TCP-прокси успешно запущен на 127.0.0.1:{}", boundPort);
            LOGGER.info("[LocalProxy] Во втором Minecraft-клиенте подключайся к адресу 127.0.0.1:{}", boundPort);

            while (running) {
                Socket clientSocket = serverSocket.accept();
                LOGGER.info("[LocalProxy] Новое подключение от Minecraft клиента: {}", clientSocket.getRemoteSocketAddress());

                long sessionId = p2pBridge.beginClientSession(clientSocket);
                handleClient(clientSocket, sessionId);
            }
        } catch (IOException e) {
            if (!bindSucceeded || running) {
                LOGGER.error("[LocalProxy] Не удалось запустить TCP-прокси на 127.0.0.1:{}. Если Minecraft пишет 'Connection refused', проверь что второй клиент запущен с -Dpeercraft.mode=client и подключается именно к этому proxyPort.", port, e);
            }
        } finally {
            running = false;
            boundPort = 0;
            closeClientSocket();
            closeServerSocket();
        }
    }

    private void handleClient(Socket socket, long sessionId) {
        this.activeClientSocket = socket;
        this.clientConnectedAtMillis = System.currentTimeMillis();
        byte[] buffer = new byte[32768];

        try {
            InputStream in = socket.getInputStream();
            this.clientOut = socket.getOutputStream();

            int bytesRead;
            while (running && (bytesRead = in.read(buffer)) != -1) {
                byte[] data = new byte[bytesRead];
                System.arraycopy(buffer, 0, data, 0, bytesRead);

                p2pBridge.sendProxyDataToP2P(sessionId, data);
            }
        } catch (Exception e) {
            LOGGER.warn("[LocalProxy] Соединение с клиентом Minecraft закрыто: {}", e.toString());
        } finally {
            closeClientSocket();
            p2pBridge.endClientSession(sessionId);
        }
    }

    public void sendToClient(byte[] data) {
        synchronized (pendingLock) {
            if (pending == null) {
                pending = new ByteArrayOutputStream(Math.max(data.length, 256));
            }
            pending.write(data, 0, data.length);
            pendingLock.notifyAll();
        }
    }

    /** Drains {@link #pending} in small batches, coalescing whatever arrived within
     * {@link #COALESCE_WINDOW_MILLIS} of each other into one write to the local socket. */
    private void runFlusher() {
        while (flusherRunning) {
            byte[] batch;
            synchronized (pendingLock) {
                while (flusherRunning && pending == null) {
                    try {
                        pendingLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!flusherRunning) {
                    return;
                }
                try {
                    pendingLock.wait(COALESCE_WINDOW_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                batch = pending != null ? pending.toByteArray() : null;
                pending = null;
            }
            if (batch != null) {
                long sinceConnect = System.currentTimeMillis() - clientConnectedAtMillis;
                if (sinceConnect < CONNECT_GRACE_MILLIS) {
                    try {
                        Thread.sleep(CONNECT_GRACE_MILLIS - sinceConnect);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                writeBatch(batch);
            }
        }
    }

    private synchronized void writeBatch(byte[] data) {
        try {
            if (clientOut != null && activeClientSocket != null && !activeClientSocket.isClosed()) {
                clientOut.write(data);
                clientOut.flush();
            }
        } catch (Exception e) {
            LOGGER.error("[LocalProxy] Ошибка отправки ответных байт клиенту MC", e);
        }
    }

    public void disconnectClient() {
        closeClientSocket();
    }

    private void closeClientSocket() {
        try {
            if (activeClientSocket != null) activeClientSocket.close();
        } catch (Exception ignored) {}
        activeClientSocket = null;
        clientOut = null;
        // Drop anything still queued for the socket that just closed — a live session's
        // bytes are never meant to bleed into whatever connects next.
        synchronized (pendingLock) {
            pending = null;
        }
    }

    private void closeServerSocket() {
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
        serverSocket = null;
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    public int getBoundPort() {
        return boundPort;
    }

    public void stop() {
        running = false;
        flusherRunning = false;
        closeClientSocket();
        closeServerSocket();
        synchronized (pendingLock) {
            pendingLock.notifyAll();
        }
    }
}
