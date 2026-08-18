package net.peercraft.network.p2p;

import net.peercraft.config.PeerCraftConfig;
import net.peercraft.network.proxy.LocalProxy;
import net.peercraft.network.rendezvous.PunchCoordinator;
import net.peercraft.network.rendezvous.RendezvousClient;
import net.peercraft.network.rendezvous.RendezvousProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public class P2PBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("peercraft");
    public static final P2PBridge INSTANCE = new P2PBridge();

    // How many recently-sent DATA packets we keep around per connection so a NACK can
    // be honored by resending the exact original bytes. LAN-appropriate default; revisit
    // once this runs over real (higher-latency, lossier) internet P2P.
    private static final int RETRANSMIT_BUFFER_CAPACITY = 256;

    // Some local network stacks/firewalls silently drop UDP datagrams above a certain size on
    // certain ports (empirically found: ~16.3KB on this dev machine's default PeerCraft ports,
    // even though the same size sails through on other ports and MAX_UDP_PAYLOAD_SIZE=65507 is
    // never hit) — retrying a dropped oversized datagram doesn't help since the drop is
    // deterministic, not random loss. Rather than depend on a specific local threshold (which
    // will vary by machine/NAT/router once this is real internet P2P), never send a chunk larger
    // than this — well under every size limit observed, real or firewall-imposed.
    private static final int MAX_CHUNK_SIZE = 8000;

    private P2PReceiver receiver;
    private P2PSender sender;
    private LocalProxy proxy;

    private String targetIp;
    private int targetPort;
    private int localMinecraftPort;

    private boolean isHost = false;

    // Каждое новое логическое TCP-подключение (или переподключение) получает
    // новую сессию; старая атомарно заменяется, а не мутируется на месте,
    // чтобы поток предыдущей сессии не мог затереть состояние новой.
    private volatile HostConnection currentHostConnection;
    private volatile ClientSession currentClientSession;

    // Активно только во время rendezvous/hole-punch фазы (RendezvousClient, затем
    // PunchCoordinator) — до и после неё null, и handleIncomingPacket идёт по обычному
    // FramedPacket-пути. См. startHostViaRendezvous/startClientViaRendezvous.
    private volatile RawPacketListener rendezvousListener;

    private P2PBridge() {
        this.receiver = new P2PReceiver();
        this.sender = new P2PSender(null);
    }

    public void startHost(int mcPort) {
        int hostUdpPort = PeerCraftConfig.hostUdpPort();
        int peerPort = PeerCraftConfig.peerPortForHost();
        String peerHost = PeerCraftConfig.peerHost();
        this.isHost = true;
        this.localMinecraftPort = mcPort;

        restartReceiver(hostUdpPort);
        this.setTargetPeer(peerHost, peerPort);

        LOGGER.info("[P2PBridge] ХОСТ ГОТОВ: LAN порт MC {}, UDP слушает на {}, ответы шлёт на {}:{}", mcPort, receiver.getBoundPort(), peerHost, peerPort);
    }

    // Вызывается на КЛИЕНТЕ
    public void startClient() {
        int clientUdpPort = PeerCraftConfig.clientUdpPort();
        int peerPort = PeerCraftConfig.peerPortForClient();
        String peerHost = PeerCraftConfig.peerHost();
        this.isHost = false;

        restartReceiver(clientUdpPort);
        this.setTargetPeer(peerHost, peerPort);

        LOGGER.info("[P2PBridge] КЛИЕНТ ГОТОВ: UDP слушает на {}, пакеты шлёт на {}:{}", receiver.getBoundPort(), peerHost, peerPort);
    }

    // Вызывается на ХОСТЕ вместо startHost(...), когда включён peercraft.internetPlay:
    // публикует комнату на сервере знакомств вместо использования статических
    // peerHost/peerPort, затем пробивает NAT и уже потом вызывает setTargetPeer(...).
    public void startHostViaRendezvous(int mcPort) {
        this.isHost = true;
        this.localMinecraftPort = mcPort;
        restartReceiver(PeerCraftConfig.hostUdpPort());

        InetAddress rendezvousAddress = resolveRendezvousAddress();
        if (rendezvousAddress == null) {
            return;
        }
        int rendezvousPort = PeerCraftConfig.rendezvousPort();

        LOGGER.info("[P2PBridge] Регистрируемся на сервере знакомств {}:{}...", rendezvousAddress.getHostAddress(), rendezvousPort);
        RendezvousClient client = new RendezvousClient(sender, rendezvousAddress, rendezvousPort);
        this.rendezvousListener = client;

        client.registerRoom(
                code -> LOGGER.info("[P2PBridge] Комната создана! Код для второго игрока: {}", code),
                new RendezvousClient.MatchCallback() {
                    @Override
                    public void onMatched(RendezvousProtocol.Address peer, long token) {
                        beginPunch(peer, token);
                    }

                    @Override
                    public void onFailed(String reason) {
                        LOGGER.error("[P2PBridge] Не удалось создать комнату на сервере знакомств: {}", reason);
                    }
                }
        );
    }

    // Вызывается на КЛИЕНТЕ вместо startClient(), когда включён peercraft.internetPlay:
    // присоединяется к комнате по коду (peercraft.roomCode) вместо статических
    // peerHost/peerPort, затем пробивает NAT и уже потом вызывает setTargetPeer(...).
    public void startClientViaRendezvous() {
        String code = PeerCraftConfig.roomCode();
        if (code.isBlank()) {
            LOGGER.error("[P2PBridge] internetPlay включён, но -Dpeercraft.roomCode не задан — присоединяться не к чему");
            return;
        }

        this.isHost = false;
        restartReceiver(PeerCraftConfig.clientUdpPort());

        InetAddress rendezvousAddress = resolveRendezvousAddress();
        if (rendezvousAddress == null) {
            return;
        }
        int rendezvousPort = PeerCraftConfig.rendezvousPort();

        LOGGER.info("[P2PBridge] Присоединяемся к комнате {} через сервер знакомств {}:{}...", code, rendezvousAddress.getHostAddress(), rendezvousPort);
        RendezvousClient client = new RendezvousClient(sender, rendezvousAddress, rendezvousPort);
        this.rendezvousListener = client;

        client.joinRoom(code, new RendezvousClient.MatchCallback() {
            @Override
            public void onMatched(RendezvousProtocol.Address peer, long token) {
                beginPunch(peer, token);
            }

            @Override
            public void onFailed(String reason) {
                LOGGER.error("[P2PBridge] Не удалось присоединиться к комнате {}: {}", code, reason);
            }
        });
    }

    private InetAddress resolveRendezvousAddress() {
        String host = PeerCraftConfig.rendezvousHost();
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            LOGGER.error("[P2PBridge] Не удалось разрешить адрес сервера знакомств {}", host, e);
            return null;
        }
    }

    private void beginPunch(RendezvousProtocol.Address peer, long token) {
        LOGGER.info("[P2PBridge] Пир найден: {}:{}, начинаем hole punching...", peer.host().getHostAddress(), peer.port());
        PunchCoordinator punch = new PunchCoordinator(sender, peer, token, new PunchCoordinator.Callback() {
            @Override
            public void onSuccess(String ip, int port) {
                rendezvousListener = null;
                setTargetPeer(ip, port);
                LOGGER.info("[P2PBridge] P2P-соединение установлено напрямую с {}:{}", ip, port);
            }

            @Override
            public void onFailure(String reason) {
                rendezvousListener = null;
                LOGGER.error("[P2PBridge] Hole punching не удался: {}", reason);
            }
        });
        this.rendezvousListener = punch;
        punch.start();
    }

    private void restartReceiver(int port) {
        if (this.receiver != null) {
            this.receiver.stop();
        }
        this.receiver = new P2PReceiver();
        this.receiver.start(port);
        // Пересоздаём отправителя на том же сокете, что и приёмник — см. комментарий
        // в P2PSender. Без этого пробитый hole punching NAT-маппинг был бы бесполезен.
        this.sender = new P2PSender(this.receiver.getSocket());
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

    // Вызывается LocalProxy при каждом новом входящем TCP-подключении от клиентского MC.
    // Возвращает id новой сессии, который нужно передавать во все последующие
    // sendProxyDataToP2P(...) для этого TCP-соединения.
    public long beginClientSession(Socket socket) {
        long sessionId = ThreadLocalRandom.current().nextLong();
        this.currentClientSession = new ClientSession(sessionId);
        LOGGER.info("[P2PBridge] Новая клиентская сессия {} для {}", sessionId, socket.getRemoteSocketAddress());
        return sessionId;
    }

    // Отправка данных от LocalProxy (Клиента) в P2P-сеть по UDP
    public void sendProxyDataToP2P(long sessionId, byte[] data) {
        ClientSession session = this.currentClientSession;
        if (session == null || session.sessionId != sessionId) {
            LOGGER.warn("[P2PBridge] Отмена отправки: клиентская сессия {} больше не активна", sessionId);
            return;
        }
        sendChunked(sessionId, session.outSeq, data, session.sentPackets);
    }

    private void sendEncoded(byte[] framed) {
        if (targetIp == null || targetPort <= 0) {
            LOGGER.warn("[P2PBridge] Отмена отправки: целевой пир ещё не задан!");
            return;
        }
        if (sender == null) {
            LOGGER.error("[P2PBridge] Отмена отправки: P2PSender не инициализирован!");
            return;
        }
        sender.sendData(framed, targetIp, targetPort);
    }

    private void sendFramed(long sessionId, long seq, byte flags, byte[] data) {
        sendEncoded(FramedPacket.encode(sessionId, seq, flags, data));
    }

    // Как sendFramed, но дополнительно сохраняет закодированный пакет в буфере
    // ретрансляции соединения — так по NACK можно переслать оригинальные байты заново.
    private void sendFramedAndBuffer(long sessionId, long seq, byte flags, byte[] data, Map<Long, byte[]> retransmitBuffer) {
        byte[] framed = FramedPacket.encode(sessionId, seq, flags, data);
        synchronized (retransmitBuffer) {
            retransmitBuffer.put(seq, framed);
        }
        sendEncoded(framed);
    }

    // Режет один TCP-read на несколько UDP-датаграмм не крупнее MAX_CHUNK_SIZE, каждая со
    // своим следующим seq. Приёмный ReorderBuffer уже умеет прозрачно склеивать соседние seq
    // обратно в один кусок, так что получателю никаких изменений не требуется.
    private void sendChunked(long sessionId, AtomicLong outSeq, byte[] data, Map<Long, byte[]> retransmitBuffer) {
        int offset = 0;
        do {
            int len = Math.min(MAX_CHUNK_SIZE, data.length - offset);
            byte[] chunk = Arrays.copyOfRange(data, offset, offset + len);
            sendFramedAndBuffer(sessionId, outSeq.getAndIncrement(), (byte) 0, chunk, retransmitBuffer);
            offset += len;
        } while (offset < data.length);
    }

    private void sendNack(long sessionId, long missingSeq) {
        sendEncoded(FramedPacket.encodeNack(sessionId, missingSeq));
    }

    private void resendIfBuffered(long sessionId, long seq, Map<Long, byte[]> retransmitBuffer) {
        byte[] framed;
        synchronized (retransmitBuffer) {
            framed = retransmitBuffer.get(seq);
        }
        if (framed != null) {
            LOGGER.info("[P2PBridge] Повторно отправляем seq={} для сессии {} по NACK", seq, sessionId);
            sendEncoded(framed);
        } else {
            LOGGER.debug("[P2PBridge] NACK на seq={} для сессии {}, но пакет уже вытеснен из буфера ретрансляции", seq, sessionId);
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

    // Приём UDP-пакета от P2PReceiver: снимаем framing и пробрасываем в локальный TCP-сокет Minecraft
    public void handleIncomingPacket(byte[] data, int length, InetAddress senderAddress, int senderPort) {
        // Rendezvous/punch-трафик узнаётся по своему magic-байту (FramedPacket.decode
        // в любом случае отверг бы его, т.к. первый байт не совпадает с VERSION) —
        // отдаём его текущему rendezvous-слушателю и не трогаем обычный relay-путь.
        if (length >= 1 && data[0] == RendezvousProtocol.MAGIC) {
            RawPacketListener listener = this.rendezvousListener;
            if (listener != null) {
                listener.onPacket(data, length, senderAddress, senderPort);
            }
            return;
        }

        FramedPacket frame = FramedPacket.decode(data, length);
        if (frame == null) {
            LOGGER.warn("[P2PBridge] Отброшен пакет: не удалось разобрать заголовок ({} байт)", length);
            return;
        }

        if (this.isHost) {
            handleHostIncoming(frame);
        } else {
            handleClientIncoming(frame);
        }
    }

    private synchronized void handleHostIncoming(FramedPacket frame) {
        HostConnection conn = this.currentHostConnection;

        if (frame.type() == FramedPacket.TYPE_NACK) {
            if (conn != null && conn.sessionId == frame.sessionId()) {
                resendIfBuffered(conn.sessionId, frame.nackSeq(), conn.sentPackets);
            }
            return;
        }

        if (conn == null || conn.sessionId != frame.sessionId()) {
            if (frame.seq() != 0) {
                // Пакет не начала новой сессии — не подхватываем середину чужого/устаревшего потока.
                LOGGER.debug("[P2PBridge] Отброшен пакет чужой/устаревшей сессии {} (seq={})", frame.sessionId(), frame.seq());
                return;
            }
            conn = startNewHostConnection(frame.sessionId());
            if (conn == null) {
                return;
            }
        }

        deliverToHost(conn, frame);
    }

    private HostConnection startNewHostConnection(long sessionId) {
        HostConnection previous = this.currentHostConnection;
        if (previous != null) {
            previous.active = false;
            closeQuietly(previous.mcSocket);
        }

        try {
            LOGGER.info("[P2PBridge] Подключаемся к локальному MC серверу 127.0.0.1:{} (сессия {})...", localMinecraftPort, sessionId);
            Socket mcSocket = new Socket("127.0.0.1", localMinecraftPort);
            HostConnection conn = new HostConnection(sessionId, mcSocket);
            this.currentHostConnection = conn;

            Thread readerThread = new Thread(() -> listenMcResponses(conn), "PeerCraft-MC-Reader");
            readerThread.setDaemon(true);
            readerThread.start();

            LOGGER.info("[P2PBridge] УСПЕШНО подключились к локальному MC серверу (сессия {})!", sessionId);
            return conn;
        } catch (IOException e) {
            LOGGER.error("[P2PBridge] Не удалось подключиться к локальному MC серверу на порту {}", localMinecraftPort, e);
            return null;
        }
    }

    private void deliverToHost(HostConnection conn, FramedPacket frame) {
        try {
            ReorderBuffer.Result result = conn.inBuf.accept(frame.seq(), frame.payload());
            if (result.deliverable != null && result.deliverable.length > 0) {
                conn.mcOut.write(result.deliverable);
                conn.mcOut.flush();
            }
            if (result.requestSeq != null) {
                sendNack(conn.sessionId, result.requestSeq);
                LOGGER.info("[P2PBridge] Запросили повторную отправку seq={} для сессии {}", result.requestSeq, conn.sessionId);
            }
        } catch (ReorderBuffer.SessionBrokenException e) {
            LOGGER.error("[P2PBridge] Сессия {} повреждена: {} — закрываем соединение с MC", conn.sessionId, e.getMessage());
            closeHostConnection(conn);
        } catch (IOException e) {
            LOGGER.error("[P2PBridge] Ошибка проброса байт в MC-сервер", e);
            closeHostConnection(conn);
        }
    }

    private synchronized void handleClientIncoming(FramedPacket frame) {
        ClientSession session = this.currentClientSession;

        if (frame.type() == FramedPacket.TYPE_NACK) {
            if (session != null && session.sessionId == frame.sessionId()) {
                resendIfBuffered(session.sessionId, frame.nackSeq(), session.sentPackets);
            }
            return;
        }

        if (session == null || session.sessionId != frame.sessionId()) {
            LOGGER.debug("[P2PBridge] Отброшен ответ чужой/устаревшей сессии {} (seq={})", frame.sessionId(), frame.seq());
            return;
        }

        try {
            ReorderBuffer.Result result = session.inBuf.accept(frame.seq(), frame.payload());
            if (result.deliverable != null && result.deliverable.length > 0 && this.proxy != null) {
                this.proxy.sendToClient(result.deliverable);
            }
            if (result.requestSeq != null) {
                sendNack(session.sessionId, result.requestSeq);
                LOGGER.info("[P2PBridge] Запросили повторную отправку seq={} для клиентской сессии {}", result.requestSeq, session.sessionId);
            }
        } catch (ReorderBuffer.SessionBrokenException e) {
            LOGGER.error("[P2PBridge] Клиентская сессия {} повреждена: {} — закрываем соединение с локальным MC-клиентом", session.sessionId, e.getMessage());
            if (this.currentClientSession == session) {
                this.currentClientSession = null;
            }
            if (this.proxy != null) {
                this.proxy.disconnectClient();
            }
        }
    }

    private void listenMcResponses(HostConnection conn) {
        byte[] buffer = new byte[32768];
        InputStream mcIn = conn.mcIn;
        try {
            int bytesRead;
            while (conn.active && (bytesRead = mcIn.read(buffer)) != -1) {
                byte[] payload = new byte[bytesRead];
                System.arraycopy(buffer, 0, payload, 0, bytesRead);

                LOGGER.info("[P2PBridge] Получен ответ от Minecraft-сервера ({} байт, сессия {}). Отправляем по UDP...", bytesRead, conn.sessionId);
                sendChunked(conn.sessionId, conn.outSeq, payload, conn.sentPackets);
            }
        } catch (Exception e) {
            LOGGER.warn("[P2PBridge] Чтение из Minecraft-сервера остановлено (сессия {})", conn.sessionId);
        } finally {
            sendFramed(conn.sessionId, conn.outSeq.getAndIncrement(), (byte) FramedPacket.FLAG_FIN, new byte[0]);
            closeHostConnection(conn);
        }
    }

    // Закрывает соединение, только если оно всё ещё "текущее" — устаревший поток от
    // уже заменённого HostConnection не может затереть состояние более нового.
    private synchronized void closeHostConnection(HostConnection conn) {
        conn.active = false;
        closeQuietly(conn.mcSocket);
        if (this.currentHostConnection == conn) {
            this.currentHostConnection = null;
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    public int getReceiverPort() {
        return receiver != null ? receiver.getBoundPort() : 0;
    }

    public void stop() {
        HostConnection conn = this.currentHostConnection;
        if (conn != null) {
            closeHostConnection(conn);
        }
        this.currentClientSession = null;
        if (receiver != null) receiver.stop();
        if (proxy != null) proxy.stop();
        LOGGER.info("[P2PBridge] Мост и ресурсы остановлены.");
    }

    private static Map<Long, byte[]> newRetransmitBuffer() {
        return new LinkedHashMap<Long, byte[]>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                return size() > RETRANSMIT_BUFFER_CAPACITY;
            }
        };
    }

    private static final class HostConnection {
        final long sessionId;
        final Socket mcSocket;
        final OutputStream mcOut;
        final InputStream mcIn;
        final ReorderBuffer inBuf = new ReorderBuffer();
        final AtomicLong outSeq = new AtomicLong(0);
        final Map<Long, byte[]> sentPackets = newRetransmitBuffer();
        volatile boolean active = true;

        HostConnection(long sessionId, Socket mcSocket) throws IOException {
            this.sessionId = sessionId;
            this.mcSocket = mcSocket;
            this.mcOut = mcSocket.getOutputStream();
            this.mcIn = mcSocket.getInputStream();
        }
    }

    private static final class ClientSession {
        final long sessionId;
        final ReorderBuffer inBuf = new ReorderBuffer();
        final AtomicLong outSeq = new AtomicLong(0);
        final Map<Long, byte[]> sentPackets = newRetransmitBuffer();

        ClientSession(long sessionId) {
            this.sessionId = sessionId;
        }
    }
}
