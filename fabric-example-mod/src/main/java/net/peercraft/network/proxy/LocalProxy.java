package net.peercraft.network.proxy;

import net.peercraft.network.p2p.P2PBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class LocalProxy {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    private ServerSocket serverSocket;
    private Socket activeClientSocket;
    private OutputStream clientOut;
    private volatile boolean running = false;
    private volatile int boundPort = 0;

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
    }

    private void runProxy(int port) {
        try {
            serverSocket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
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
            if (running || serverSocket == null) {
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
            LOGGER.warn("[LocalProxy] Соединение с клиентом Minecraft закрыто");
        } finally {
            closeClientSocket();
        }
    }

    public synchronized void sendToClient(byte[] data) {
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
        closeClientSocket();
        closeServerSocket();
    }
}
