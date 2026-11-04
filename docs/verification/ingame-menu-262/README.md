# In-game PeerCraft menu verification — Minecraft 26.2

From `peercraft/`:

```bash
./gradlew -I ../docs/verification/ingame-menu-262/probe.gradle :26.2-fabric:runClient --offline
```

The fixture creates a fresh flat world in `build/ingame-menu-262/game`, opens the pause menu,
checks that the single 20×20 "PC" entry overlaps no other control, opens the quick menu signed
out and with a fake (never sent) account session, then the "players on this server" and
"friends" screens. The client exits on its own.

Success markers: `INGAME_MENU_PAUSE_ENTRY_PASSED`, `INGAME_MENU_PASSED`.
Captures: `build/ingame-menu-262/game/screenshots/ingame-*.png`.

Not covered: rows with real friends/requests/players need a live rendezvous server and a
second client.

## Legacy versions

The same flow (pause "PC" entry overlap check, quick menu signed out / signed in, players,
friends) runs on the backports:

```bash
./gradlew -I ../docs/verification/session-ui-1165/probe.gradle :1.16.5-fabric:runClient --offline   # from peercraft/
./gradlew -I ../docs/verification/ingame-menu-1122/probe.gradle runClient --offline                  # from peercraft-forge-1122/
./gradlew -I ../docs/verification/session-ui-1710/probe.gradle runClient --offline                   # from peercraft-forge-1710/
```

Markers: `SESSION_UI_1165_PASSED`, `INGAME_MENU_1122_PASSED`, `SESSION_UI_1710_PASSED` (1.16.5 and
1.7.10 also re-check the pause entry at 640×480). The `session-ui-262` fixture still looks for the
old full-width pause buttons and fails at that step; this probe replaces that part of it.
