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

No Minecraft/Fabric dependency — plain Java, no external libraries. Deliberately
minimal for v1: no accounts, no persistent lobbies, no TURN/relay fallback if hole
punching fails (see the mod's README for why).

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

## Test

```
./gradlew test
```

Unit tests for the wire protocol and room registry, plus a live integration test that
starts a real server instance and drives it with raw UDP datagrams.
