# Official skins

PeerCraft preserves profile properties when applying the world identity. Clients restore missing
signed textures using the exact UUID at Mojang's HTTPS session service. No nickname lookup,
identity reassignment, third-party skin provider, or server protocol change is involved.
An authenticated licensed PeerCraft account has the official Mojang UUID; anonymous offline
nickname UUIDs cannot establish that identity and are deliberately skipped.

Lookup runs on two daemon workers with a bounded queue and a 512-entry cache. Successful results
expire after 15 minutes, failures after one minute. Connect/read timeouts are 2.5 seconds each;
a missing/unavailable profile uses the vanilla skin result. The original signature is retained
and handed to Minecraft's normal texture loading/verification. World admission never waits on it.
Both viewers need this PeerCraft update to restore skins absent from the server's profiles.

## Verification

Run deterministic tests (six per target, covering profile copy and response validation):

```sh
cd peercraft
./gradlew :1.21.1-fabric:test :26.2-fabric:test --tests 'net.peercraft.network.skin.*' --tests 'net.peercraft.client.skin.*' --offline --configure-on-demand --max-workers=2
```

Run the isolated native 1.21.1 client fixture:

```sh
cd peercraft
./gradlew :1.21.1-fabric:runClient -I ../docs/verification/official-skins/probe.gradle --offline --configure-on-demand --max-workers=1
```

The fixture uses a fresh game directory under `build/official-skin-runs`, without user accounts
or worlds. It requests a known public official UUID with no texture property, requires a loaded
`skins/` texture and `secure=true`, and verifies the initial lookup does not block the render
thread for 100 ms. Require `OFFICIAL_SKIN_PROBE_PASSED`; Gradle success alone is insufficient.
This fixture needs network access to Mojang and a working graphical environment.

2026-10-12: native 1.21.1 fixture passed with `secure=true` and downloaded texture
`minecraft:skins/0ee174b187f8db2adca845b8e6e866c2d12397b1`. Six deterministic tests passed
on each of 1.21.1 and 26.2. Builds are checked separately from native behavior; this fixture
does not prove a two-client multiplayer session, every loader/version, or launcher deployment.
