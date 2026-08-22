plugins {
	`java-library`
	`maven-publish`
	id("net.fabricmc.fabric-loom-remap")
}

val modId = project.property("mod_id") as String
val modName = project.property("mod_name") as String
val mcVersion = stonecutter.current.version

group = project.property("maven_group") as String
version = project.property("mod_version") as String

java {
	toolchain.languageVersion = JavaLanguageVersion.of(project.property("java_version") as String)
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
	mappings(loom.officialMojangMappings())
	modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_api_version")}")

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
	options.release = 21
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
