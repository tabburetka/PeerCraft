plugins {
	id("dev.kikugie.stonecutter")
	// Versions must match gradle.properties' loom_version/moddev_version — the plugins {} block
	// can't read Gradle properties, so these are applied here once (apply = false) and reused by
	// build.fabric.gradle.kts / build.fabric-unmapped.gradle.kts / build.neoforge.gradle.kts
	// without a version.
	// fabric-loom-remap: obfuscated Minecraft (1.21.1-1.21.11), used by build.fabric.gradle.kts.
	// fabric-loom: unobfuscated Minecraft (26.1+), used by build.fabric-unmapped.gradle.kts —
	// same Loom release, different plugin id.
	id("net.fabricmc.fabric-loom-remap") version "1.17-SNAPSHOT" apply false
	id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT" apply false
	id("net.neoforged.moddev") version "2.0.144" apply false
}

stonecutter active "1.21.1-fabric" /* [SC] DO NOT EDIT */

stonecutter parameters {
	constants.match(current.project.substringAfterLast('-'), "fabric", "neoforge")
}

allprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        // An opt-in coturn run must not reuse results from a run where it was skipped.
        inputs.property("coturnExecutable", providers.environmentVariable("PEERCRAFT_TEST_COTURN_BIN").orElse(""))
        inputs.property("coturnLibraries", providers.environmentVariable("LD_LIBRARY_PATH").orElse(""))
    }
}

tasks.register("buildAll") {
	group = "project"
	description = "Builds the mod jar for every registered Minecraft version and loader."
	dependsOn(stonecutter.tasks.named("build").map { it.values })
}
