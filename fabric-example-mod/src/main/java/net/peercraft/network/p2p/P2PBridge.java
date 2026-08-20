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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    // Только для роли ДЖОЙНЕРА — джойнер всегда общается ровно с одним хостом.
    private volatile PeerAddress clientTargetPeer;
    private int localMinecraftPort;

    private boolean isHost = false;

    // Максимум игроков в комнате — задаётся хостом в startHostViaRendezvous(...).
    // Статический локальный путь (startHost) остаётся однопировым по конструкции (это
    // dev-only путь для тестирования на одной машине, не путь реальной фичи), значение
    // здесь для него не актуально.
    private volatile int maxPlayers = 1;

    // ХОСТ: одно TCP-подключение к локальному IntegratedServer НА КАЖДОГО джойнера, а не
    // одно на весь мост — иначе второй игрок обрывал бы первого (см. handleHostIncoming).
    // Обе карты индексируют один и тот же набор HostConnection: по sessionId (канонический
    // ключ доверия, как и раньше) и по адресу пира (быстрый допуск новых подключений +
    // живой учёт числа игроков для currentPlayerCount). Мутации обеих карт происходят
    // только внутри synchronized-методов (handleHostIncoming/closeHostConnection), как и
    // раньше для одиночного currentHostConnection; ConcurrentHashMap — чтобы чтение (без
    // захвата того же лока) из sendChunked/listenMcResponses и currentPlayerCount() было
    // безопасным.
    private final Map<Long, HostConnection> hostConnectionsBySessionId = new ConcurrentHashMap<>();
    private final Map<PeerAddress, HostConnection> hostConnectionsByAddress = new ConcurrentHashMap<>();

    // ХОСТ: адреса, для которых hole punching реально удался — только такой адрес может
    // открыть HostConnection (см. handleHostIncoming). Раньше такого допускного контроля
    // не было вообще: любой UDP-отправитель, угадавший relay-порт хоста и слепивший фрейм
    // с seq==0, получал живое TCP-подключение к локальному MC-серверу. Никогда не убирается
    // при отключении (см. closeHostConnection) — намеренно, чтобы быстрый реконнект того же
    // игрока не требовал повторного пробива NAT; из-за этого НЕ используется для подсчёта
    // текущего числа игроков (см. currentPlayerCount()) — для этого используется живая
    // hostConnectionsByAddress.
    private final Set<PeerAddress> authorizedPeers = ConcurrentHashMap.newKeySet();

    // ХОСТ (Фаза 5): accountId джойнера по PeerAddress, если джойнер был залогинен при входе
    // — заполняется в beginHostPunch (пришло вместе с PEER_FOUND), читается в
    // startNewHostConnection, чтобы связать accountId с локальным портом сокета для
    // PlayerIdentityRegistry (см. ServerLoginNetworkHandlerMixin). Как и authorizedPeers,
    // никогда не убирается при отключении одного джойнера — только целиком при
    // cancelRendezvous(), той же причине: быстрый реконнект того же игрока не должен терять
    // привязку к аккаунту без нового пробива NAT.
    private final Map<PeerAddress, java.util.UUID> joinerAccountIdByAddress = new ConcurrentHashMap<>();

    // ХОСТ: активные попытки пробива NAT, по одной на джойнера — НЕ отменяют друг друга
    // при добавлении новой (в отличие от rendezvousListener ниже, который остаётся
    // единственным слотом только для джойнерской роли, где одновременно возможна лишь
    // одна попытка).
    private final Map<PeerAddress, PunchCoordinator> activePunches = new ConcurrentHashMap<>();

    private volatile ClientSession currentClientSession;

    // Только для роли ДЖОЙНЕРА: активен во время одноразового rendezvous/hole-punch flow
    // (сначала RendezvousClient, потом PunchCoordinator) — до и после неё null. Джойнер
    // всегда пробивается ровно к одному хосту за раз, так что единственный слот здесь
    // по-прежнему корректен (на хосте вместо него используется activePunches выше).
    private volatile RawPacketListener rendezvousListener;

    // Постоянный RendezvousClient хоста — живёт всю сессию хостинга (пока не позовут
    // cancelRendezvous() при закрытии мира, или не начнётся новая попытка хостинга), а
    // не только до первого матча. Комната переиспользуема, пока хост её держит (см.
    // RoomRegistry.ROOM_TTL_MILLIS на сервере), так что этот слот должен продолжать
    // получать трафик от сервера знакомств (и слать keepalive) даже во время и после
    // пробива конкретных джойнеров — поэтому он отдельно от activePunches, а не смешан с
    // ними.
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
        this.maxPlayers = 1;

        restartReceiver(hostUdpPort);
        try {
            authorizedPeers.add(new PeerAddress(InetAddress.getByName(peerHost), peerPort));
        } catch (UnknownHostException e) {
            LOGGER.error("[P2PBridge] Не удалось разрешить статический адрес пира {}", peerHost, e);
        }

        LOGGER.info("[P2PBridge] ХОСТ ГОТОВ: LAN порт MC {}, UDP слушает на {}, ожидает пира {}:{}", mcPort, receiver.getBoundPort(), peerHost, peerPort);
    }

    // Вызывается на КЛИЕНТЕ
    public void startClient() {
        int clientUdpPort = PeerCraftConfig.clientUdpPort();
        int peerPort = PeerCraftConfig.peerPortForClient();
        String peerHost = PeerCraftConfig.peerHost();
        this.isHost = false;

        restartReceiver(clientUdpPort);
        setClientTargetPeer(peerHost, peerPort);

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
    // peerHost/peerPort, затем пробивает NAT для каждого подошедшего джойнера.
    public void startHostViaRendezvous(int mcPort) {
        startHostViaRendezvous(mcPort, 1, LOGGING_HOST_LISTENER);
    }

    public void startHostViaRendezvous(int mcPort, int maxPlayers, HostListener listener) {
        this.isHost = true;
        this.localMinecraftPort = mcPort;
        this.maxPlayers = maxPlayers;
        restartReceiver(PeerCraftConfig.hostUdpPort());

        InetAddress rendezvousAddress = resolveRendezvousAddress();
        if (rendezvousAddress == null) {
            listener.onFailed("Не удалось разрешить адрес сервера знакомств");
            return;
        }
        int rendezvousPort = PeerCraftConfig.rendezvousPort();

        LOGGER.info("[P2PBridge] Регистрируемся на сервере знакомств {}:{} (макс. игроков: {})...", rendezvousAddress.getHostAddress(), rendezvousPort, maxPlayers);
        RendezvousClient client = new RendezvousClient(sender, rendezvousAddress, rendezvousPort);
        setHostRendezvousClient(client);

        // Attach the host's logged-in account (if any) so their room shows up as "hosting" in
        // friends' presence — anonymous hosting (no account) is unchanged, see
        // RendezvousClient.registerRoom's two overloads.
        net.peercraft.network.account.AccountClient.AccountSession session =
                net.peercraft.network.account.AccountClient.INSTANCE.getCurrentSession();

        client.registerRoom(
                maxPlayers,
                this::currentPlayerCount,
                session != null ? session.accountId() : null,
                session != null ? session.sessionToken() : null,
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
                        beginHostPunch(peer, token, client.accountIdForPeer(peer));
                    }

                    @Override
                    public void onFailed(String reason) {
                        LOGGER.error("[P2PBridge] Не удалось создать комнату на сервере знакомств: {}", reason);
                        listener.onFailed("Не удалось создать комнату на сервере знакомств: " + reason);
                    }
                }
        );
    }

    // Живое число подключённых игроков — пробрасывается в RendezvousClient и через
    // keepalive REGISTER доходит до RoomRegistry на сервере знакомств. Это то, что
    // позволяет месту, освободившемуся при выходе игрока, снова стать доступным без
    // отдельного события "игрок вышел" — см. RoomRegistry.join() на сервере.
    private int currentPlayerCount() {
        return hostConnectionsByAddress.size();
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
    // peerHost/peerPort, затем пробивает NAT и уже потом вызывает setClientTargetPeer(...).
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

        // Attaching our account (if logged in) lets the host identify us for save-data
        // isolation (Фаза 5) — anonymous joining (no account) is unchanged.
        net.peercraft.network.account.AccountClient.AccountSession joinerSession =
                net.peercraft.network.account.AccountClient.INSTANCE.getCurrentSession();
        RendezvousClient.MatchCallback matchCallback = new RendezvousClient.MatchCallback() {
            @Override
            public void onMatched(RendezvousProtocol.Address peer, long token) {
                beginClientPunch(peer, token, guardedListener);
            }

            @Override
            public void onFailed(String reason) {
                guardedListener.onFailed("Не удалось присоединиться к комнате " + code + ": " + reason);
            }
        };
        if (joinerSession != null) {
            client.joinRoom(code, joinerSession.sessionToken(), matchCallback);
        } else {
            client.joinRoom(code, matchCallback);
        }
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

    // ХОСТ: запускает пробив NAT для одного джойнера. В отличие от джойнерского
    // beginClientPunch(...) ниже, НЕ отменяет другие текущие попытки — activePunches
    // хранит их все параллельно, по одной на PeerAddress.
    private void beginHostPunch(RendezvousProtocol.Address peer, long token, java.util.Optional<java.util.UUID> joinerAccountId) {
        PeerAddress addr = new PeerAddress(peer.host(), peer.port());
        if (joinerAccountId.isPresent()) {
            joinerAccountIdByAddress.put(addr, joinerAccountId.get());
        } else {
            joinerAccountIdByAddress.remove(addr);
        }

        // Defensive-проверка лимита на случай гонки с проверкой на RoomRegistry (сервер
        // знакомств уже должен был отклонить лишний JOIN сам, см. RoomRegistry.join()) —
        // не ожидается в норме. Считаем по живым HostConnection + попыткам пробива в
        // процессе, а не по authorizedPeers (который никогда не уменьшается, см. его
        // комментарий) — иначе давно отключившиеся игроки навсегда "съедали" бы места.
        if (hostConnectionsByAddress.size() + activePunches.size() >= maxPlayers) {
            LOGGER.warn("[P2PBridge] Игнорируем матч с {}:{} — комната уже полна ({} из {} игроков)",
                    peer.host().getHostAddress(), peer.port(), hostConnectionsByAddress.size(), maxPlayers);
            return;
        }

        LOGGER.info("[P2PBridge] (Хост) Пир найден: {}:{}, начинаем hole punching...", peer.host().getHostAddress(), peer.port());
        PunchCoordinator punch = new PunchCoordinator(sender, peer, token, new PunchCoordinator.Callback() {
            @Override
            public void onSuccess(String ip, int port) {
                activePunches.remove(addr);
                authorizedPeers.add(addr);
                LOGGER.info("[P2PBridge] (Хост) P2P-соединение установлено напрямую с {}:{}", ip, port);
            }

            @Override
            public void onFailure(String reason) {
                activePunches.remove(addr);
                LOGGER.error("[P2PBridge] (Хост) Hole punching с {}:{} не удался: {}", peer.host().getHostAddress(), peer.port(), reason);
            }
        });
        PunchCoordinator previous = activePunches.put(addr, punch);
        if (previous != null) {
            // Тот же пир уже пробивался (например, повторный матч до истечения предыдущей
            // попытки) — отменяем именно его, другие попытки (для других адресов) не трогаем.
            previous.cancel();
        }
        punch.start();
    }

    // ДЖОЙНЕР: запускает пробив NAT к хосту. Джойнер всегда пробивается ровно к одному
    // пиру за раз, так что единственный слот rendezvousListener здесь корректен (в
    // отличие от хоста, см. beginHostPunch выше).
    private void beginClientPunch(RendezvousProtocol.Address peer, long token, ConnectListener listener) {
        LOGGER.info("[P2PBridge] Пир найден: {}:{}, начинаем hole punching...", peer.host().getHostAddress(), peer.port());
        listener.onStatus("Пир найден: " + peer.host().getHostAddress() + ":" + peer.port() + ", пробиваем NAT...");
        PunchCoordinator punch = new PunchCoordinator(sender, peer, token, new PunchCoordinator.Callback() {
            @Override
            public void onSuccess(String ip, int port) {
                clearRendezvousListener();
                setClientTargetPeer(ip, port);
                LOGGER.info("[P2PBridge] P2P-соединение установлено напрямую с {}:{}", ip, port);
                listener.onConnected();
            }

            @Override
            public void onFailure(String reason) {
                clearRendezvousListener();
                LOGGER.error("[P2PBridge] Hole punching не удался: {}", reason);
                listener.onFailed("Hole punching не удался: " + reason);
            }
        });
        setRendezvousListener(punch);
        punch.start();
    }

    // Заменяет текущего rendezvousListener новым, сначала останавливая (cancel()) старого
    // — иначе его фоновые потоки (например, keepalive REGISTER у RendezvousClient) остаются
    // работать вечно без владельца. См. RawPacketListener.cancel(). Актуально только для
    // роли ДЖОЙНЕРА — см. комментарий у поля rendezvousListener.
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

    // Останавливает текущую rendezvous/punch-активность хоста — постоянный RendezvousClient
    // хоста и все текущие попытки пробива (activePunches) — без сообщения об ошибке.
    // Вызывается, когда хост закрывает свой мир (см. OpenToLanMixin.onStopServer), чтобы
    // комната не продолжала жить (и не оставалась переиспользуемой) после того, как
    // хостить перестали.
    public void cancelRendezvous() {
        setHostRendezvousClient(null);
        clearRendezvousListener();
        for (PeerAddress addr : activePunches.keySet()) {
            PunchCoordinator punch = activePunches.remove(addr);
            if (punch != null) {
                punch.cancel();
            }
        }
        authorizedPeers.clear();
        joinerAccountIdByAddress.clear();
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

    // ДЖОЙНЕР ТОЛЬКО: задаёт единственного целевого хоста моста.
    private void setClientTargetPeer(String ip, int port) {
        try {
            this.clientTargetPeer = new PeerAddress(InetAddress.getByName(ip), port);
            LOGGER.info("[P2PBridge] Назначен целевой хост: {}:{}", ip, port);
        } catch (UnknownHostException e) {
            LOGGER.error("[P2PBridge] Не удалось разрешить адрес хоста {}", ip, e);
        }
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
        PeerAddress dest = this.clientTargetPeer;
        if (dest == null) {
            LOGGER.warn("[P2PBridge] Отмена отправки: целевой хост ещё не задан!");
            return;
        }
        sendChunked(dest, sessionId, session.outSeq, data, session.sentPackets);
    }

    private void sendEncoded(PeerAddress dest, byte[] framed) {
        if (dest == null) {
            LOGGER.warn("[P2PBridge] Отмена отправки: адрес назначения не задан!");
            return;
        }
        if (sender == null) {
            LOGGER.error("[P2PBridge] Отмена отправки: P2PSender не инициализирован!");
            return;
        }
        sender.sendData(framed, dest.ip(), dest.port());
    }

    private void sendFramed(PeerAddress dest, long sessionId, long seq, byte flags, byte[] data) {
        sendEncoded(dest, FramedPacket.encode(sessionId, seq, flags, data));
    }

    // Как sendFramed, но дополнительно сохраняет закодированный пакет в буфере
    // ретрансляции соединения — так по NACK можно переслать оригинальные байты заново.
    private void sendFramedAndBuffer(PeerAddress dest, long sessionId, long seq, byte flags, byte[] data, Map<Long, byte[]> retransmitBuffer) {
        byte[] framed = FramedPacket.encode(sessionId, seq, flags, data);
        synchronized (retransmitBuffer) {
            retransmitBuffer.put(seq, framed);
        }
        sendEncoded(dest, framed);
    }

    // Режет один TCP-read на несколько UDP-датаграмм не крупнее MAX_CHUNK_SIZE, каждая со
    // своим следующим seq. Приёмный ReorderBuffer уже умеет прозрачно склеивать соседние seq
    // обратно в один кусок, так что получателю никаких изменений не требуется.
    private void sendChunked(PeerAddress dest, long sessionId, AtomicLong outSeq, byte[] data, Map<Long, byte[]> retransmitBuffer) {
        int offset = 0;
        do {
            int len = Math.min(MAX_CHUNK_SIZE, data.length - offset);
            byte[] chunk = Arrays.copyOfRange(data, offset, offset + len);
            sendFramedAndBuffer(dest, sessionId, outSeq.getAndIncrement(), (byte) 0, chunk, retransmitBuffer);
            offset += len;
        } while (offset < data.length);
    }

    private void sendNack(PeerAddress dest, long sessionId, long missingSeq) {
        sendEncoded(dest, FramedPacket.encodeNack(sessionId, missingSeq));
    }

    private void resendIfBuffered(PeerAddress dest, long sessionId, long seq, Map<Long, byte[]> retransmitBuffer) {
        byte[] framed;
        synchronized (retransmitBuffer) {
            framed = retransmitBuffer.get(seq);
        }
        if (framed != null) {
            LOGGER.info("[P2PBridge] Повторно отправляем seq={} для сессии {} по NACK", seq, sessionId);
            sendEncoded(dest, framed);
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
        if (length >= 1 && data[0] == RendezvousProtocol.MAGIC) {
            RendezvousClient hostClient = this.hostRendezvousClient;
            if (hostClient != null) {
                hostClient.onPacket(data, length, senderAddress, senderPort);
            }
            if (this.isHost) {
                // На хосте несколько попыток пробива могут идти одновременно (по одной на
                // джойнера) — рассылаем всем, каждый PunchCoordinator сам фильтрует по
                // ожидаемому адресу+токену, так что это безопасно и ограничено maxPlayers.
                boolean anyPunchActive = false;
                for (PunchCoordinator punch : activePunches.values()) {
                    anyPunchActive = true;
                    punch.onPacket(data, length, senderAddress, senderPort);
                }
                if (hostClient == null && !anyPunchActive) {
                    LOGGER.debug("[P2PBridge] Получен rendezvous-пакет от {}:{}, но ни один слушатель не активен — игнорируем", senderAddress, senderPort);
                }
            } else {
                RawPacketListener listener = this.rendezvousListener;
                if (listener != null) {
                    listener.onPacket(data, length, senderAddress, senderPort);
                } else if (hostClient == null) {
                    // Обычно безобидный запоздавший пакет от сервера знакомств/пира, пришедший
                    // уже после того, как rendezvous/punch-фаза закончилась (успехом или нет) —
                    // но след оставляем, иначе он пропадает совсем без объяснений.
                    LOGGER.debug("[P2PBridge] Получен rendezvous-пакет от {}:{}, но ни один слушатель не активен — игнорируем", senderAddress, senderPort);
                }
            }
            return;
        }

        FramedPacket frame = FramedPacket.decode(data, length);
        if (frame == null) {
            LOGGER.warn("[P2PBridge] Отброшен пакет: не удалось разобрать заголовок ({} байт)", length);
            return;
        }

        if (this.isHost) {
            handleHostIncoming(frame, new PeerAddress(senderAddress, senderPort));
        } else {
            handleClientIncoming(frame);
        }
    }

    private synchronized void handleHostIncoming(FramedPacket frame, PeerAddress sender) {
        HostConnection conn = this.hostConnectionsBySessionId.get(frame.sessionId());

        if (frame.type() == FramedPacket.TYPE_NACK) {
            if (conn != null) {
                resendIfBuffered(conn.peerAddress, conn.sessionId, frame.nackSeq(), conn.sentPackets);
            }
            return;
        }

        if (conn != null) {
            // Тот же пир, что и раньше в этой сессии, либо его NAT переназначил внешний порт
            // между пакетами — терпим это неявно, как и раньше (сохраняем сегодняшнее
            // поведение однопирового моста, но теперь явно, а не случайно): переносим адрес
            // соединения, а не заводим новую сессию.
            if (!sender.equals(conn.peerAddress)) {
                hostConnectionsByAddress.remove(conn.peerAddress);
                conn.peerAddress = sender;
                hostConnectionsByAddress.put(sender, conn);
            }
            deliverToHost(conn, frame);
            return;
        }

        if (frame.seq() != 0) {
            // Пакет не начала новой сессии — не подхватываем середину чужого/устаревшего потока.
            LOGGER.debug("[P2PBridge] Отброшен пакет чужой/устаревшей сессии {} (seq={})", frame.sessionId(), frame.seq());
            return;
        }

        if (!authorizedPeers.contains(sender)) {
            // Этот адрес никогда не проходил hole punching (или это чужой UDP-отправитель,
            // угадавший relay-порт хоста) — не открываем ему TCP-подключение к MC-серверу.
            LOGGER.warn("[P2PBridge] Отброшена попытка новой сессии от неавторизованного {}:{}", sender.ip(), sender.port());
            return;
        }

        // Реконнект того же джойнера (новая логическая TCP-сессия с тем же адресом) —
        // закрываем только его предыдущую HostConnection, остальные джойнеры не задеты.
        HostConnection existingForAddress = hostConnectionsByAddress.get(sender);
        if (existingForAddress != null) {
            closeHostConnection(existingForAddress);
        }

        HostConnection newConn = startNewHostConnection(frame.sessionId(), sender);
        if (newConn == null) {
            return;
        }
        deliverToHost(newConn, frame);
    }

    private HostConnection startNewHostConnection(long sessionId, PeerAddress peerAddress) {
        try {
            LOGGER.info("[P2PBridge] Подключаемся к локальному MC серверу 127.0.0.1:{} (сессия {}, пир {}:{})...", localMinecraftPort, sessionId, peerAddress.ip(), peerAddress.port());
            Socket mcSocket = new Socket("127.0.0.1", localMinecraftPort);
            HostConnection conn = new HostConnection(sessionId, peerAddress, mcSocket);
            hostConnectionsBySessionId.put(sessionId, conn);
            hostConnectionsByAddress.put(peerAddress, conn);

            java.util.UUID joinerAccountId = joinerAccountIdByAddress.get(peerAddress);
            if (joinerAccountId != null) {
                PlayerIdentityRegistry.INSTANCE.put(conn.localPort, joinerAccountId);
            }

            Thread readerThread = new Thread(() -> listenMcResponses(conn), "PeerCraft-MC-Reader");
            readerThread.setDaemon(true);
            readerThread.start();

            LOGGER.info("[P2PBridge] УСПЕШНО подключились к локальному MC серверу (сессия {}, {} из {} игроков)!", sessionId, hostConnectionsByAddress.size(), maxPlayers);
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
                sendNack(conn.peerAddress, conn.sessionId, result.requestSeq);
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
                resendIfBuffered(this.clientTargetPeer, session.sessionId, frame.nackSeq(), session.sentPackets);
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
                sendNack(this.clientTargetPeer, session.sessionId, result.requestSeq);
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
                sendChunked(conn.peerAddress, conn.sessionId, conn.outSeq, payload, conn.sentPackets);
            }
        } catch (Exception e) {
            LOGGER.warn("[P2PBridge] Чтение из Minecraft-сервера остановлено (сессия {}): {}", conn.sessionId, e.toString());
        } finally {
            sendFramed(conn.peerAddress, conn.sessionId, conn.outSeq.getAndIncrement(), (byte) FramedPacket.FLAG_FIN, new byte[0]);
            closeHostConnection(conn);
        }
    }

    // Закрывает соединение и убирает его из обеих карт (по текущему, возможно уже
    // перевешенному, conn.peerAddress) — устаревший поток от уже закрытого HostConnection
    // не может затереть состояние более новой сессии на том же адресе, т.к. проверяем
    // именно на равенство "карта -> этот же conn", а не просто удаляем по ключу.
    private synchronized void closeHostConnection(HostConnection conn) {
        conn.active = false;
        closeQuietly(conn.mcSocket);
        // The joinerAccountIdByAddress entry itself stays (see its field comment — needed for
        // a fast reconnect without a fresh punch), but THIS specific local-port mapping is
        // dead the moment the socket closes: a reconnect opens a brand new Socket with a new
        // ephemeral port, and startNewHostConnection re-populates PlayerIdentityRegistry then.
        PlayerIdentityRegistry.INSTANCE.remove(conn.localPort);
        hostConnectionsBySessionId.remove(conn.sessionId, conn);
        hostConnectionsByAddress.remove(conn.peerAddress, conn);
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
        for (HostConnection conn : hostConnectionsByAddress.values()) {
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
        volatile PeerAddress peerAddress;
        final Socket mcSocket;
        // Captured once at construction rather than re-querying mcSocket.getLocalPort() later
        // (e.g. in closeHostConnection, after the socket may already be closed) — see
        // PlayerIdentityRegistry's use of this exact value.
        final int localPort;
        final OutputStream mcOut;
        final InputStream mcIn;
        final ReorderBuffer inBuf = new ReorderBuffer();
        final AtomicLong outSeq = new AtomicLong(0);
        final Map<Long, byte[]> sentPackets = newRetransmitBuffer();
        volatile boolean active = true;

        HostConnection(long sessionId, PeerAddress peerAddress, Socket mcSocket) throws IOException {
            this.sessionId = sessionId;
            this.peerAddress = peerAddress;
            this.mcSocket = mcSocket;
            this.localPort = mcSocket.getLocalPort();
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
