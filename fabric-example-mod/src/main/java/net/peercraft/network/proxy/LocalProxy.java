package net.peercraft.network.proxy;

import net.peercraft.network.p2p.P2PBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class LocalProxy {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    private ServerSocket serverSocket;
    private Socket activeClientSocket;
    private OutputStream clientOut;
    private volatile boolean running = false;

    // 1. Объявляем поле для ссылки на P2PBridge
    private final P2PBridge p2pBridge;


    // 2. Передаем P2PBridge в конструктор
    public LocalProxy(P2PBridge p2pBridge) {
        this.p2pBridge = p2pBridge;
    }


    public void start(int port) {
        if (running) return;
        running = true;

        Thread proxyThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                LOGGER.info("[LocalProxy] Локальный TCP-прокси успешно запущен на 127.0.0.1:{}", port);

                while (running) {
                    Socket clientSocket = serverSocket.accept();
                    LOGGER.info("[LocalProxy] Новое подключение от Minecraft клиента: " + clientSocket.getRemoteSocketAddress());

// Сохраняем сокет в P2PBridge, чтобы отправлять в него входящие ответы от Хоста!
                    p2pBridge.registerClientSocket(clientSocket);
                    handleClient(clientSocket);
                }
            } catch (Exception e) {
                if (running) {
                    LOGGER.error("[LocalProxy] Ошибка в работе TCP-прокси", e);
                }
            }
        }, "PeerCraft-LocalProxy");

        proxyThread.setDaemon(true);
        proxyThread.start();
    }

    private void handleClient(Socket socket) {
        this.activeClientSocket = socket;
        byte[] buffer = new byte[2048];

        try {
            InputStream in = socket.getInputStream();
            this.clientOut = socket.getOutputStream();

            int bytesRead;
            // Читаем пакеты от Minecraft Клиента и отправляем их Хосту по UDP
            while (running && (bytesRead = in.read(buffer)) != -1) {
                byte[] data = new byte[bytesRead];
                System.arraycopy(buffer, 0, data, 0, bytesRead);

                p2pBridge.sendProxyDataToP2P(data);
            }
        } catch (Exception e) {
            LOGGER.warn("[LocalProxy] Соединение с клиентом Minecraft закрыто");
        } finally {
            closeClientSocket();
        }
    }

    // Метод для записи ответных байт (пришедших по UDP от Хоста) обратно в клиент Minecraft
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

    private void closeClientSocket() {
        try {
            if (activeClientSocket != null) activeClientSocket.close();
        } catch (Exception ignored) {}
        activeClientSocket = null;
        clientOut = null;
    }

    public void stop() {
        running = false;
        closeClientSocket();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
    }
}