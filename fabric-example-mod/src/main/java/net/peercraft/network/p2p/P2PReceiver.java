package net.peercraft.network.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketException;


import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class P2PReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final int MAX_UDP_PAYLOAD_SIZE = 65_507;

    private DatagramSocket socket;
    private volatile boolean running = false;
    private Thread listenThread;
    // Внутри класса P2PReceiver сделать статическое поле экземпляра:
    public static final P2PReceiver INSTANCE = new P2PReceiver();

    public boolean start(int port) {
        stop();
        try {
            socket = new DatagramSocket(port);
            running = true;

            listenThread = new Thread(this::listenLoop, "PeerCraft-UDP-Receiver");
            listenThread.setDaemon(true);
            listenThread.start();

            LOGGER.info("[PeerCraft Receiver] UDP сокет успешно запущен на 0.0.0.0:{}", getBoundPort());
            return true;
        } catch (SocketException e) {
            running = false;
            socket = null;
            LOGGER.error("[PeerCraft Receiver] Не удалось запустить UDP сокет на порту {}. Порт уже занят другим экземпляром PeerCraft или другой программой — задай другой -Dpeercraft.clientUdpPort/-Dpeercraft.hostUdpPort.", port, e);
            return false;
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[MAX_UDP_PAYLOAD_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (running) {
            try {
                packet.setLength(buffer.length);
                socket.receive(packet);

                P2PBridge.INSTANCE.handleIncomingPacket(
                        packet.getData(),
                        packet.getLength(),
                        packet.getAddress(),
                        packet.getPort()
                );

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