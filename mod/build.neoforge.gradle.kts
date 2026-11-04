plugins {
	`java-library`
	`maven-publish`
	id("net.neoforged.moddev")
}

val modId = project.property("mod_id") as String
val modName = project.property("mod_name") as String
val mcVersion = stonecutter.current.version

// Per-version override (unobfuscated Minecraft 26.x needs Java 25); falls back to the shared
// java_version (21) for the 1.21.x line.
val javaVersion = (project.findProperty("java_version") as String).toInt()

group = project.property("maven_group") as String
version = project.property("mod_version") as String

java {
	toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
	withSourcesJar()
}

base {
	archivesName = "$modId-neoforge-$mcVersion"
}

sourceSets {
	main {
		java.srcDir(rootProject.file("src/neoforge/java"))
		resources.srcDir(rootProject.file("src/neoforge/resources"))
	}
}

repositories {
	// Mixin annotations are needed at compile time (mixin classes live in the shared src),
	// even though NeoForge's own bundled Mixin runtime applies them at runtime.
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

neoForge {
	version = project.property("neoforge_version") as String

	mods {
		create(modId) {
			sourceSet(sourceSets.main.get())
		}
	}

	runs {
		configureEach {
			systemProperty("neoforge.enabledGameTestNamespaces", modId)
		}
		create("client") {
			client()
		}
		create("server") {
			server()
		}
	}
}

dependencies {
	compileOnly("org.spongepowered:mixin:0.8.5")

	// Test-only: these are on the compile classpath transitively via Minecraft's own libraries at
	// game runtime, but the plain JUnit `test` task needs them declared explicitly. Versions match
	// what this Minecraft version itself bundles.
	testImplementation("org.slf4j:slf4j-api:2.0.16")
	testImplementation("com.google.code.gson:gson:2.10.1")
	// authlib 7 (1.21.9+) replaced GameProfile.getProperties() with properties(); match the game's own.
	val authlibVersion = when {
		stonecutter.eval(mcVersion, ">=26.2") -> "9.0.75"
		stonecutter.eval(mcVersion, ">=26.1") -> "7.0.63"
		stonecutter.eval(mcVersion, ">=1.21.9") -> "7.0.61"
		else -> "6.0.54"
	}
	testImplementation("com.mojang:authlib:$authlibVersion")
	testImplementation("org.apache.logging.log4j:log4j-api:2.22.1")
	testImplementation("com.google.guava:guava:32.1.2-jre")
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
		"neoforge_version" to project.property("neoforge_version"),
		"neoforge_loader_version_range" to project.property("neoforge_loader_version_range"),
	)
	inputs.properties(expandProps)
	filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml")) {
		expand(expandProps)
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

apply(from = rootProject.file("gradle/modsync-helper.gradle"))
