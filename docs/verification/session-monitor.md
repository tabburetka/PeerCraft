# Session monitoring and host controls

The pause menu keeps all vanilla controls and adds one full-width **Hand off hosting** button
below them. It opens the host picker, whose footer contains **Session monitoring** and Back.
The monitoring panel returns to that picker. There is no corner button and no replacement of
Minecraft's LAN/access controls. On short screens the shared layout moves the existing group
up just enough to fit the additional row.

The picker remains accessible without an active PeerCraft host session so monitoring is still
reachable. Actual candidate actions require an active host session, handoff enabled, and no
handoff already in progress. Account/successor eligibility and the existing confirmation gate
remain in force. The inactive picker explains why a transfer cannot start. Opening either
screen never starts a transfer automatically.

## Measurements

- One lightweight request/reply probe per second on an established game route. The last 30
  completed outcomes determine probe loss; a reply after three seconds counts as lost. The
  delay is application round-trip time, including route/processing delay, not ICMP latency.
- Jitter is the mean absolute difference between consecutive successful RTT samples.
- Logical transport bytes per second, game bytes waiting inside PeerCraft, and game
  retransmissions are observed in the bridge. OS/socket send queues are not claimed as measured.
  The view identifies the host as the source of per-player transport figures.
- Tick time is sampled on the game client thread every second and exchanged over the existing
  participant routes. Reports become unavailable after five seconds without a fresh sample.
- Mod Sync senders/receivers, HTTP mod downloads, and world-archive transfers expose their
  actual active state and remaining byte count. Completed and cancelled providers report no
  active files; registry references are weak.
- The file upload control cycles between 256 KiB/s, 1024 KiB/s and unlimited. Its aggregate
  payload budget applies to all local Mod Sync/world-archive senders, including repairs.
  Minecraft and diagnostic/control packets do not consume this budget. It is a session setting.
- The connection screen keeps the actual stage visible with elapsed time. The session view also
  exposes the last join stage. It does not substitute transport readiness for world entry.

## Host comparison

An explicit comparison performs a bounded common CPU task and direct UDP probe exchanges
between guests. Endpoints and unpredictable pair tokens are distributed over authenticated,
encrypted participant routes. Probe endpoints are taken from the negotiated physical direct
routes, not the logical routing keys. Probes cannot establish a Minecraft route or authorize a
new participant. A result requires reports from **both** directions. Failed/ineligible/older
participants remain incomplete; they are not given invented scores.

The supported room size is up to 32 players. Pair matrices are transmitted in bounded batches.
The test is rate-limited to one start per 30 seconds. Probes run for nine seconds, allow three
seconds for outstanding replies, and repeat final reports. Results expire after one minute or
when the participant roster changes.

The estimated world tick is current host tick multiplied by the ratio of CPU sample times.
The CPU sample uses the median of seven passes after three warm-up passes. This is explicitly
an estimate: it does not run a copy of the world on a guest, test disk performance or guarantee
performance with a different mod workload. Candidates require every relevant measured link,
completed samples, compatible monitoring, and handoff eligibility. Sorting considers both the
worst connection delay and the estimated 50 ms tick budget. The existing handoff picker still
requires an explicit player choice and confirmation.

## Verification

Tests live in `peercraft/src/test/java/net/peercraft/network/diagnostics/`:

- deterministic monotonic-clock loss/duplicate/jitter/rate tests;
- three actual loopback UDP sockets exchanging state and direct pair probes;
- deliberately dropped probe requests to exercise loss and the tentative transfer diagnosis;
- transfer lifecycle and a real elapsed-time aggregate upload budget check.

The isolated 26.2 game fixture asserts the bottom handoff entry and preserved native access button, actual navigation,
the handoff-to-monitoring round trip, and a visible disabled handoff action in an ordinary local world. Captures are under
`build/session-ui-262/game/screenshots/`. Its fixture classes are only bundled by the verification
init script, never the normal release build. This is local runtime verification, not a claim
that every supported version or an internet/NAT/multi-Minecraft session was visually tested.
