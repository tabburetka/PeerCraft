# Join request UI review — Minecraft 26.2 Fabric

Run from `peercraft/` with Java 21 or newer:

```sh
./gradlew -I ../docs/verification/join-requests-262/probe.gradle :26.2-fabric:runClient
```

This is an isolated interactive UI fixture, not server-side admission protection. The normal
mod JAR does not include the fixture. No real room is registered; networking points to
loopback port 1. A fresh creative flat world is created under `build/join-requests-262/game`.
The settings, requests, decisions and block action are simulated and do not persist.

Initial automated captures exercise host requests, guest waiting, settings, the transient HUD
notification, expiration of that notification, and a real pause screen with a request button.
Then the host request panel stays open for manual review. Use "Готово" to return to the pause menu, then resume the game;
Esc opens the actual pause screen. "Предпросмотр: новая заявка и уведомление" supplies two
sample requests and shows a notification for five seconds without opening any screen.

Screenshots: `build/join-requests-262/game/screenshots/join-*.png`.

The network logger change is production source, limited to the main modern P2P, rendezvous
and local proxy components. It masks numeric IPv4/IPv6 arguments and exception messages at
SLF4J calls. It does not rewrite old logs, cover third-party/vanilla logging, or conceal IPs
from network peers. Java 8/backport twins are deliberately deferred until the UI review.

Required next implementation: authenticated server-side requests and decisions, no peer
addresses/candidates before approval and guest confirmation, per-account/IP request limits,
request cancellation and expiration, persistent host preference and blocks, safe handling
of old clients/servers. Do not claim approval protects IPs until that path is implemented and
verified using two clients and packet-level observations.
