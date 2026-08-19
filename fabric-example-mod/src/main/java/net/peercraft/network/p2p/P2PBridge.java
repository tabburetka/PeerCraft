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
import java.util.concurrent.atomic.AtomicBoolean;
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

    // На ДЖОЙНЕРЕ: активен только во время одноразового rendezvous/hole-punch flow
    // (сначала RendezvousClient, потом PunchCoordinator) — до и после неё null.
    // На ХОСТЕ: активен только во время конкретной попытки пробива (PunchCoordinator) —
    // сам постоянный RendezvousClient хоста живёт отдельно, см. hostRendezvousClient.
    // В обоих случаях, когда null, handleIncomingPacket игнорирует rendezvous-трафик
    // (не считая hostRendezvousClient) и обычный FramedPacket-путь не задет.
    private volatile RawPacketListener rendezvousListener;

    // Постоянный RendezvousClient хоста — живёт всю сессию хостинга (пока не позовут
    // cancelRendezvous() при закрытии мира, или не начнётся новая попытка хостинга), а
    // не только до первого матча. Комната переиспользуема, пока хост её держит (см.
    // RoomRegistry.ROOM_TTL_MILLIS на сервере), так что этот слот должен продолжать
    // получать трафик от сервера знакомств (и слать keepalive) даже во время и после
    // пробива конкретного джойнера — поэтому он отдельно от rendezvousListener, а не
    // делит с ним один слот (иначе beginPunch() затирал бы его при каждом матче).
    private volatile RendezvousClient hostRendezvousClient;

    // Не даёт повторному клику "Подключиться" на экране Join тихо оборвать уже идущее
    // или уже установленное присоединение через сервер знакомств — раньше повторный
    // вызов startClientViaRendezvous просто пересоздавал приёмник/отправителя поверх
    // живой сессии. true с начала попытки; сбрасывается на неудаче (см. handleFailed)
    // и когда локальный TCP-клиент реально отключается от LocalProxy (см. endClientSession).
    private final AtomicBoolean rendezvousClientBusy = new AtomicBoolean(false);

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

    // Слушатель хода регистрации комнаты на ХОСТЕ — в первую очередь нужен, чтобы
    // сообщить код комнаты вызывающему (например, показать его в чате), а не только
    // в лог. Как и ConnectListener, может звать методы из фонового потока.
    public interface HostListener {
        // changed=true means this ISN'T the room's original code — see
        // RendezvousClient.RoomCallback for why that can happen (rendezvous server
        // restart, or a long connectivity gap despite the keepalive).
        void onRoomCreated(String code, boolean changed);
        void onFailed(String reason);
    }

    private static final HostListener LOGGING_HOST_LISTENER = new HostListener() {
        @Override
        public void onRoomCreated(String code, boolean changed) {
            if (changed) {
                LOGGER.warn("[P2PBridge] Код комнаты изменился (старый больше не действителен): {}", code);
            }
        }

        @Override
        public void onFailed(String reason) {
            LOGGER.error("[P2PBridge] {}", reason);
        }
    };

    // Вызывается на ХОСТЕ вместо startHost(...), когда включён peercraft.internetPlay:
    // публикует комнату на сервере знакомств вместо использования статических
    // peerHost/peerPort, затем пробивает NAT и уже потом вызывает setTargetPeer(...).
    public void startHostViaRendezvous(int mcPort) {
        startHostViaRendezvous(mcPort, LOGGING_HOST_LISTENER);
    }

    public void startHostViaRendezvous(int mcPort, HostListener listener) {
        this.isHost = true;
        this.localMinecraftPort = mcPort;
        restartReceiver(PeerCraftConfig.hostUdpPort());

        InetAddress rendezvousAddress = resolveRendezvousAddress();
        if (rendezvousAddress == null) {
            listener.onFailed("Не удалось разрешить адрес сервера знакомств");
            return;
        }
        int rendezvousPort = PeerCraftConfig.rendezvousPort();

        LOGGER.info("[P2PBridge] Регистрируемся на сервере знакомств {}:{}...", rendezvousAddress.getHostAddress(), rendezvousPort);
        RendezvousClient client = new RendezvousClient(sender, rendezvousAddress, rendezvousPort);
        setHostRendezvousClient(client);

        client.registerRoom(
                (code, changed) -> {
                    if (changed) {
                        LOGGER.warn("[P2PBridge] Код комнаты изменился! Новый код для второго игрока: {}", code);
                    } else {
                        LOGGER.info("[P2PBridge] Комната создана! Код для второго игрока: {}", code);
                    }
                    listener.onRoomCreated(code, changed);
                },
                new RendezvousClient.MatchCallback() {
                    @Override
                    public void onMatched(RendezvousProtocol.Address peer, long token) {
                        beginPunch(peer, token);
                    }

                    @Override
                    public void onFailed(String reason) {
                        LOGGER.error("[P2PBridge] Не удалось создать комнату на сервере знакомств: {}", reason);
                        listener.onFailed("Не удалось создать комнату на сервере знакомств: " + reason);
                    }
                }
        );
    }

    // Слушатель хода присоединения через сервер знакомств — даёт вызывающему (например,
    // экрану в игре) статус в реальном времени вместо только логов. Все методы могут
    // вызываться из фонового потока (retry-поток RendezvousClient/PunchCoordinator),
    // так что вызывающий сам отвечает за маршалинг на нужный поток при необходимости.
    public interface ConnectListener {
        void onStatus(String message);
        void onConnected();
        void onFailed(String reason);
    }

    private static final ConnectListener LOGGING_LISTENER = new ConnectListener() {
        @Override
        public void onStatus(String message) {
            LOGGER.info("[P2PBridge] {}", message);
        }

        @Override
        public void onConnected() {
        }

        @Override
        public void onFailed(String reason) {
            LOGGER.error("[P2PBridge] {}", reason);
        }
    };

    // Вызывается на КЛИЕНТЕ вместо startClient(), когда включён peercraft.internetPlay:
    // присоединяется к комнате по коду (peercraft.roomCode) вместо статических
    // peerHost/peerPort, затем пробивает NAT и уже потом вызывает setTargetPeer(...).
    public void startClientViaRendezvous() {
        startClientViaRendezvous(PeerCraftConfig.roomCode(), PeerCraftConfig.rendezvousHost(), PeerCraftConfig.rendezvousPort(), LOGGING_LISTENER);
    }

    // Как startClientViaRendezvous(), но код комнаты и адрес сервера знакомств задаются
    // явно вызывающим (например, экраном в игре), а не читаются из PeerCraftConfig —
    // и о ходе присоединения сообщается через listener, а не только в лог.
    public void startClientViaRendezvous(String code, String rendezvousHost, int rendezvousPort, ConnectListener listener) {
        if (code == null || code.isBlank()) {
            listener.onFailed("код комнаты не задан — присоединяться не к чему");
            return;
        }

        // Не даём повторному клику "Подключиться" тихо оборвать уже идущее или уже
        // установленное присоединение — см. rendezvousClientBusy. Сбрасывается либо при
        // неудаче (ниже, через guardedListener), либо когда локальный клиент реально
        // отключается от мира (endClientSession).
        if (!rendezvousClientBusy.compareAndSet(false, true)) {
            listener.onFailed("Уже идёт подключение или соединение уже установлено — сначала выйдите из текущего мира, если хотите присоединиться заново.");
            return;
        }
        ConnectListener guardedListener = new ConnectListener() {
            @Override
            public void onStatus(String message) {
                listener.onStatus(message);
            }

            @Override
            public void onConnected() {
                listener.onConnected();
            }

            @Override
            public void onFailed(String reason) {
                rendezvousClientBusy.set(false);
                listener.onFailed(reason);
            }
        };

        this.isHost = false;
        // Хостивший ранее в этом же запуске (и потому остановивший LocalProxy при открытии
        // в LAN — см. OpenToLanMixin) мог выйти из мира и теперь хочет присоединиться по
        // коду: без этого 127.0.0.1:<proxyPort> оказался бы мёртвым портом на этапе
        // ConnectScreen.startConnecting(...).
        if (!isProxyRunning()) {
            startProxy(PeerCraftConfig.proxyPort());
        }
        restartReceiver(PeerCraftConfig.clientUdpPort());

        InetAddress rendezvousAddress = resolveRendezvousAddress(rendezvousHost, guardedListener);
        if (rendezvousAddress == null) {
            return;
        }

        guardedListener.onStatus("Присоединяемся к комнате " + code + " через сервер знакомств " + rendezvousAddress.getHostAddress() + ":" + rendezvousPort + "...");
        RendezvousClient client = new RendezvousClient(sender, rendezvousAddress, rendezvousPort);
        setRendezvousListener(client);

        client.joinRoom(code, new RendezvousClient.MatchCallback() {
            @Override
            public void onMatched(RendezvousProtocol.Address peer, long token) {
                beginPunch(peer, token, guardedListener);
            }

            @Override
            public void onFailed(String reason) {
                guardedListener.onFailed("Не удалось присоединиться к комнате " + code + ": " + reason);
            }
        });
    }

    // Зовётся LocalProxy, когда локальный TCP-клиент Minecraft реально отключился от
    // прокси (сессия закрыта нормально или порвана) — единственный способ, которым
    // rendezvousClientBusy может честно сброситься после УСПЕШНОГО присоединения,
    // не блокируя навсегда повторный Join после выхода из мира.
    public void endClientSession(long sessionId) {
        if (this.currentClientSession != null && this.currentClientSession.sessionId == sessionId) {
            this.currentClientSession = null;
        }
        rendezvousClientBusy.set(false);
    }

    private InetAddress resolveRendezvousAddress() {
        return resolveRendezvousAddress(PeerCraftConfig.rendezvousHost(), LOGGING_LISTENER);
    }

    private InetAddress resolveRendezvousAddress(String host, ConnectListener listener) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            listener.onFailed("Не удалось разрешить адрес сервера знакомств " + host + ": " + e.getMessage());
            return null;
        }
    }

    private void beginPunch(RendezvousProtocol.Address peer, long token) {
        beginPunch(peer, token, null);
    }

    private void beginPunch(RendezvousProtocol.Address peer, long token, ConnectListener listener) {
        LOGGER.info("[P2PBridge] Пир найден: {}:{}, начинаем hole punching...", peer.host().getHostAddress(), peer.port());
        if (listener != null) {
            listener.onStatus("Пир найден: " + peer.host().getHostAddress() + ":" + peer.port() + ", пробиваем NAT...");
        }
        PunchCoordinator punch = new PunchCoordinator(sender, peer, token, new PunchCoordinator.Callback() {
            @Override
            public void onSuccess(String ip, int port) {
                clearRendezvousListener();
                setTargetPeer(ip, port);
                LOGGER.info("[P2PBridge] P2P-соединение установлено напрямую с {}:{}", ip, port);
                if (listener != null) {
                    listener.onConnected();
                }
            }

            @Override
            public void onFailure(String reason) {
                clearRendezvousListener();
                LOGGER.error("[P2PBridge] Hole punching не удался: {}", reason);
                if (listener != null) {
                    listener.onFailed("Hole punching не удался: " + reason);
                }
            }
        });
        setRendezvousListener(punch);
        punch.start();
    }

    // Заменяет текущего rendezvousListener новым, сначала останавливая (cancel()) старого
    // — иначе его фоновые потоки (например, keepalive REGISTER у RendezvousClient) остаются
    // работать вечно без владельца. См. RawPacketListener.cancel().
    private void setRendezvousListener(RawPacketListener newListener) {
        RawPacketListener previous = this.rendezvousListener;
        this.rendezvousListener = newListener;
        if (previous != null) {
            previous.cancel();
        }
    }

    private void clearRendezvousListener() {
        setRendezvousListener(null);
    }

    // Как setRendezvousListener, но для отдельного слота постоянного RendezvousClient
    // хоста — см. поле hostRendezvousClient.
    private void setHostRendezvousClient(RendezvousClient newClient) {
        RendezvousClient previous = this.hostRendezvousClient;
        this.hostRendezvousClient = newClient;
        if (previous != null) {
            previous.cancel();
        }
    }

    // Останавливает текущую rendezvous/punch-активность — и постоянного RendezvousClient
    // хоста, и (если есть) текущую попытку пробива — без сообщения об ошибке. Вызывается,
    // когда хост закрывает свой мир (см. OpenToLanMixin.onStopServer), чтобы комната не
    // продолжала жить (и не оставалась переиспользуемой) после того, как хостить перестали.
    public void cancelRendezvous() {
        setHostRendezvousClient(null);
        clearRendezvousListener();
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
        // отдаём его активным rendezvous-слушателям и не трогаем обычный relay-путь.
        // На хосте hostRendezvousClient (трафик от сервера знакомств) и rendezvousListener
        // (трафик от конкретного пира во время пробива) могут быть активны ОДНОВРЕМЕННО —
        // оба сами фильтруют по ожидаемому адресу отправителя, так что раздать пакет
        // обоим безопасно и не создаёт путаницы.
        if (length >= 1 && data[0] == RendezvousProtocol.MAGIC) {
            RendezvousClient hostClient = this.hostRendezvousClient;
            if (hostClient != null) {
                hostClient.onPacket(data, length, senderAddress, senderPort);
            }
            RawPacketListener listener = this.rendezvousListener;
            if (listener != null) {
                listener.onPacket(data, length, senderAddress, senderPort);
            } else if (hostClient == null) {
                // Обычно безобидный запоздавший пакет от сервера знакомств/пира, пришедший
                // уже после того, как rendezvous/punch-фаза закончилась (успехом или нет) —
                // но след оставляем, иначе он пропадает совсем без объяснений.
                LOGGER.debug("[P2PBridge] Получен rendezvous-пакет от {}:{}, но ни один слушатель не активен — игнорируем", senderAddress, senderPort);
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
            LOGGER.warn("[P2PBridge] Чтение из Minecraft-сервера остановлено (сессия {}): {}", conn.sessionId, e.toString());
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
