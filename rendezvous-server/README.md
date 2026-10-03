# PeerCraft Rendezvous Server

A minimal standalone UDP server that lets two PeerCraft (Minecraft mod) players on
different networks find each other's public address and attempt direct UDP hole
punching. It never sees or relays actual game traffic — its only job is:

1. **Host** sends `REGISTER`; the server replies with a short room code and the host's
   own observed public `(ip, port)`.
2. **Joiner** sends `JOIN <code>`; once both sides are known, the server sends each one
   the *other's* observed address plus a shared pairing token (`PEER_FOUND`), then gets
   out of the way.
3. Both peers then punch directly at each other using that address — see the mod's own
   `README.md` ("Internet play") for the full flow and how it plugs into the rest of
   PeerCraft.

No Minecraft/Fabric dependency. The server also handles accounts, friends,
presence, public rooms, and handoff control. It does not relay game traffic.

## Build

```
./gradlew jar
```

Produces `build/libs/rendezvous-server-<version>.jar` — a single runnable jar, nothing
else to copy.

## Run

```
java -jar build/libs/rendezvous-server-1.0.0.jar [port]
```

`port` defaults to `51000` if omitted. Forward that UDP port on your router to whatever
machine you run this on (same idea as forwarding a port for a vanilla Minecraft
server, just UDP instead of TCP) — this is the *only* port-forward internet play
needs; neither player's own machine requires one.

## Local analytics dashboard

The same jar now collects aggregate statistics from requests it already receives.
No mod update or protocol change is needed, including for older clients. The dashboard
uses a separate HTTP port, `51080` by default. It binds to `127.0.0.1` by default.
For access from another device at home, bind it to the server's **LAN address**:

```
java -Dpeercraft.analytics.host=192.168.1.20 -Dpeercraft.analytics.httpPort=51080 \
  -Dpeercraft.rendezvous.dataDir=/path/to/existing/data \
  -jar rendezvous-server-1.0.0.jar 51000
```

Open `http://192.168.1.20:51080/` on a device on the same network. Replace the
example address with the server's actual LAN address. Allow TCP 51080 on the server's
local firewall if necessary; **do not forward that TCP port on the router**. Set
`-Dpeercraft.analytics.httpPort=0` to disable the dashboard. Keep the existing
`peercraft.rendezvous.dataDir` and UDP port when upgrading so account data and client
connections continue to work.

If the jar is launched by an existing service and its Java arguments are not easily
editable, create `<dataDir>/analytics.properties` instead:

```
host=192.168.1.20
httpPort=51080
```

The Java `-Dpeercraft.analytics.*` options override this file. An invalid dashboard
setting or an unavailable HTTP port is logged without stopping the UDP service.

The dashboard shows unique observed network addresses and authenticated accounts by
UTC day and calendar month, request counts by operation, successful/failed auth,
room creation and visibility, pairing attempts, friend actions, Minecraft versions
reported with new rooms, and a live room snapshot. A pairing means the rendezvous
server returned addresses; it does not prove the game connection succeeded. Repeated
UDP requests may increase event counts. Network-address uniqueness is an estimate:
players behind one router share an address, while one player may use several addresses.
Unique sets are capped at 100,000 entries per UTC day to bound memory use.
The RU/EN buttons switch the dashboard language immediately; the choice is saved in
the browser on that device.

Aggregates start when this version first runs; older history cannot be reconstructed.
Analytics are stored under `<dataDir>/analytics/` for 400 days. The store contains
HMAC hashes for unique counting, not raw IP addresses, account IDs, names, tokens,
room codes, or passwords. Keep `analytics.key` with `analytics.json` when moving the
server: replacing the key would make cross-day uniqueness inaccurate. This local HTTP
dashboard has no login, so bind it to a trusted LAN address only.

## Test

```
./gradlew test
```

Unit tests for the wire protocol and room registry, plus a live integration test that
starts a real server instance and drives it with raw UDP datagrams.

## Optional daily host reboot

For a host that should reboot every day at **05:00 Europe/Saratov**, install the
unit files in `deploy/systemd/` as root:

```
sudo install -m 644 deploy/systemd/peercraft-daily-reboot.service /etc/systemd/system/
sudo install -m 644 deploy/systemd/peercraft-daily-reboot.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now peercraft-daily-reboot.timer
systemctl list-timers peercraft-daily-reboot.timer
```

The timer is deliberately not persistent: if the host is off at 05:00, booting it
later will not trigger an immediate reboot. The machine, including active game
connections, will restart at the scheduled time. Ensure `rendezvous.service` is
enabled so it starts again after boot. Disable the schedule with
`sudo systemctl disable --now peercraft-daily-reboot.timer`.
