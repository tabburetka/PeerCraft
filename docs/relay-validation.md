# TURN fallback: validation and release gates

Relay remains disabled by default. A successful build or simulated provider does
not authorize public enablement or prove Minecraft runtime behavior.

## Local evidence

| Requirement | Evidence | What remains |
| --- | --- | --- |
| Direct route probing, bilateral nomination, late success, changed NAT port | DirectConnectivityCoordinatorTest; PunchCoordinatorTest | Closed tests on real LAN, IPv6 and NAT combinations |
| STUN/TURN authentication and bounded UDP framing | StunCodecTest, TurnUdpClient tests, SecureDatagramChannel tests | Actual Cloudflare credentials and both UDP ports |
| Two independently encrypted TURN peers | RelayPeerTransportIntegrationTest uses real local UDP TURN allocations | Real Cloudflare endpoint and WAN latency |
| Allocation rotation and temporary renewal failure | RelayPeerTransportIntegrationTest verifies fresh allocation, continuity, overlap and preserved data | Real 15-minute credential cycle, both endpoint rotations |
| Mod sync before Minecraft login | RelayModSyncIntegrationTest verifies manifest and a missing mod's SHA-512 over TURN | Fabric/NeoForge 1.21.1 gameplay/install/restart flow |
| World archive transfer | RelayWorldTransferIntegrationTest verifies 1.2 MB archive and authoritative DONE over TURN; budget shutdown during an 8 MB transfer reports immediate failure and preserves the source snapshot | Large real world, interrupted transfer, return hosting |
| Peer protocol routing and handoff retain/release | PeerRouteIntegrationTest routes E2/E3/E4/E7/E8, checks retained routes, rejects source stop until broker retention acknowledgement, and verifies release happens once | Both actual handoff generations during source world stop |
| COMMIT/ABORT, staging and world safety | Existing handoff flow/archive tests | End-to-end relay handoff, interrupted relay and preserved user worlds |
| 800 GB guard, stale analytics, revocation and restart | RelayBrokerTest with controlled usage/provider failures, pinned subscription parsing and automatic period rollover only after complete fresh usage | Complete live account analytics matched to billing period |
| Legacy client behavior and Java 8 core | Legacy compilation and compatibility protocol tests | Mixed client versions with real rooms and player sessions |
| Connection mode/disconnection UI | Cross-version compilation; original concept image | In-game rendering, retry/navigation and quota shutdown |
| Ubuntu deployment | systemd unit syntax validated; server/proxy templates prepared | Existing service/data paths, nginx configuration validation and install on user's laptop |

Named test suites are evidence for their actual assertions only. The local TURN
fixture replaces Cloudflare and the broker fixture replaces HTTPS/provider API;
it does not demonstrate public TLS, account billing or external provider behavior.

The current retention checks include pending and rejected broker acknowledgement,
operation identifier mismatch, one-time release, and continued encrypted UDP
delivery after release. RelayBrokerTest additionally simulates credential rotation
and repeated retain calls across forty minutes: the original hold deadline still
expires after the room changes host. These checks passed locally; they do not yet
exercise a complete Minecraft host transfer or the recovery UI.

Legacy HandoffCoordinator now waits for retention on its worker before OFFER,
and HandoffClientAgent waits before consent/migration callbacks. Focused
HandoffLoopbackIntegrationTest checks that rejected host preparation sends no
OFFER and starts no transfer, and that rejected joiner retention declines without
showing consent. Both checks passed on Fabric 1.21.1; live legacy relay handoff
and cross-version validation of these latest changes remain pending.

An earlier matrix verification completed all tasks but reported one account-server
startup timeout on NeoForge 1.21.5. A subsequent isolated AccountEndToEndTest run
and the complete NeoForge 1.21.5 test task both passed. The original startup cause
is unproven; the fixture now records subprocess output and reports its exit state
on failure. This must not be described as a fully green single matrix run.

The subsequent matrix run after legacy retention changes completed all remaining
tasks but reported one TURN Allocate timeout on NeoForge 26.1. Its isolated TURN
tests and then its complete test task passed. The local single-client TURN fixture
now rejects foreign malformed UDP without terminating its reader, with a dedicated
regression check; the original timeout cause remains unproven.

Temporary broker failure while releasing a handoff hold now queues a retry instead
of disconnecting a valid game route. The local two-peer TURN integration test verifies
that encrypted application data still flows before the retried release succeeds.
Non-transient broker policy failures still close the route with their own reason.

After the release-retry change, `buildAll -x test` passed for the modern matrix
(233 tasks), and Forge 1.12.2 plus 1.7.10 `compileJava` passed. Focused current
NeoForge 1.21.1 tests for relay transport, mod sync, world transfer, legacy handoff
and peer routing passed; current Fabric 1.21.1 relay transport tests passed too.
This records compilation across versions and selected runtime-free tests, not a
new full matrix test run or closed gameplay verification.

## Required closed checks before public enablement

- Preserve existing server accounts/handoff journal and Minecraft worlds; keep
  the old JAR and configuration for rollback.
- Configure server-only provider secrets and complete account-wide usage access.
  Confirm actual subscription start/end dates in UTC.
- Validate public HTTPS and trusted proxy IP forwarding; do not expose local
  analytics, environment files or provider responses.
- Verify direct success never issues TURN credentials; test loss/delay/one-way
  probes and record the per-candidate failure diagnostics.
- Establish real fallback between two registered clients after supported direct
  routes fail. Test old/new clients, cancellation and rejoining, host consent,
  full room/access rejection, and forged peer/address messages.
- Run game, mod sync on Fabric and NeoForge 1.21.1, large world transfer, explicit
  handoff and return handoff. Stop relay mid-transfer and verify ABORT/COMMIT and
  world preservation. Verify retained channel survives Minecraft TCP closure.
- Observe both endpoint allocation renewals and the 30-second overlap. A live
  connection failure uses ordinary rejoin, not automatic host/game recovery.
- Inject budget/stale-analytics/revoke failures in a controlled test configuration
  and confirm new plus active relay sessions close while direct sessions work.
  Do not modify the real usage journal to bypass the guard.
- Measure account egress before/after representative game hours and world/mod
  transfers, including protocol overhead. Publish an estimate based on these
  measurements, not an assumed bandwidth per player.
- Inspect actual in-game status/reason/retry screens on the supported UI families.
- Replace the temporary Quick Tunnel hostname with stable HTTPS before release.

The 800 GB shutdown is a best-effort safeguard. Delayed analytics/revocation and
modified clients prevent a guarantee of zero charges. Do not enable publicly
until these gates have recorded concrete results.
