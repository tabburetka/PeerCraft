# Player data during host handoff

All players load their own `playerdata/<UUID>.dat`. The name-based singleplayer shortcut is disabled only in the player-loading methods. `Data.Player` continues to be written by vanilla for the actual local owner's UUID; a guest sharing the owner's name cannot replace it. Minecraft 26.x uses `singleplayer_uuid` instead of embedded player NBT, so the integrated server's profile is updated to the UUID used for the local player.

The local player's identity is captured before world loading. A logged-in PeerCraft account uses its account UUID, as guests already do. Without a login, an existing world retains its previously migrated identity; a new world uses the Minecraft UUID. Legacy sessions without a UUID use vanilla's `OfflinePlayer:<name>` UUID.

## Existing worlds

Before chunks, statistics or advancements are loaded, `PlayerDataMigration`:

1. Exports embedded player NBT to its own UUID file if that file is absent. Ownership is established by UUID, never by the current opener's name. A transferred world's embedded data therefore stays with the previous host.
2. Copies the local owner's data to the selected account UUID if the destination is absent. Existing destination inventories and progress remain intact. The original player data and `level.dat` are retained.
3. Copies UUID-based statistics and advancements to absent destination files.
4. Updates matching ownership UUID references in world and entity regions, including Nether, End and custom dimensions. The entity's own UUID and other players' ownership references remain unchanged. Gzip, zlib, uncompressed and LZ4 chunks are supported, including external `.mcc` data.

`peercraft-player-identities.properties` records the completed UUID assignments and travels with the world archive. A launcher UUID already assigned to one PeerCraft account cannot be claimed again by a different account, including when host and successor share a launcher identity during local testing.

If the first account login finishes after world startup, archiving records a pending assignment without editing live player or region files. The next world startup finishes it before loading the successor. Authenticated accounts are marked as bound even when their UUID equals the launcher UUID; switching accounts cannot claim their progress. Anonymous self-assignments can bind once.

Edits are staged before any live file is replaced. Changed originals and a recovery journal remain under `.peercraft-player-migration/backup-*`. An interrupted commit is rolled back before the next migration attempt. An unreadable or unverifiable save aborts startup without guessing ownership. Backup directories are excluded from handoff archives; the identity record is included.

## Removing PeerCraft

The world still uses Minecraft's standard player NBT, region, statistics and advancement files. Normal vanilla saving retains the current local owner's embedded snapshot (`Data.Player`) or UUID pointer (`singleplayer_uuid` in 26.x). After a normal save and shutdown, vanilla can open the world without reading PeerCraft's migration metadata.

This does not guarantee that every remote player will load the same inventory after
removing the mod. Unlicensed PeerCraft players use their account UUID; vanilla
offline-mode servers use a name-derived UUID. With a different UUID, the player
can appear to have lost inventory, XP and progress even though the original files
remain in the host's world. Licensed players retain their Mojang UUID when joining
with vanilla online authentication; switching authentication modes can also change
their identity.

Before removing PeerCraft, stop and back up the host's world and explicitly
transfer affected players' account UUID data to the UUID their vanilla connection
will use. An automatic removal/export tool is not implemented yet. Never delete
the original files or overwrite an existing destination inventory. Reinstalling
PeerCraft and signing into the original account restores access to that UUID's
data, provided the world files have been retained.

Unlicensed accounts see this limitation before their first PeerCraft connection
or account-screen visit in each game launch. The account screen also offers
**Before removing PeerCraft** to reopen the explanation. Escape from the connection
notice returns without connecting; **Understood** continues the original join.

## Recovering a lost friend code

Password login now accepts either the friend code or the full account UUID. A
successful login saves a public recovery card in
`config/peercraft/account-backups/<account-uuid>.txt`. Logout removes the remembered
session, but keeps the recovery cards; switching accounts preserves separate
cards. **Copy recovery account ID** copies the UUID for storage outside the game
folder. Keep a separate copy of the card: deleting the entire installation can
also delete its local backup.

On a new device, enter that UUID and the existing password on the login screen,
using the same PeerCraft account server. The server still performs its normal
password challenge: the UUID is an identifier, not a secret recovery credential.
The card contains no password, password hash, session token or remember token.
It recovers a lost friend code; it cannot reset a forgotten password. Licensed
accounts sign in again with the same Mojang account instead.

## Validation

`PlayerDataMigrationTest` covers exporting old singleplayer progress, existing destination conflicts, transfer and return, account changes, legacy UUIDs, region ownership, external chunks and interrupted commits:

```sh
./gradlew :1.21.1-fabric:test --tests 'net.peercraft.world.*'
```

For live handoff testing, give the host and successor different inventories, XP and positions. Transfer to the successor, then transfer back; verify both players' state each time. Save and shut down normally before checking a copy of the world without PeerCraft.
