package net.peercraft.network.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketException;


import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class P2PReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    private DatagramSocket socket;
    private volatile boolean running = false;
    private Thread listenThread;
    // Внутри класса P2PReceiver сделать статическое поле экземпляра:
    public static final P2PReceiver INSTANCE = new P2PReceiver();
    public void start(int port) {

        try {
            socket = new DatagramSocket(port);
            running = true;

            int boundPort = this.socket.getLocalPort();

            listenThread = new Thread(this::listenLoop, "PeerCraft-UDP-Receiver");
            listenThread.setDaemon(true);
            listenThread.start();

            LOGGER.info("[PeerCraft Receiver] Сокет успешно запущен на порту {}", port);
        } catch (SocketException e) {
            LOGGER.error("[PeerCraft Receiver] Не удалось запустить UDP сокет на порту {}", port, e);
            // Здесь можно обработать ошибку (например, закрыть программу или поменять порт)
        }

    }

    private void listenLoop() {
        // Буфер для входящих сетевых пакетов Minecraft
        byte[] buffer = new byte[1400];
        DatagramPacket packet = new DatagramPacket(buffer,buffer.length);
        while (running) {
            try {
                // Создай DatagramPacket(buffer, buffer.length)

                // Вызови socket.receive(packet)
                socket.receive(packet);

                P2PBridge.INSTANCE.handleIncomingPacket(
                        packet.getData(),
                        packet.getLength(),
                        packet.getAddress(),
                        packet.getPort()
                );

                // Для теста выведи в лог, сколько байт пришло и от кого!
                LOGGER.info("[P2PReceiver] Получено {} байт от {}:{}", packet.getLength(), packet.getAddress(), packet.getPort());

            } catch (Exception e) {
                if (running) {
                    LOGGER.error("[P2PReceiver] Ошибка чтения UDP пакета", e);
                }
            }
        }
    }

    public int getBoundPort() {
        return (this.socket != null && !this.socket.isClosed()) ? this.socket.getLocalPort() : 0;
    }
    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}