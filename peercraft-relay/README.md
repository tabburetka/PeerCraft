# peercraft-relay

[Русский](#русский) · [English](#english)

---

## Русский

**peercraft-relay** — маленькая программа, через которую два игрока PeerCraft могут играть вместе, если прямое P2P-подключение не получилось (строгий/симметричный NAT, мобильный интернет, CGNAT, корпоративная сеть).

Реле нужно **только одному** из двух игроков: хосту или тому, кто подключается. Второму игроку ничего настраивать не нужно. Если реле есть у обоих, используется реле хоста.

### Сначала попробуйте без реле

1. Отключите VPN у обоих игроков и подключитесь снова. VPN — самая частая причина ошибки «Не удалось подключиться напрямую».
2. Если не помогло — разверните реле по инструкции ниже.

### Что нужно

- Компьютер или сервер (VPS), **доступный из интернета по UDP**: белый IP и проброшенный порт на роутере, либо VPS с открытым портом в фаерволе.
- Java 17 или новее (или Docker).
- Нагрузка небольшая: одна игровая сессия обычно занимает меньше 1 Мбит/с; по умолчанию скорость одной сессии ограничена 2 МиБ/с.

Реле не видит игру: весь трафик зашифрован между игроками ключами, которых у реле нет. Реле только пересылает пакеты.

### Запуск (jar)

1. Скачайте `peercraft-relay-<версия>.jar` со страницы [Releases](../../releases).
2. Запустите:

   ```bash
   java -jar peercraft-relay-1.0.0.jar
   ```

3. Программа создаст рядом файл `relay.properties` и напечатает **код реле**:

   ```
   Relay code (PeerCraft -> Settings -> Own relay):

     pcrelay://203.0.113.7:52100#k3J...
   ```

4. В игре: **PeerCraft → Настройки → Код своего реле** (или кнопка **«Своё реле…»** в окне ошибки подключения). Вставьте код, нажмите **«Проверить»** — должно появиться «Реле доступно, N мс». Сохраните.
5. Подключайтесь как обычно. Если прямое соединение не получится, PeerCraft сам переключится на реле.

Окно реле должно оставаться открытым, пока вы играете. Остановить — `Ctrl+C`.

### Домашний ПК: проброс порта

1. Узнайте, есть ли у вас белый IP: адрес, который напечатало реле, должен совпадать с WAN-адресом в настройках роутера. Если адрес в роутере начинается с `10.`, `100.64–127.`, `172.16–31.` или `192.168.` — у провайдера CGNAT, проброс не сработает; используйте VPS или закажите у провайдера белый IP.
2. В роутере пробросьте **UDP 52100** на локальный IP компьютера с реле.
3. Разрешите входящий UDP 52100 в фаерволе компьютера (Windows спросит при первом запуске — разрешите для частных и общественных сетей).
4. Если IP меняется после перезагрузки роутера, код реле тоже изменится. Можно использовать динамический DNS и указать домен: `--public-host myrelay.ddns.net`.

### VPS

Подойдёт самый дешёвый VPS с Linux.

```bash
sudo apt install -y openjdk-17-jre-headless   # или новее
java -jar peercraft-relay-1.0.0.jar
sudo ufw allow 52100/udp                      # если используется ufw
```

Откройте UDP 52100 и в панели провайдера (Security Group / Firewall), если она есть.

Автозапуск через systemd — `/etc/systemd/system/peercraft-relay.service`:

```ini
[Unit]
Description=PeerCraft relay
After=network-online.target

[Service]
WorkingDirectory=/opt/peercraft-relay
ExecStart=/usr/bin/java -jar /opt/peercraft-relay/peercraft-relay.jar
Restart=on-failure
User=peercraft

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now peercraft-relay
journalctl -u peercraft-relay   # здесь будет код реле
```

### Docker

```bash
docker compose up -d --build
docker compose logs peercraft-relay   # код реле
```

Или без compose:

```bash
docker build -t peercraft-relay .
docker run -d --name peercraft-relay --restart unless-stopped \
  -p 52100:52100/udp -v "$PWD/data:/data" peercraft-relay
```

Настройки и секрет хранятся в `./data/relay.properties`, поэтому код не меняется при пересоздании контейнера.

### Настройки (`relay.properties`)

| Ключ | По умолчанию | Значение |
|---|---|---|
| `port` | `52100` | UDP-порт реле |
| `secret` | случайный | Секрет из кода реле. Не публикуйте |
| `publicHost` | пусто | IP или домен для кода; пусто — определить автоматически |
| `maxSessions` | `8` | Сколько пар игроков одновременно |
| `maxKiBPerSecondPerSession` | `2048` | Ограничение скорости одной сессии, КиБ/с |
| `opensPerMinutePerIp` | `20` | Сколько новых сессий в минуту с одного IP |
| `pendingTimeoutSeconds` | `30` | Сколько ждать второго игрока |
| `idleTimeoutSeconds` | `60` | Закрыть сессию после стольких секунд без трафика |

Параметры запуска: `--config FILE`, `--public-host HOST`, `--rotate-secret`. Переменные окружения: `PEERCRAFT_RELAY_CONFIG`, `PEERCRAFT_RELAY_PUBLIC_HOST`.

### Безопасность

- Код реле — это пароль. С ним кто угодно может открывать сессии на вашем реле (в пределах лимитов). Передавайте его только тем, кому доверяете.
- Если код утёк: `java -jar peercraft-relay.jar --rotate-secret` — старый код перестанет работать, вставьте в игру новый.
- Реле не может прочитать или подменить игровой трафик: он зашифрован и подписан ключами самого матча.
- Подписанные запросы проверяют время: часы компьютера и реле не должны расходиться больше чем на 10 минут (включите синхронизацию времени).

### Если не работает

| Сообщение | Что сделать |
|---|---|
| «Нет ответа» | Реле не запущено, порт не проброшен или закрыт фаерволом, неверный IP в коде |
| «Секрет неверный» | Скопируйте код из реле заново (после `--rotate-secret` старый код недействителен) |
| «Часы расходятся» | Включите синхронизацию времени на компьютере и на сервере |
| «Адрес реле локальный» | В коде указан `192.168.*`/`10.*`/`127.*` — задайте публичный адрес через `--public-host` |
| «Реле занято» | Достигнут `maxSessions` или лимит запросов; подождите минуту или увеличьте лимиты |

### Как это работает

1. PeerCraft сначала пробует прямое P2P-соединение.
2. Если не вышло, игрок с кодом реле открывает на реле сессию (запрос подписан секретом) и сообщает её номер второму игроку через сервер знакомств PeerCraft, по уже защищённому каналу матча.
3. Второй игрок присоединяется к сессии. Реле запоминает адреса обоих и пересылает между ними зашифрованные пакеты.

Протокол — UDP, магический байт `0xEB`; описание — в [`OwnRelayProtocol.java`](src/main/java/net/peercraft/network/ownrelay/OwnRelayProtocol.java).

### Сборка из исходников

```bash
./gradlew jar      # build/libs/peercraft-relay-<версия>.jar
./gradlew test
```

---

## English

**peercraft-relay** is a small program that lets two PeerCraft players play together when a direct P2P connection fails (strict/symmetric NAT, mobile internet, CGNAT, corporate networks).

Only **one** of the two players needs a relay — the host or the joining player. The other player configures nothing. If both have one, the host's relay is used.

### Try without a relay first

1. Turn off VPNs on both sides and connect again. A VPN is the most common cause of “Could not connect directly”.
2. If that does not help, set up a relay as described below.

### Requirements

- A PC or server (VPS) **reachable from the internet over UDP**: a public IP with a forwarded router port, or a VPS with the port open in its firewall.
- Java 17 or newer (or Docker).
- Light load: one game session usually needs less than 1 Mbit/s; by default each session is capped at 2 MiB/s.

The relay cannot see the game: traffic is encrypted between the players with keys the relay never has. It only forwards packets.

### Run (jar)

1. Download `peercraft-relay-<version>.jar` from [Releases](../../releases).
2. Run:

   ```bash
   java -jar peercraft-relay-1.0.0.jar
   ```

3. It creates `relay.properties` next to it and prints a **relay code**:

   ```
   Relay code (PeerCraft -> Settings -> Own relay):

     pcrelay://203.0.113.7:52100#k3J...
   ```

4. In game: **PeerCraft → Settings → Own relay code** (or the **“Own relay…”** button in the connection error dialog). Paste the code, press **Check** — you should see “Relay is reachable, N ms”. Save.
5. Connect as usual. If the direct connection fails, PeerCraft switches to the relay automatically.

Keep the relay running while you play. Stop it with `Ctrl+C`.

### Home PC: port forwarding

1. Check that you have a public IP: the address printed by the relay should match the WAN address in your router. If the router shows `10.*`, `100.64–127.*`, `172.16–31.*` or `192.168.*`, your ISP uses CGNAT and forwarding will not work — use a VPS or ask your ISP for a public IP.
2. Forward **UDP 52100** in the router to the local IP of the relay machine.
3. Allow inbound UDP 52100 in the OS firewall (Windows asks on first start — allow it).
4. If your IP changes, the relay code changes too. Use dynamic DNS and pass the domain: `--public-host myrelay.ddns.net`.

### VPS

The cheapest Linux VPS is enough.

```bash
sudo apt install -y openjdk-17-jre-headless   # or newer
java -jar peercraft-relay-1.0.0.jar
sudo ufw allow 52100/udp                      # if ufw is used
```

Also open UDP 52100 in the provider's panel (Security Group / Firewall) if it has one. A systemd unit example is in the Russian section above.

### Docker

```bash
docker compose up -d --build
docker compose logs peercraft-relay   # relay code
```

Settings and the secret live in `./data/relay.properties`, so the code survives container re-creation.

### Settings (`relay.properties`)

| Key | Default | Meaning |
|---|---|---|
| `port` | `52100` | Relay UDP port |
| `secret` | random | Secret part of the relay code. Keep it private |
| `publicHost` | empty | IP or domain put in the code; empty = auto-detect |
| `maxSessions` | `8` | Concurrent player pairs |
| `maxKiBPerSecondPerSession` | `2048` | Per-session rate limit, KiB/s |
| `opensPerMinutePerIp` | `20` | New sessions per minute from one IP |
| `pendingTimeoutSeconds` | `30` | How long to wait for the second player |
| `idleTimeoutSeconds` | `60` | Close a session after this much silence |

Options: `--config FILE`, `--public-host HOST`, `--rotate-secret`. Environment: `PEERCRAFT_RELAY_CONFIG`, `PEERCRAFT_RELAY_PUBLIC_HOST`.

### Security

- The relay code is a password: anyone who has it can open sessions on your relay (within the limits). Share it only with people you trust.
- If it leaks, run with `--rotate-secret`; the old code stops working.
- The relay cannot read or alter game traffic: it is encrypted and authenticated with per-match keys.
- Signed requests are time-checked: the clocks of the player's PC and the relay must be within 10 minutes.

### Troubleshooting

| Message | Fix |
|---|---|
| “No answer” | Relay not running, port not forwarded or blocked, wrong IP in the code |
| “Secret is wrong” | Copy the code again (after `--rotate-secret` the old code is invalid) |
| “Clocks differ” | Enable time sync on both machines |
| “Relay address is local” | The code has a `192.168.*`/`10.*`/`127.*` address — set the public one with `--public-host` |
| “Relay is busy” | `maxSessions` or the request limit was reached; wait a minute or raise the limits |

### How it works

1. PeerCraft first tries a direct P2P connection.
2. If that fails, the player with the relay code opens a session on the relay (the request is signed with the secret) and sends its id to the other player through the PeerCraft rendezvous server, over the match's already authenticated channel.
3. The other player joins the session. The relay learns both addresses and forwards their encrypted packets.

The protocol is UDP with magic byte `0xEB`; see [`OwnRelayProtocol.java`](src/main/java/net/peercraft/network/ownrelay/OwnRelayProtocol.java).

### Building from source

```bash
./gradlew jar      # build/libs/peercraft-relay-<version>.jar
./gradlew test
```

---

MIT License — see [LICENSE](LICENSE).
