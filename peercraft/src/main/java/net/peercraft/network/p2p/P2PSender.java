package net.peercraft.network.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;


public class P2PSender {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    private static final int MAX_UDP_PAYLOAD_SIZE = 65_507;
    private static final int SOCKET_BUFFER_SIZE_BYTES = 4 * 1024 * 1024;

    private final DatagramSocket socket;

    // Sending happens on the same socket used for receiving (P2PReceiver.getSocket()),
    // not on its own random port — otherwise the NAT mapping punched on the receiver's
    // port would be useless: traffic would actually leave from a different, never-punched
    // port, and the peer would never see it.
    public P2PSender(DatagramSocket socket) {
        this.socket = socket;
        if (socket != null) {
            try {
                socket.setSendBufferSize(SOCKET_BUFFER_SIZE_BYTES);
            } catch (Exception e) {
                LOGGER.error("[P2PSender] Не удалось увеличить буфер отправки", e);
            }
        }
    }

    public void sendData(byte[] data, String ip, int port) {
        try {
            if (data.length > MAX_UDP_PAYLOAD_SIZE) {
                LOGGER.error("[P2PSender] Пакет {} байт больше максимального UDP payload {} байт; отправка отменена, чтобы не повредить Minecraft stream", data.length, MAX_UDP_PAYLOAD_SIZE);
                return;
            }

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
        // 1. Convert ip (String) to InetAddress
        // 2. Create a DatagramPacket
        // 3. Call socket.send(...)
    }
}