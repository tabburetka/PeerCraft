# Progress preservation and email recovery

Scope: preserve existing owner and guest progress during updates, anonymous-to-account
conversion and host handoff; bind verified email and recover unlicensed accounts without
changing UUID. Source files, destination conflicts and world-local metadata must survive.

## Required work and evidence

- [x] Require an authenticated account before all new PeerCraft joins, including public games
  and code entry, with UI navigation to login and server enforcement.
- [x] Add explicit host-approved guest UUID assignment on a stopped, backed-up world.
  Do not infer ownership from nickname. Reject destination conflicts and account reassignment.
- [ ] Preserve inventory, ender chest, XP, position, stats, advancements, pet ownership,
  owner snapshot and identity metadata; verify repeated migration is idempotent.
- [ ] Preserve identity and world extension files through handoff and return.
- [x] Bind verified email for unlicensed accounts; expire and consume challenges once;
  limit requests and guesses, cap pending state, conceal account existence.
- [x] Restore credentials on the same UUID, durably persist them and revoke sessions,
  remembered logins and pending password login challenges.
- [x] Send email asynchronously with a bounded queue, timeouts and safe configuration.
- [x] Use HTTPS for recovery credentials; add in-game binding and recovery screens across
  modern and legacy adapters. Failure must not silently create another account.
- [x] Document migration, recovery, server mail setup and rollback, including old accounts.
- [ ] Test backend and migrations, compile representative modern/legacy adapters and
  build-only minor variants. Record exact results rather than extrapolating.
- [ ] Live game gates: old world update, guest registration, distinct player states,
  handoff and return, reconnect, same-name licensed/unlicensed accounts, interrupted
  migration, destination conflicts, and vanilla compatibility on copies.
- [ ] Verify real mail delivery and complete recovery using the deployed HTTPS endpoint.

The goal is incomplete until the integration and runtime gates above are verified.

## Evidence on experiment, 2026-10-07

- Backend account, recovery, HTTPS client contract and join integration: 100 tests,
  zero failures. Production client transport binds and resets through a trusted local
  TLS endpoint; hostname mismatch and plain HTTP are rejected. SMTP refuses delivery
  when STARTTLS is unavailable and exposes neither AUTH nor message data to that peer.
- Fabric 1.21.1 world migration: 27 tests, zero failures, including guest assignment,
  complete backup, destination conflicts, native world lock, symlink lock rejection,
  authenticated guest metadata, inventory preview and interrupted migration recovery.
- NeoForge 1.21.7 account end-to-end: 6 tests, zero failures on the focused rerun.
  The original matrix run hit a UDP port reservation race (`Address already in use`).
- `buildAll -x test` succeeds across every registered modern variant. Forge 1.12.2
  and 1.7.10 native adapters compile. Compilation is separate from game-session proof.
- Full backend suite had 237 tests with two failures in RelayServerIntegrationTest
  waiting for the relay listener disabled by concurrent rollout work; one coturn test
  skipped. The recovery and room-join tests pass independently.
- New registration persists account UUID/password data before acknowledging success;
  unavailable storage yields no new usable identity. A corrupt existing database
  stops startup and is preserved. Mail setup examples and rollback instructions:
  [progress-and-email-setup.md](progress-and-email-setup.md).

Remaining gates: live Minecraft UI, old-world update and two-client handoff/return;
real provider delivery after SMTP/HTTPS deployment. Direct LAN continues to use vanilla
identity; its separate connection-bound authentication plan remains pending.

### Archive/install/return regression

`PlayerDataMigrationTest.migratedGuestAndDistinctOwnerSurviveProductionArchiveInstallAndReturn`
passes on Fabric 1.21.1. It runs the production `WorldArchiveFiles.write`,
`WorldInstall.unpack` and `WorldInstall.replace` twice, with a guest-to-account
migration and new-host/return identity preparation. It checks exact player NBT
(including inventory, EnderItems, XP and position), stats/advancements, pet owner,
original owner marker, level.dat, identity metadata and plugin data. Local full
backups are excluded from the transfer. This proves the file pipeline, not a live
Minecraft login, network handoff or vanilla save after removal.

### Forge 1.12.2 live UI probe

An isolated Forge 1.12.2 client launched successfully with six Russian UI fixtures
at 640×480 framebuffer / 320×240 GUI scale. It rendered binding and reset address/code
screens, the full transfer confirmation and the progress tools menu, checked button
bounds and exited normally (`PROGRESS_UI_DONE`). Screenshots are under
`docs/verification/progress-ui-1122/`. Mail fields and action buttons fit; old and
account UUIDs are visible in the confirmation. This uses fixture state, not a real
account login or world join. Forge 1.7.10 and modern live UI checks remain pending.

The probe also exposed raw newlines breaking legacy `.lang` values. Both Forge
generators now escape property values, write explicit UTF-8 and verify every generated
translation through the same Properties parser used by PeerCraftLang. This preserves
all confirmation lines and prevents format regression at resource generation.

### Native save/load and account handoff-copy/return probe

Forge 1.12.2 completed `WORLD_PROGRESS_PROBE_DONE` in a real client with an isolated
world. Anonymous → account reopen retained inventory, ender chest, XP, position,
statistics, completed advancement and pet ownership. Shutdown used production
WorldArchiver.saveAndStop and confirmed server/file-I/O termination. Original source
bytes, account playerdata, Data.Player and a full backup were checked.

The same run archived/installed through the production handoff file pipeline, opened
as a second account with the same name, created distinct progress, then installed a
return copy and reopened as the former host. Its state survived and the offline
successor playerdata stayed byte-identical. The run and reproducible probe are in
`docs/verification/world-progress-1122/`. Synthetic sessions were used: account-service
authentication, two concurrent clients and network handoff are still separate gates.

The native successor scenario exposed an omitted binding when the shared launcher
UUID already belonged to the old host. Migration now records the new verified account
as bound while retaining the original assignment and preserving old inventory.
The focused regression verifies that neither account is offered for guest reassignment.

### Successful SMTP delivery contract

SmtpTlsDeliveryTest passes for both implicit TLS and STARTTLS. A local certificate
trusted only by the test context is used; the real SmtpMailSender authenticates after
TLS, sends to the configured recipient and preserves the UTF-8 subject, recovery code
and UUID wording through MIME encode/decode. The test restores the original TLS
context. This complements the refusal-to-send-without-STARTTLS test; real provider/DNS
and external mailbox delivery still require configured deployment.

After the successor-binding fix, all 29 focused world tests pass on Fabric 1.21.1,
`buildAll -x test` succeeds, and Forge 1.7.10 compiles. The native 1.12.2 probe used
this updated source and completed its save/load/outbound-copy/return checks.

### Combined recovery and rendezvous regression

The combined backend run completed successfully with 102 tests, zero failures,
zero errors and zero skips. The selection was `net.peercraft.rendezvous.account.*`,
`RendezvousServerIntegrationTest` and `PublicGameBrowserIntegrationTest`.
This includes both successful SMTP TLS modes in the same run as the HTTPS recovery
contract, verifying that their temporary TLS contexts are restored and do not break
the other transport tests. Results were counted from the current JUnit XML files
under `rendezvous-server/build/test-results/test`.

### Native world progress with real registration

The Forge 1.12.2 probe also completed with two accounts registered through production
AccountClient against an isolated local rendezvous server. Both accounts had the same
display name and distinct server-issued UUIDs. Anonymous-to-account reopening retained
the checked native player state; the successor and returned owner retained their
distinct saves. Evidence: `verification/world-progress-1122/authenticated-run.log`.
This closes the synthetic-session limitation for registration and native identity
selection, while two-client network handoff remains unverified.

### Additional recovery and native networking checks

All 11 EmailRecoveryServiceTest cases passed with a 512 MiB Gradle heap, a single
worker and a 256 MiB test heap. The additional case verifies that changing the
mailbox invalidates an existing old-address reset, persists the new email while
retaining UUID/friend code/password, and notifies the former address.

The two-process Forge 1.12.2 probe now limits client heaps to 768 MiB, direct buffers
to 640 MiB, CPU workers to two, and view distance to four. Gradle runs are sequentially
started with 384 MiB heaps and one worker. Smaller direct-buffer caps (128/256 MiB)
caused isolated client allocation failures during renderer construction; these are
test configuration failures, not completed gameplay checks. With the adjusted cap,
both clients started and joined the direct PeerCraft transport with over 5 GiB of
available system memory observed. Forge then rejected native login with an
unregistered-packet encoder exception. Network progress and handoff remain unproven;
the probe now captures failed packet classes for the next diagnostic run. All owned
clients and the local test server were stopped after this attempt.

### Successful two-client native join/reconnect

The next diagnostic run identified SPacketLoginSuccess as the packet rejected by
Forge's encoder: the Forge handshake changed channel state before its queued write.
The 1.12.2 login adapter now orders that handshake on the channel event loop after
the queued login-success write for verified PeerCraft connections.

The two-client test then completed `NETWORK_PROGRESS_PROBE_DONE` for both roles,
with normal exits. It checked native UUIDs against separately registered accounts,
guest disconnect/reconnect, retained guest inventory/ender chest/XP, distinct owner
state, separate playerdata and canonical guest binding after production shutdown.
Evidence is in `verification/network-progress-1122/passed-host.log` and
`passed-guest.log`. The account display names were identical and launcher names
different because Forge rejects simultaneous duplicate launcher names. Heap/buffer
and build-worker limits kept over 4 GiB system memory available during this run.
Network handoff and return remain separate outstanding checks.
The guest log contains queued entity-packet null-pointer errors after the fixture's
immediate unload during the first disconnect. The verified progress assertions
passed, but a clean disconnect remains to be rerun. The fixture now waits for native
unloading after closing the channel instead of clearing the world immediately.

### Network handoff fixture preparation

The native reconnect rerun again passed both progress/UUID assertions and normal
exits. Waiting for native disconnect unloading did not eliminate queued entity
packet errors; this remains a recorded Forge disconnect limitation, not a clean-log
gate. No additional progress loss was observed by the assertions.

A separate fixture under `verification/network-handoff-1122` now exercises the
production SafeHandoffSession, native consent, successor startup/room registration,
original-owner network reconnect and return handoff. It uses the player picker's
stable world ID path and verifies both bound UUIDs and native/offline player state.
It is prepared for runtime verification; do not treat the script's existence or
compilation as successful network handoff evidence.

## First native attempt

Two clients reached explicit successor consent, then preflight aborted on the
Forge builtin `Minecraft` pseudo-JAR. Platform classification is now case-insensitive;
all eight HostManifestCapture tests passed, including a missing ordinary mod still
rejecting preflight. No successful handoff is claimed. All owned test processes were
stopped. The adjusted fixture bundles loaded production/probe classes into a real JAR
for strict manifest hashing; its bundle builds successfully. Native transfer and
return verification remain pending.
