# Interface acceptance — current evidence

Updated 2026-10-03. The requested outcome is the complete PeerCraft interface on every supported version/loader. This is an acceptance checklist, not a completion claim. The append-only port-status log contains historical intermediate failures and waits; use latest terminal logs rather than historical status statements.

| Requirement | Current evidence | Remaining acceptance |
| --- | --- | --- |
| All 32 build targets | Modern buildAll plus both Forge builds passed; final room-code matrix passed (`/tmp/ui-join-final-matrix.log`, 233 tasks) | 32 DEVELOP twins refreshed; deployment completed: `local-test/prism-update-20261003-174507/manifest.json` |
| Settings / account / auth / join palette | Actual 1.21.1 Fabric/NeoForge and 26.1 captures; legacy family evidence folders | Complete remaining family interactions and resize checks |
| Licensed account hides recovery ID and shows skin face | 1.21.1 licensed fixture screenshot, face region visible | Real session interaction remains separate from fixture rendering |
| Account Back / rename do not crash or grow parent chain | Callback guards and current captures; no claim of all-version interaction coverage | Actual callback chains passed on 1.21.1, 1.16.5 and both legacy Forge families using inert identities; real service login is not proven |
| Favorites removes full-width vanilla bands | Real 1.21.1 / 26.1 and older family Favorites captures | Retain regression checks during final review |
| Refresh keeps themed tabs and current page | Real 1.21.1 button callback capture on Games | 1.21.1 Discover/Games exact query + tab preservation asserted after real Refresh; further filter/family checks pending |
| Full-height lists and aligned searches | Main tab captures and legacy implementation | Populated requests/discovery/friends list scroll/resize validation |
| Small Feedback/Donate glyphs left of Join/Edit | Actual modern and legacy footer captures | Final compact viewport review |
| LAN and host transfer preserve world without particles | Current 1.21.1 LAN/picker/offer/observer/status world screenshots | Remaining physical families in-world checks |
| Host offer, observer, progress, reclaim, overwrite, stale screens | Current actual screens with inert handoff callbacks | Further renderer families; actual transfer is not proven by these fixtures |
| Mod Sync dialogs and progress | Current 1.21.1 gallery + 26.1 preparing/security; older progress/list captures | New preparing bar checked on all physical renderer families; remaining 26.x sync states need capture |
| One labeled screenshot per final screen | Draft gallery has 29 screen/state entries | Review all entries, replace stale images, mark final only after acceptance |
| Rollback-friendly commit and merge main | Current branch codex/steampunk-ui; mixed shared dirty tree | Selective UI commit, inspect dependencies and preserve other agents' files, then authorized merge |
| Prism updates | 100 compatible instances updated, checksums/backups verified; incompatible Forge 1.21.11 skipped | Final room-code fix deployed in latest report above |

Gallery: `local-test/ui-smoke/gallery-1211/index.html` (draft). No fixture performs world overwrite, starts real host transfer, or installs downloaded mods. Build checks exclude generic tests and cannot prove real authentication/network transfers.

## Commit boundary found during audit

Current GUI LoginByCode source imports AccountLoginIdentifier and invokes loginByIdentifier, which are uncommitted authentication changes outside the visual port. AccountProgressNotice is another untracked GUI dependency. A naive commit of GUI directories alone would not be a standalone buildable checkpoint. Before committing, separate authentication hunks or explicitly include reviewed necessary dependencies; preserve unrelated network/server/data work. No files were staged or reset during this boundary audit.

## Gallery file audit

All 29 linked screen/state PNGs exist and have valid PNG signatures. `local-test/ui-smoke/gallery-1211/capture-manifest.json` records their SHA-256 hashes. This verifies artifact integrity only. A fresh running-client capture (`/tmp/ui-requests-current.png`, UI_SMOKE_OPENED=requests at 18:22) confirms `requests.png` matches the current empty state. The earlier stale-heading inference was incorrect. Populated request capture and scroll validation remain required before final acceptance. The independently exported UI checkpoint is being built from `/tmp/peercraft-ui-checkpoint-source`; do not treat the running build as a passing check.

## Standalone checkpoint and populated requests

Commit `fe9b8c98` contains the independently built visual checkpoint (136 source/config files, no build/cache/server files). Modern buildAll and both Forge builds passed from the exported tree; generic tests excluded. Working authentication changes remain intact and unstaged. The 1.21.1 populated fixture displayed 36 inert requests with bounded long names and scrollbar. Actual Page Down callback advanced scroll 0 to 5; same-size reinit preserved 5 (`/tmp/ui-populated-requests-runtime.log`). Gallery contains populated and scrolled captures. No accept/decline callback was invoked.

## Main merge verified

Merge `199e4acc` joins main and the visual checkpoint. Four UI conflicts were resolved with the current all-version implementations; obsolete main-only 1.21.1 helper gating and the 1.16.5 list-mixin exclusion were not restored. Main account queue changes and focused regression test were retained. The resolved tree passed modern buildAll, both Forge builds, and AccountClientQueueTest. Both branch refs point at the merge; all working files were preserved.

## Populated browser runtime wait

The new friends/discover fixtures compiled successfully. Current test-client session is live, but the requested friends fixture has not been consumed yet. Thread dump `/tmp/ui-browser-thread-dump.txt` shows Render thread waiting in vanilla Minecraft.addInitialScreens on CompletableFuture.join, while Download-1 is reading HTTPS in YggdrasilMinecraftSessionService.fetchProfileUncached. No PeerCraft GUI callback appears in that wait. This is a verified runtime wait, not proof of populated browser acceptance; no restart was issued and the pending command was preserved.

## Populated discovery capture

The Mojang startup wait finished and both fixture commands were consumed. Discovery screenshot shows bounded rows, themed search field/actions and scrollbar above the footer. Added `discover-populated.png` to the gallery. Friends fixture was replaced by normal periodic polling before capture; no populated-friends acceptance is claimed. Test-only fixture now delays periodic polling during the short check; revised uiSmokeClasses compiled successfully.

## Populated browser interactions

Current fixture runtime `/tmp/ui-browser-scroll-runtime.log`: Friends has 36 entries, Page Down moved scroll 0 to 2 and same-size reinit preserved 2. Discovery has 36 entries; Page Down/reinit callback results are recorded in the same log. Gallery includes populated and scrolled screenshots. These are actual callbacks, not OS key input. No add/remove/connect button was pressed.

## Additional handoff world captures

Current 1.21.1 test-world captures confirm reclaim and stale panels preserve the world without particles. Replaced corresponding gallery images. Actual overwrite-button callback opened PeerCraftConfirmScreen without invoking confirmation or mutating any world. Its oversized empty body was found in visual review; four physical confirmation implementations now measure wrapped text for desired height. Full package builds are running; compact runtime recapture is still pending.

## Compact confirmation and stale-world panels

All four renderer families now derive confirmation and stale-warning panel heights from wrapped body lines plus their two footer actions. Forge 1.12.2 build passed (`/tmp/ui-modal-final-1122.log`, 45s) and Forge 1.7.10 build passed (`/tmp/ui-modal-final-1710.log`, 57s). Modern full matrix is still running; actual compact modal recapture started in `/tmp/ui-compact-modal-runtime.log`. These new source changes are not yet committed/deployed, so earlier Prism deployment does not include this last refinement.

## Compact runtime issue found

The compact stale warning was rendered in a loaded 1.21.1 world (`/tmp/ui-stale-compact.png`). Visual review found an unnecessary scrollbar: prewrapped paragraphs were wrapped again by drawBody, while sizing counted only the original lines. All four stale-warning implementations now pass the raw paragraph and measure the exact renderer wrap width. New full matrix builds are running (`/tmp/ui-stale-wrap-final-*.log`). DEVELOP jars regenerated before this finding are superseded; Prism apply was not executed. Final deployment must use builds after this fix.

## Forge 1.12.2 in-world evidence

The new isolated flat test world loaded successfully. LAN and reclaim panels were captured over the actual world without particles (`evidence-1122-20261003/lan-world.png`, `reclaim-world.png`). LAN still has excess empty space below its controls in the offline state; do not treat its geometry as final. Vanilla tutorial toast is present in the test capture and does not belong to PeerCraft. No LAN start, transfer or overwrite was invoked.
