plugins {
	`java-library`
	`maven-publish`
	// Minecraft 26.1 onwards ships unobfuscated — FabricMC stopped publishing mappings from that
	// version. The non-remapping Loom plugin (net.fabricmc.fabric-loom, NOT -remap) builds
	// against Minecraft as-is: no mappings() line, plain implementation() instead of
	// modImplementation(), and the jar task is the final artifact (nothing to remap).
	// build.fabric.gradle.kts is the obfuscated-era (1.21.1-1.21.11) counterpart.
	id("net.fabricmc.fabric-loom")
}

val modId = project.property("mod_id") as String
val modName = project.property("mod_name") as String
val mcVersion = stonecutter.current.version

group = project.property("maven_group") as String
version = project.property("mod_version") as String

// Unobfuscated Minecraft (26.1+) requires Java 25.
val javaVersion = 25

java {
	toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
	withSourcesJar()
}

base {
	archivesName = "$modId-fabric-$mcVersion"
}

sourceSets {
	main {
		java.srcDir(rootProject.file("src/fabric/java"))
		resources.srcDir(rootProject.file("src/fabric/resources"))
	}
}

repositories {
	// Mixin annotations are needed at compile time (mixin classes live in the shared src),
	// even though Loom's own bundled Mixin runtime applies them at runtime.
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
	// No mappings() — Minecraft 26.1+ is shipped unobfuscated.
	implementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
	implementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_api_version")}")

	compileOnly("org.spongepowered:mixin:0.8.5")

	// Test-only: these are on the compile classpath transitively via Minecraft's own libraries at
	// game runtime, but the plain JUnit `test` task needs them declared explicitly. Versions match
	// what this Minecraft version itself bundles.
	testImplementation("org.slf4j:slf4j-api:2.0.16")
	testImplementation("com.google.code.gson:gson:2.10.1")
	testImplementation("com.mojang:authlib:6.0.54")
	testImplementation(platform("org.junit:junit-bom:5.10.2"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
	useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
	options.release = javaVersion
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
	// The shared mixin configs declare compatibilityLevel JAVA_21 for the obfuscated-era builds;
	// unobfuscated Minecraft (26.1+) is compiled at Java 25 and needs JAVA_25. Rewrite it here so
	// the shared resource files stay untouched for the 1.21.x targets.
	filesMatching(listOf("peercraft.mixins.json", "peercraft.client.mixins.json")) {
		filter { line -> line.replace("\"JAVA_21\"", "\"JAVA_25\"") }
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
		// Add repositories to publish to here.
	}
}
