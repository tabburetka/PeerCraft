# Session UI verification — Minecraft 26.2

From `peercraft/`:

```bash
./gradlew -I ../docs/verification/session-ui-262/probe.gradle :26.2-fabric:runClient
```

The fixture uses a separate `build/session-ui-262/game` directory and opens the production
screen, without demonstration values or test buttons. It captures each section and creates
a fresh creative flat world, captures the real local player's numeric ping, and opens the
session screen through the actual pause-menu button.

For the pause-layout check only, the fixture temporarily supplies an inert rendezvous client
and the hosting flag. No room is registered and no packets are sent. Both session and handoff
buttons must be visible, within bounds, on the same row and separated horizontally. The original
bridge fields are restored before leaving the production session screen open for review.

Captures: `build/session-ui-262/game/screenshots/session-*.png`.
Success markers: `SESSION_UI_HANDOFF_SEPARATION_PASSED`, `SESSION_UI_PAUSE_ENTRY_PASSED`,
`SESSION_UI_PREVIEW_READY` (finished; client stays open). The test-only entrypoint is bundled
only by this init script, never by the normal build.

The production panel reads Minecraft player latency and local average tick time. Packet loss,
ping variation, file counters, remote tick telemetry and host ranking remain explicitly unavailable.
The panel does not pause the server while measuring it. Manual handoff follows existing eligibility
and confirmation settings. The same screen and pause-row behavior is implemented in all client
source families. Minecraft 1.7.10 uses Forge's PLAYER_LIST event for numeric ping; later versions
use their player-list ping rendering method.

## Cross-version verification (2026-10-19)

- All 32 normal client packages built: 30 Stonecutter Fabric/NeoForge targets plus Forge 1.12.2 and 1.7.10.
- Three `SessionPauseLayoutTest` tests passed, including 48 size/bottom combinations with both actions.
- Fabric 1.16.5: both pause actions, actual navigation, 640×480 layout and scrolling captured; `SESSION_UI_1165_PASSED`.
- Fabric 26.2: real Tab ping, both pause actions and navigation captured; `SESSION_UI_HANDOFF_SEPARATION_PASSED` and `SESSION_UI_PAUSE_ENTRY_PASSED`.
- Forge 1.7.10: real Tab ping and both actions captured in a separate local world; the probe checks both regular and compact layouts.
- `build/session-ui-matrix-audit.json` records SHA-256, bytecode versions and checks for the session screen, pause layout and numeric ping in all 32 packages. It also checks that fixture classes and demonstration data are absent.

The additional isolated probes live in `docs/verification/session-ui-1165` and
`docs/verification/session-ui-1710`; use their `probe.gradle` init scripts with the corresponding
`runClient` task. These checks validate menu layout and the available local measurements,
not remote loss telemetry, file counters or host benchmarking.
