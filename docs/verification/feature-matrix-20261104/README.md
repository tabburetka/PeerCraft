# Feature matrix — 2026-11-04 (PeerCraft 3.4.3)

Automated checks of the four features merged into `main` on 2026-11-04: mod sync removal of
joiner-only mods, the in-game ESC (PC) menu, the mod version check and opt-in telemetry.

## How to run (from the repository root)

```bash
cd rendezvous-server && ./gradlew jar && cd ..
python3 docs/verification/feature-matrix-20261104/run_matrix.py 26.2-fabric
python3 docs/verification/feature-matrix-20261104/run_matrix.py 1.21.1-fabric
python3 docs/verification/feature-matrix-20261104/legacy/run_legacy.py
```

`run_matrix.py` starts an isolated rendezvous server (loopback UDP, own data dir), a local HTTP
server for `update.json`, one host client and then joiner clients one after another, each with
its own game dir and its own probe jar. `run_legacy.py` runs one client at a time. Results,
logs and screenshots land in `build/feature-matrix-20261104/<run>/` (`results.json`).

## Two-client scenarios (`run_matrix.py`)

| Scenario | Joiner setup | Expected |
|---|---|---|
| remove | AppleSkin in `mods/`, `removeExtra=all`, telemetry allowed, 9.9.9 published | telemetry notice, update notice, confirm screen with the AppleSkin removal row, restart screen; after exit the helper deletes the jar, keeps a backup, journal `APPLIED` |
| rejoin | same game dir, new account | no removal left, joins the world, guest ESC menu |
| off | AppleSkin, `removeExtra=off`, telemetry declined, current version published | no update notice, joins directly, jar untouched |
| untick | AppleSkin, `removeExtra=all`, only a newer beta published | no update notice for a stable player; removal unticked, joins, jar untouched |

The host checks its ESC menu with the real guest connected and approves admission requests.
Telemetry: the collector must count exactly the two joins of the one joiner that allowed it.

## Results

| Check | 26.2 Fabric | 1.21.1 Fabric | 1.16.5 Fabric | 1.12.2 Forge | 1.7.10 Forge |
|---|---|---|---|---|---|
| Mod sync: removal confirmed → jar removed by helper, backup kept | PASS | PASS | — | — | — |
| Mod sync: rejoin after removal | PASS | PASS | — | — | — |
| Mod sync: `removeExtra=off` keeps the jar | PASS | PASS | — | — | — |
| Mod sync: unticked removal keeps the jar | PASS | PASS | — | — | — |
| ESC menu: PC entry (20×20, no overlap), hub, players, friends | PASS (host + guest) | PASS (host + guest) | PASS | PASS | PASS |
| Update notice shown for a newer release | PASS | PASS | PASS | PASS | PASS |
| No notice for the same version / beta-only for a stable player | PASS | PASS | — | — | — |
| Telemetry first-run notice and gate | PASS | PASS | PASS (decline) | PASS (decline) | PASS (decline) |
| Telemetry reaches the collector only with consent | PASS | PASS | — | — | — |

Runs: `26.2-fabric-20261104-123252`, `1.21.1-fabric-20261104-131145`, `legacy-20261104-184838`.
Legacy ESC checks reuse the existing probes from `session-ui-1165`, `ingame-menu-1122` and
`session-ui-1710` (single client, fake account session).

## Not covered

- NeoForge targets and the Fabric versions between 1.21.1 and 26.2 were not launched.
- Mod sync removal on 1.16.5 / 1.12.2 / 1.7.10 (no two-client probe for those loaders).
- Real NAT / Windows; everything runs over loopback on Linux.

## Findings

- Fixed: the join-request screen drew its panel under the background blur on 1.21.x/26.x
  (blurred text). The fix compiles; it has not yet been seen on a screenshot.
- Open (UX): when the plan only removes mods, the confirm screen still reads as a download
  ("скачать моды хоста?", "… 0 шт." — truncated on the right, "Скачать и установить"), and the
  removal row is labelled "обязательный".
- Test-rig lesson: a shared probe jar rebuilt by a later client corrupted the running host's
  class loading ("invalid LOC header"); each client now gets its own jar.
