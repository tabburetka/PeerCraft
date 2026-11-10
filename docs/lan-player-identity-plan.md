# Unified LAN and PeerCraft player identity

## Goal

A player authenticated to the same PeerCraft account uses the same in-world UUID
over LAN and the PeerCraft tunnel. Verify ownership before player data loads.
A nickname or a client-supplied UUID is not proof of account ownership.

## Implementation sequence

1. **Separate proxy identity from LAN identity.** Resolve the existing P2P
   registry only for resolved loopback endpoints. Reject an external LAN endpoint
   with the same source port. Apply this to modern Minecraft and the 1.16.5,
   1.12.2 and 1.7.10 adapters.
2. **Add connection-bound LAN authentication.** Use a host challenge and a
   short-lived, one-use account proof verified by the rendezvous account service.
   Bind proof to the challenge and actual Minecraft connection. Never expose
   persistent session or remember tokens to the LAN host. Bound pending state,
   enforce timeouts and remove it on disconnect. Verification failure must not
   silently log a PeerCraft client in under a second UUID.
3. **Integrate login across versions.** Select the verified account UUID before
   player creation. Preserve the local owner's identity path. Check licensed
   account/Mojang UUID correspondence before modifying online-mode behavior.
   Preserve vanilla host and client compatibility. Forge 1.7.10 needs a separate
   transport adapter because it predates login plugin queries.
4. **Migrate old LAN progress explicitly.** Reuse migration transactions, backups
   and assignment records. A name-derived LAN UUID cannot automatically be claimed
   by an account: several people may have used the name. Require an explicit host
   assignment of the old UUID to a verified account. Migrate with the world stopped,
   before the next player-data load. Preserve source files and existing destination
   progress; surface conflicts rather than merging or overwriting inventories.
   Include statistics, advancements and ownership references.
5. **Validate and document.** Test spoofing, expiry, replay, concurrent logins,
   timeout, disconnect cleanup, same-name accounts, anonymous clients and old
   client/server combinations. Run LAN → PeerCraft → LAN with distinct inventories,
   XP and positions. Check handoff and vanilla compatibility. Compile modern and
   legacy adapters and perform live login checks per protocol generation.

## Current progress

Step 1 is implemented in source with regression tests. Steps 2–5 are pending.
The loopback check protects the existing P2P lookup; it does not authenticate
local processes and is not the new LAN proof mechanism. LAN connections still
use vanilla identity until the remaining integration is complete.

Validated step 1: five registry tests pass on Fabric 1.21.1, including LAN source
port collision, unresolved addresses and IPv6 loopback. Compilation passes on
Fabric 1.21.1 and 1.16.5 and Forge 1.12.2 and 1.7.10. These checks do not establish
live login compatibility or completion of LAN authentication.

Supporting recovery and disclosure are also implemented: password login accepts
the stable account ID, public recovery cards survive logout, and unlicensed
players see a removal/progress warning before their first PeerCraft connection
or account-screen visit per launch. This does not implement LAN authentication
or automatic removal/export. Account recovery cards recover a lost friend code,
not a forgotten password. See `player-data-migration.md` for the exact limits.

## Acceptance criteria

- A verified account keeps UUID and progress across LAN and PeerCraft joins.
- A different account with the same nickname cannot acquire that progress.
- Unverified UUIDs, expired proofs and replays never select an account save.
- Existing progress is never silently overwritten or assigned by nickname.
- Pending login state is bounded and cleaned up, including failed logins.
- Vanilla clients and hosts remain usable; unsupported authentication is visible.
