plugins {
	id("dev.kikugie.stonecutter")
	// Versions must match gradle.properties' loom_version/moddev_version — the plugins {} block
	// can't read Gradle properties, so these are applied here once (apply = false) and reused by
	// build.fabric.gradle.kts / build.neoforge.gradle.kts without a version.
	id("net.fabricmc.fabric-loom-remap") version "1.17-SNAPSHOT" apply false
	id("net.neoforged.moddev") version "2.0.144" apply false
}

stonecutter active "1.21.1-fabric" /* [SC] DO NOT EDIT */

stonecutter parameters {
	constants.match(current.project.substringAfterLast('-'), "fabric", "neoforge")
}

tasks.register("buildAll") {
	group = "project"
	description = "Builds the mod jar for every registered Minecraft version and loader."
	dependsOn(stonecutter.tasks.named("build").map { it.values })
}
