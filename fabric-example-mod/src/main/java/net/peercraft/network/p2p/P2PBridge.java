package net.peercraft.network.p2p;

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
    // Сокет подключения к локальному MC-серверу (используется ХОСТОМ)
    private Socket targetSocket;



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
        this.isHost = true;
        this.localMinecraftPort = mcPort;

        // 1. Если ресивер уже работал (например, на 50002), глушим его и освобождаем сокет!
        if (this.receiver != null) {
            this.receiver.stop();
        }

        // 2. Поднимаем ресивер Хоста строго на 50001
        this.receiver.start(50001);

        // 3. Указываем слать ответы на 50002 (где будет сидеть Клиент)
        this.setTargetPeer("127.0.0.1", 50002);

        LOGGER.info("[P2PBridge] ХОСТ ГОТОВ: UDP слушает на 50001, ответы шлёт на 50002");
    }

    // Вызывается на КЛИЕНТЕ
    public void startClientTest() {
        this.isHost = false;
        // Запускаем UDP-приемник Клиента строго на порту 50002
        this.receiver.start(50002);

        // Жестко задаем Клиенту слать пакеты на UDP-порт Хоста (50001)
        this.setTargetPeer("127.0.0.1", 50001);

        LOGGER.info("[P2PBridge] ТЕСТ КЛИЕНТА: UDP слушает на 50002, пакеты шлет на 127.0.0.1:50001");
    }



    public void startProxy(int port) {
        if (this.proxy != null) {
            this.proxy.stop();
        }
        // Передаем текущий P2PBridge через "this"
        this.proxy = new LocalProxy(this);
        this.proxy.start(port);
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

    // Прием UDP-пакета от P2PReceiver и проброс в локальный TCP-сокет Minecraft
    public void handleIncomingPacket(byte[] data, int length, InetAddress senderAddress, int senderPort) {
        if (this.isHost) {
            // --- ЛОГИКА ХОСТА ---
            try {
                if (this.targetSocket == null || this.targetSocket.isClosed()) {
                    connectToLocalMinecraft();
                }
                if (this.targetSocket != null && !this.targetSocket.isClosed()) {
                    OutputStream out = this.targetSocket.getOutputStream();
                    out.write(data, 0, length); // Используем length!
                    out.flush();
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
        byte[] buffer = new byte[2048];
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