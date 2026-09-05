plugins {
	`java-library`
	`maven-publish`
	// Minecraft 1.16.5 is obfuscated and on official Fabric — same remapping Loom plugin as
	// build.fabric.gradle.kts (1.21.1-1.21.11). What differs here: the toolchain is pinned to
	// Java 8 (1.16.5's runtime), the mixin compatibilityLevel is rewritten down to JAVA_8, and
	// the language-incompatible / MC-coupled source is swapped for backport source roots
	// (src/main-java8, src/client-1165) instead of src/main's modern-API originals.
	id("net.fabricmc.fabric-loom-remap")
}

val modId = project.property("mod_id") as String
val modName = project.property("mod_name") as String
val mcVersion = stonecutter.current.version

group = project.property("maven_group") as String
version = project.property("mod_version") as String

// 1.16.5 shipped on Java 8 and mods target Java 8 bytecode (java_version=8 in this node's
// gradle.properties). We cross-compile: a modern JDK toolchain (same 21 as the 1.21.x nodes,
// so no extra JDK 8 has to be provisioned) with `options.release = 8`, which pins both the
// bytecode level and the language/API surface to Java 8 — records/var/switch-expressions are
// rejected at compile time, which is exactly the guard the src/main-java8 fork relies on.
val targetRelease = (project.findProperty("java_version") as String).toInt()

java {
	toolchain.languageVersion = JavaLanguageVersion.of(21)
	withSourcesJar()
}

base {
	archivesName = "$modId-fabric-$mcVersion"
}

// Files that are MC-agnostic but use Java 9+ language features (records, switch expressions)
// or the modern GUI/mixin API — dropped from Stonecutter's generated output for this node and
// replaced by hand-written Java 8 / 1.16.5-API twins under src/main-java8 and src/client-1165.
val backportReplaced = listOf(
	"**/net/peercraft/network/rendezvous/RendezvousProtocol.java",
	"**/net/peercraft/network/rendezvous/AccountProtocol.java",
	"**/net/peercraft/network/account/AccountClient.java",
	"**/net/peercraft/network/p2p/PeerAddress.java",
	"**/net/peercraft/client/account/AccountState.java",
	"**/net/peercraft/platform/Services.java",
	// record — the Java 8 twin in src/main-java8 provides it (mod-sync pulled this type into
	// PeercraftPlatform; only surfaced here once network/modsync stopped shadowing it).
	"**/net/peercraft/platform/services/PlatformMod.java",
	"**/net/peercraft/PeerCraftCommon.java",
	"**/net/peercraft/client/gui/**",
	"**/net/peercraft/client/mixin/**",
	"**/net/peercraft/mixin/**",
	// Mod sync: the loader-agnostic core is records / switch-expressions (compiled from
	// src/modsync-java8 instead), and the client layer twins drop the Java-11 HTTP fast-path
	// (P2P-only on Java 8) and target the 1.16.5 GUI API — see src/client-1165/.../modsync
	// and the four ModSync*Screen twins (already covered by the client/gui/** entry above).
	"**/net/peercraft/network/modsync/**",
	"**/net/peercraft/client/modsync/**",
)

// Stonecutter emits preprocessed sources into build/generated/stonecutter/main/java; the twin
// files above (records / modern GUI API) can't compile at --release 8 or against the 1.16.5
// API, so strip them from the generated tree before compileJava and let the src/main-java8 +
// src/client-1165 srcDirs provide the replacements. (stonecutter.filters didn't reliably drop
// them from generation, so this deletes them post-generate instead.)
val stripBackportTwins by tasks.registering(Delete::class) {
	delete(
		provider {
			val genRoot = layout.buildDirectory.dir("generated/stonecutter/main/java").get().asFile
			backportReplaced.flatMap { pattern ->
				fileTree(genRoot) { include(pattern.removePrefix("**/")) }.files
			}
		}
	)
}
stripBackportTwins { mustRunAfter(tasks.named("stonecutterGenerate")) }
tasks.named("stonecutterGenerate") { finalizedBy(stripBackportTwins) }
tasks.named("compileJava") { dependsOn(stripBackportTwins) }
tasks.named("sourcesJar") { dependsOn(stripBackportTwins) }

sourceSets {
	main {
		java.srcDir(rootProject.file("src/main-java8/java"))
		java.srcDir(rootProject.file("src/client-1165/java"))
		// Loader-agnostic mod-sync core hand-lowered to Java 8 (records -> classes,
		// arrow-switch -> colon-switch); shared verbatim with the 1.12.2 / 1.7.10 backports.
		// network/modsync/** is stripped from the generated tree above.
		java.srcDir(rootProject.file("src/modsync-java8/java"))
		java.srcDir(rootProject.file("src/fabric/java"))
		resources.srcDir(rootProject.file("src/fabric/resources"))
	}
}

repositories {
	exclusiveContent {
		forRepository {
			maven {
				name = "Sponge"
				url = uri("https://repo.spongepowered.org/repository/maven-public")
			}
		}
		filter { includeGroupAndSubgroups("org.spongepowered") }
	}
}

dependencies {
	minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
	mappings(loom.officialMojangMappings())
	modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_api_version")}")

	// Fabric Loader only started bundling org.slf4j around the 1.20 era; the 1.16.5-era
	// loader/API pairing does not put it on the classpath, and Minecraft 1.16.5 itself ships
	// Log4j 2 (2.17.1) without an SLF4J facade. The shared network/config code logs through
	// org.slf4j, so bundle the 1.7.x API (call-site compatible with the mod's basic Logger
	// usage) plus the Log4j-2 binding, so PeerCraft's logs land in the normal game log instead
	// of SLF4J's silent NOP fallback.
	modImplementation("org.slf4j:slf4j-api:1.7.36")
	include("org.slf4j:slf4j-api:1.7.36")
	include("org.apache.logging.log4j:log4j-slf4j-impl:2.17.1") { isTransitive = false }

	compileOnly("org.spongepowered:mixin:0.8.5")

	testImplementation("org.slf4j:slf4j-api:2.0.16")
	testImplementation("com.google.code.gson:gson:2.10.1")
	testImplementation("com.mojang:authlib:6.0.54")
	testImplementation(platform("org.junit:junit-bom:5.10.2"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The unit/integration tests are not per-Minecraft-version — they run once on
// :1.21.1-fabric:test against the modern (record-based, Java 21) source and cover the shared
// network/protocol/account core that this node reuses unchanged. They use var / List.of /
// Files.writeString / Optional.isEmpty and so can't compile at --release 8; skip them here.
tasks.named("compileTestJava") { enabled = false }
tasks.named<Test>("test") { enabled = false }

tasks.withType<JavaCompile>().configureEach {
	options.release = targetRelease
}

tasks.jar {
	from(rootProject.file("LICENSE")) {
		rename { "${it}_$modName" }
	}
}

tasks.processResources {
	val expandProps = mapOf(
		"version" to version,
		"minecraft_version_range" to project.property("minecraft_version_range"),
		"neoforge_version" to (project.findProperty("neoforge_version") ?: ""),
		"neoforge_loader_version_range" to (project.findProperty("neoforge_loader_version_range") ?: ""),
	)
	inputs.properties(expandProps)
	filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml")) {
		expand(expandProps)
	}
	// The shared fabric.mod.json pins the 1.21.x line (minecraft >=1.21.1, java >=21, a very
	// recent loader). Rewrite those three predicates for 1.16.5 here so the shared file stays
	// untouched for the other targets — same line-filter approach as the mixin rewrite below.
	filesMatching("fabric.mod.json") {
		filter { line ->
			line.replace("\">=1.21.1\"", "\">=1.16.5\"")
				.replace("\"java\": \">=21\"", "\"java\": \">=8\"")
				.replace("\"fabricloader\": \">=0.19.3\"", "\"fabricloader\": \">=0.15.0\"")
				// Fabric API only renamed its aggregator mod id from "fabric" to "fabric-api"
				// in 1.19.2 — on 1.16.5 the dependency has to be declared against "fabric".
				.replace("\"fabric-api\": \"*\"", "\"fabric\": \"*\"")
		}
	}
	// The shared mixin configs declare compatibilityLevel JAVA_21 for the 1.21.x builds; 1.16.5
	// is compiled at Java 8 and its bundled Mixin needs JAVA_8. Rewrite it here so the shared
	// resource files stay untouched for the other targets (mirror of the JAVA_25 rewrite in
	// build.fabric-unmapped.gradle.kts).
	filesMatching(listOf("peercraft.mixins.json", "peercraft.client.mixins.json")) {
		filter { line -> line.replace("\"JAVA_21\"", "\"JAVA_8\"") }
	}
}

publishing {
	publications {
		create<MavenPublication>("mavenJava") {
			from(components["java"])
			artifactId = base.archivesName.get()
		}
	}
	repositories {
	}
}
