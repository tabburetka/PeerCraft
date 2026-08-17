package net.peercraft.network.p2p;

import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.proxy.LocalProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;

public class P2PBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    public static final P2PBridge INSTANCE = new P2PBridge();

    private P2PReceiver receiver;
    private P2PSender sender;
    private LocalProxy proxy;

    private String targetIp;
    private int targetPort;
    private int localMinecraftPort;
    private Socket mcSocket;
    private OutputStream mcOut;
    private InputStream mcIn;
    private volatile boolean isConnectedToMc = false;

    private boolean isHost = false;
    private Socket clientProxySocket;

    private P2PBridge() {
        this.sender = new P2PSender();
        this.receiver = new P2PReceiver();
    }

//    public void startHost(int mcPort) {
//        this.localMinecraftPort = mcPort;
//        this.receiver.start(0);
//        LOGGER.info("[P2PBridge] Хост запущен! LAN порт MC: {}, UDP порт P2P: {}", mcPort, receiver.getBoundPort());
//    }


    public void startHost(int mcPort) {
        int hostUdpPort = PeerCraftConfig.hostUdpPort();
        int peerPort = PeerCraftConfig.peerPortForHost();
        String peerHost = PeerCraftConfig.peerHost();
        this.isHost = true;
        this.localMinecraftPort = mcPort;

        if (!restartReceiver(hostUdpPort)) {
            LOGGER.error("[P2PBridge] ХОСТ НЕ ЗАПУЩЕН: UDP порт {} занят. Освободи порт или задай другой -Dpeercraft.hostUdpPort.", hostUdpPort);
            return;
        }
        this.setTargetPeer(peerHost, peerPort);

        LOGGER.info("[P2PBridge] ХОСТ ГОТОВ: LAN порт MC {}, UDP слушает на {}, ответы шлёт на {}:{}", mcPort, receiver.getBoundPort(), peerHost, peerPort);
    }

    // Вызывается на КЛИЕНТЕ
    public void startClient() {
        int clientUdpPort = PeerCraftConfig.clientUdpPort();
        int peerPort = PeerCraftConfig.peerPortForClient();
        String peerHost = PeerCraftConfig.peerHost();
        this.isHost = false;

        if (!restartReceiver(clientUdpPort)) {
            LOGGER.error("[P2PBridge] КЛИЕНТ НЕ ЗАПУЩЕН: UDP порт {} занят. Освободи порт или задай другой -Dpeercraft.clientUdpPort.", clientUdpPort);
            return;
        }
        this.setTargetPeer(peerHost, peerPort);

        LOGGER.info("[P2PBridge] КЛИЕНТ ГОТОВ: UDP слушает на {}, пакеты шлёт на {}:{}", receiver.getBoundPort(), peerHost, peerPort);
    }

    private boolean restartReceiver(int port) {
        if (this.receiver != null) {
            this.receiver.stop();
        }
        this.receiver = new P2PReceiver();
        return this.receiver.start(port);
    }



    public void startProxy(int port) {
        if (this.proxy != null) {
            this.proxy.stop();
        }
        this.proxy = new LocalProxy(this);
        this.proxy.start(port);
        LOGGER.info("[P2PBridge] Запрошен запуск локального TCP-прокси на 127.0.0.1:{}", port);
    }

    public void setTargetPeer(String ip, int port) {
        this.targetIp = ip;
        this.targetPort = port;
        LOGGER.info("[P2PBridge] Назначен целевой пир: {}:{}", ip, port);
    }

    // Отправка данных от LocalProxy (Клиента) в P2P-сеть по UDP
    public void sendProxyDataToP2P(byte[] data) {
        if (targetIp == null || targetPort <= 0) {
            LOGGER.warn("[P2PBridge] Отмена отправки: целевой пир ещё не задан!");
            return;
        }
        if (sender == null) {
            LOGGER.error("[P2PBridge] Отмена отправки: P2PSender не инициализирован!");
            return;
        }
        sender.sendData(data, targetIp, targetPort);
    }

    // Внутри класса P2PBridge.java:

    // Метод для пересылки входящих UDP-пакетов от Хоста обратно в LocalProxy Клиента
    public void handleIncomingProxyResponse(byte[] data, int length) {
        if (this.proxy != null) {
            byte[] payload = new byte[length];
            System.arraycopy(data, 0, payload, 0, length);
            this.proxy.sendToClient(payload);
            LOGGER.info("[P2PBridge] Ответ от Хоста ({} байт) передан в LocalProxy Клиента", length);
        }
    }

    public LocalProxy getProxy() {
        return this.proxy;
    }

    public boolean isProxyRunning() {
        return this.proxy != null && this.proxy.isRunning();
    }

    public int getProxyPort() {
        return this.proxy != null ? this.proxy.getBoundPort() : 0;
    }

    // Прием UDP-пакета от P2PReceiver и проброс в локальный TCP-сокет Minecraft
    public void handleIncomingPacket(byte[] data, int length, InetAddress senderAddress, int senderPort) {
        if (this.isHost) {
            // --- ЛОГИКА ХОСТА ---
            try {
                if (this.mcSocket == null || this.mcSocket.isClosed() || !this.isConnectedToMc) {
                    connectToLocalMinecraft();
                }
                if (this.mcOut != null && this.mcSocket != null && !this.mcSocket.isClosed()) {
                    mcOut.write(data, 0, length);
                    mcOut.flush();
                }
            } catch (IOException e) {
                LOGGER.error("[P2PBridge] Ошибка проброса байт в MC-сервер", e);
            }
        } else {
            // --- ЛОГИКА КЛИЕНТА ---
            try {
                if (this.clientProxySocket != null && !this.clientProxySocket.isClosed()) {
                    OutputStream out = this.clientProxySocket.getOutputStream();
                    out.write(data, 0, length); // Используем length!
                    out.flush();
                } else {
                    LOGGER.warn("[P2PBridge] Некуда переслать ответ: clientProxySocket ещё не подключен!");
                }
            } catch (IOException e) {
                LOGGER.error("[P2PBridge] Ошибка отправки байт в клиентский MC", e);
            }
        }
    }

    private void connectToLocalMinecraft() {
        try {
            LOGGER.info("[P2PBridge] Подключаемся к локальному MC серверу 127.0.0.1:{}...", localMinecraftPort);
            this.mcSocket = new Socket("127.0.0.1", localMinecraftPort);
            this.mcOut = mcSocket.getOutputStream();
            this.mcIn = mcSocket.getInputStream();
            this.isConnectedToMc = true;

            Thread readerThread = new Thread(this::listenMcResponses, "PeerCraft-MC-Reader");
            readerThread.setDaemon(true);
            readerThread.start();

            LOGGER.info("[P2PBridge] УСПЕШНО подключились к локальному MC серверу!");

        } catch (Exception e) {
            LOGGER.error("[P2PBridge] Не удалось подключиться к локальному MC серверу на порту {}", localMinecraftPort, e);
            resetMcConnection();
        }
    }

    private void listenMcResponses() {
        byte[] buffer = new byte[32768];
        try {
            int bytesRead;
            while (isConnectedToMc && (bytesRead = mcIn.read(buffer)) != -1) {
                byte[] payload = new byte[bytesRead];
                System.arraycopy(buffer, 0, payload, 0, bytesRead);

                LOGGER.info("[P2PBridge] Получен ответ от Minecraft-сервера ({} байт). Отправляем по UDP...", bytesRead);

                if (targetIp != null && targetPort > 0) {
                    sender.sendData(payload, targetIp, targetPort);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[P2PBridge] Чтение из Minecraft-сервера остановлено");
        } finally {
            resetMcConnection();
        }
    }

    private void resetMcConnection() {
        isConnectedToMc = false;
        try {
            if (mcSocket != null && !mcSocket.isClosed()) {
                mcSocket.close();
            }
        } catch (Exception ignored) {}
        mcSocket = null;
        mcOut = null;
        mcIn = null;
    }

    public int getReceiverPort() {
        return receiver != null ? receiver.getBoundPort() : 0;
    }

    public void registerClientSocket(Socket socket) {
        this.clientProxySocket = socket;
    }

    public void stop() {
        resetMcConnection();
        if (receiver != null) receiver.stop();
        if (proxy != null) proxy.stop();
        LOGGER.info("[P2PBridge] Мост и ресурсы остановлены.");
    }
}