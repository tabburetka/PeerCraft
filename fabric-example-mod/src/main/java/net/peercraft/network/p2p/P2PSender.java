package net.peercraft.network.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;


public class P2PSender {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");

    private DatagramSocket socket;

    public P2PSender() {
        try {
            // new DatagramSocket() без параметров выдаёт любой случайный свободный порт
            this.socket = new DatagramSocket();
        } catch (Exception e) {
            LOGGER.error("[P2PSender] Ошибка создания сокета отправки", e);
        }
    }

    public void sendData(byte[] data, String ip, int port) {
        try {
            InetAddress ipAdrr = InetAddress.getByName(ip);
            LOGGER.info("[PeerCraft Sender] ip adres: {}", ipAdrr);
            DatagramPacket packet = new DatagramPacket(data, data.length, ipAdrr, port);

            if (socket == null || socket.isClosed()) {
                LOGGER.error("[P2PSender] Сокет не инициализирован или закрыт!");
                return;
            }

            socket.send(packet);
        } catch (UnknownHostException e) {
            LOGGER.error("[P2PSender] Неверный формат IP-адреса", e);
        } catch (java.io.IOException e) {
            LOGGER.error("[P2PSender] проблемки с отправкой пакета", e);
        }
        // 1. Преобразуй ip (String) в InetAddress
        // 2. Создай DatagramPacket
        // 3. Вызови socket.send(...)
    }
}