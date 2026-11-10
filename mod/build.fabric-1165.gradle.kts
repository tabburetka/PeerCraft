plugins {
	`java-library`
	`maven-publish`
	// Minecraft 1.16.5 is obfuscated and on official Fabric — same remapping Loom plugin as
	// build.fabric.gradle.kts (1.21.1-1.21.11). What differs here: the toolchain is pinned to
	// Java 8 (1.16.5's runtime), the mixin compatibilityLevel is rewritten down to JAVA_8, and
	// the language-incompatible / MC-coupled source is swapped for backport source roots
	// (src/client-1165) instead of src/main's modern-API originals.
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
// rejected at compile time, which is exactly the guard src/core and src/common rely on.
val targetRelease = (project.findProperty("java_version") as String).toInt()

java {
	toolchain.languageVersion = JavaLanguageVersion.of(21)
	withSourcesJar()
}

base {
	archivesName = "$modId-fabric-$mcVersion"
}

// Minecraft-coupled files whose modern-API originals in src/main can't compile against 1.16.5 —
// dropped from Stonecutter's generated output for this node and replaced by hand-written
// 1.16.5-API twins under src/client-1165. The Minecraft-agnostic code needs no twins: it lives in
// src/core + src/common, which are Java 8 source for every target.
val backportReplaced = listOf(
	"**/net/peercraft/PeerCraftCommon.java",
	"**/net/peercraft/client/gui/**",
	"**/net/peercraft/client/mixin/**",
	"**/net/peercraft/client/skin/**",
	// client/handoff: WorldArchiver (MinecraftServerAccessor + LevelResource) and
	// SuccessorLauncher (IntegratedServer/CompoundTag/GameType, modern GUI screens) need a
	// 1.16.5-API twin (see src/client-1165/.../client/handoff).
	"**/net/peercraft/client/handoff/WorldArchiver.java",
	"**/net/peercraft/client/handoff/SuccessorLauncher.java",
	"**/net/peercraft/client/handoff/SafeHandoffPlatform.java",
	"**/net/peercraft/mixin/**",
	// Mod sync client layer: twins use HttpsURLConnection for the Modrinth fast-path and target
	// the 1.16.5 GUI API — see src/client-1165/.../modsync.
	"**/net/peercraft/client/modsync/**",
)
// Stonecutter emits preprocessed sources into build/generated/stonecutter/main/java; the twin
// files above (records / modern GUI API) can't compile at --release 8 or against the 1.16.5
// API, so strip them from the generated tree before compileJava and let the
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
		java.srcDir(rootProject.file("src/core/java"))
		java.srcDir(rootProject.file("src/common/java"))
		java.srcDir(rootProject.file("src/client-1165/java"))
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

	// PeerCraft logs through the Log4j 2 API that Minecraft itself ships. Do not bundle
	// slf4j / log4j-slf4j-impl here: the 2.17.x binding calls
	// LoggerContextFactory.isClassLoaderDependent(), which the launcher's older Log4j on
	// 1.16.5 does not have, and the mod crashes with NoSuchMethodError during init.

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
	// Track the legacy resource filter change: the list theme now has a physical 1.16.5 twin.
	inputs.property("peercraftLegacyListTheme", true)
	val expandProps = mapOf(
		"version" to version,
		"minecraft_version_range" to project.property("minecraft_version_range"),
		// Fabric spells "[1.21.1,1.21.4)" as ">=1.21.1 <1.21.4".
		"fabric_minecraft_range" to (project.property("minecraft_version_range") as String)
			.removePrefix("[").removeSuffix(")").split(",").let { (lo, hi) -> ">=$lo <$hi" },
		"neoforge_version" to (project.findProperty("neoforge_version") ?: ""),
		"neoforge_loader_version_range" to (project.findProperty("neoforge_loader_version_range") ?: ""),
	)
	inputs.properties(expandProps)
	filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml")) {
		expand(expandProps)
	}
	// The shared fabric.mod.json pins the 1.21.x line (java >=21, a very recent loader). Rewrite
	// those predicates for 1.16.5 here so the shared file stays
	// untouched for the other targets — same line-filter approach as the mixin rewrite below.
	filesMatching("fabric.mod.json") {
		filter { line ->
			line.replace("\"java\": \">=21\"", "\"java\": \">=8\"")
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
		filter { line -> if (line.contains("PeerCraftWidgetTooltipAccessor")) "" else line.replace("\"JAVA_21\"", "\"JAVA_8\"") }
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

apply(from = rootProject.file("gradle/modsync-helper.gradle"))
