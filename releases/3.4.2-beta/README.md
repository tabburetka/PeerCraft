# PeerCraft 3.4.2 Beta release artifacts

This directory contains 32 normal client JARs and the rendezvous server fat JAR from a clean build, plus SHA-256 checksums, upload metadata and automated test totals. Development and sources JARs are excluded.

Client version: 3.4.2. The standalone rendezvous artifact retains its existing filename/version, rendezvous-server-1.0.0.jar; its bytes were rebuilt from the current sources.

See [the English changelog](../../docs/releases/3.4.2-beta.md). CurseForge metadata uses Beta, the exact Minecraft version and loader, Client environment, and required Fabric API / MixinBooter / UniMixins dependencies where applicable.

## Validation

- Clean modern matrix assembly: 30 JARs, 265 executed Gradle tasks.
- Clean Forge 1.12.2 and 1.7.10 builds: two JARs.
- Client tests: 362, zero failures/errors, 8 skips (optional external fixtures, TURN/relay environment and rendezvous subprocess tests).
- Server tests: 254, zero failures/errors, 3 skips (two intentionally disabled relay rollout scenarios and one coturn environment test).
- Forge 1.7.10 mod synchronization tests: 15, zero failures/errors, one optional real-mod fixture skip. Forge 1.12.2 build has no automated test suite configured.
- Each client archive was checked for integrity, the current default endpoint, settings and player migration classes. Fabric/NeoForge version metadata was checked.
- SHA256SUMS covers all 33 JARs. These checks do not establish live Minecraft or cross-provider networking acceptance.

## Server deployment

The new server JAR was installed on laptop-server in /home/ikuku/peercraft_server, under rendezvous.service. The JAR hash matched locally and remotely. The service restarted, bound UDP 51000 and loaded all 373 existing accounts. The account database was byte-identical immediately after restart. A live UDP room-list request received the expected protocol response.

The previous JAR and data archive were retained at backups/release-3.4.2-20261010T192657Z on the server. Relay remains disabled. Server configuration, account data and deployment secrets are not included here.

curseforge-uploads.json records successful API upload IDs; API acceptance does not confirm moderation approval or public availability.
