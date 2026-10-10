# Russian progress/recovery UI probe, Forge 1.12.2

Run from `peercraft-forge-1122`:

```
./gradlew -I ../docs/verification/progress-ui-1122/probe.gradle runClient
```

Requires a graphical Linux session. The probe uses an isolated game directory at
`/tmp/peercraft-progress-ui/game`, disables P2P startup and directs account requests to loopback port 1, creates no account
and opens no saved world. It renders six fixture screens at 640×480 / GUI scale 2,
checks button bounds, saves screenshots and closes its client automatically.
`PROGRESS_UI_DONE` confirms the sequence completed. This does not test real login,
mail delivery, world transfer or the other GUI adapters.

The saved fixture snapshots show: reset address, reset code/password, binding
address/password, binding code, explicit UUID confirmation, and progress tools.
