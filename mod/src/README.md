# Source roots

Every target compiles a stack of these roots. A file lives in exactly one of them.

| Root | What goes here | Compiled by |
|---|---|---|
| `core/java` | Minecraft-agnostic logic: P2P transport, protocols, rendezvous/account clients, config, mod-sync and handoff engines. No `net.minecraft`, no authlib, no loader API. | every mod target, `servers/rendezvous`, `servers/relay` |
| `common/java` | Minecraft-agnostic client glue that calls into classes each platform provides (`client.modsync`, `SafeHandoffPlatform`, `WorldArchiver`, …). | every mod target |
| `main/java` | Modern client layer (GUI, mixins, skins, mod-sync UI) for 1.21.x / 26.x. Stonecutter-preprocessed: `//? if` gates select per-version API. | Fabric + NeoForge 1.21.1+ |
| `fabric/`, `neoforge/` | Loader entrypoints, platform service, metadata. | the matching loader |
| `client-1165/java`, `fabric-1165/` | 1.16.5 replacements for the `main/java` client layer. | Fabric 1.16.5 |
| `legacy-forge/java` | The Forge client layer (GUI, mixins, mod sync, handoff) for every RetroFuturaGradle backport, version-gated (see below). | `legacy/forge-1122`, `legacy/forge-1710` |
| `modsync-helper/java` | The dependency-free Java 8 helper that installs downloaded mods after the game exits. | every mod target (embedded) |
| `test/java` | Unit and integration tests. Run on `:1.21.1-fabric:test` (and every other modern node). | modern nodes |

## Rules for `core` and `common`

- **Java 8 source.** The legacy targets run on Java 8, so these roots use no records, switch
  arrows, `var`, `List.of`/`Set.of`/`List.copyOf`, `String.isBlank`, `InputStream.readAllBytes`
  and so on. The 1.16.5 node and both Forge backports compile them with `--release 8`, which
  fails on any of these — run `./gradlew :1.16.5-fabric:compileJava` before pushing a change here.
- **No Stonecutter gates.** These roots are added as plain source directories and are not
  preprocessed. Anything that differs per Minecraft version belongs in a platform root, behind
  an interface (see `AccountClient.MojangSessionJoin` for the pattern).
- Code that needs `net.minecraft.*`, authlib or a loader API goes in `main/java` and its
  per-era replacements, never here.

## Gates in `legacy-forge`

Stonecutter only drives the Fabric/NeoForge build, so the legacy tree has its own line-based
preprocessor (`mod/gradle/legacy-preprocess.gradle`) with the same look:

```java
//? if >=1.12 {
PasswordField(int componentId, FontRenderer font, int x, int y, int width, int height) {
//?} else {
//~ PasswordField(FontRenderer font, int x, int y, int width, int height) {
//?}
```

- Conditions: `<`, `<=`, `>`, `>=`, `==`, `!=` against the project's `minecraft_version`, joined
  with `&&` / `||`. Chains use `//?} else if <cond> {`.
- Inactive lines are stored behind `//~ ` (`//~` alone for a blank line). The checked-in files
  keep 1.12.2 active; each build writes the resolved copy to
  `build/generated/legacy-forge/java` with marker lines kept, so line numbers match.
- A file that exists only for some versions starts with `//? file <cond>`.
