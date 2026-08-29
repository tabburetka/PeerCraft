pluginManagement {
	repositories {
		maven("https://maven.fabricmc.net/") { name = "Fabric" }
		maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
		mavenCentral()
		gradlePluginPortal()
	}
}

plugins {
	// 1.0.0 drops the reference to the removed JvmVendorSpec.IBM_SEMERU — required for Gradle 9
	// (and for auto-provisioning the JDK 25 toolchain the 26.x targets need).
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
	id("dev.kikugie.stonecutter") version "0.9.7"
}

rootProject.name = "peercraft"

stonecutter {
	create(rootProject) {
		fun mc(
			mcVersion: String,
			loaders: Iterable<String> = listOf("fabric", "neoforge"),
			fabricBuildscript: String = "build.fabric.gradle.kts",
		) {
			for (loader in loaders) {
				val buildscript = if (loader == "fabric") fabricBuildscript else "build.$loader.gradle.kts"
				version("$mcVersion-$loader", mcVersion).buildscript = buildscript
			}
		}

		// Minecraft 26.1 onwards ships unobfuscated; Fabric targets from that point use the
		// non-remapping Loom plugin via build.fabric-unmapped.gradle.kts (NeoForge is unaffected).
		val unmapped = "build.fabric-unmapped.gradle.kts"

		mc("1.21.1")
		// NeoForge never shipped a stable (or even a proper NeoForm) build targeting 1.21.2 —
		// it went straight to 1.21.3, released one day later. Fabric-only for this one version.
		mc("1.21.2", listOf("fabric"))
		mc("1.21.3")
		mc("1.21.4")
		mc("1.21.5")
		mc("1.21.6")
		mc("1.21.7")
		mc("1.21.8")
		mc("1.21.9")
		mc("1.21.10")
		mc("1.21.11")
		// Backport era. Minecraft 1.16.5 is the last version on official Fabric before the
		// "Great Repackage" (1.17) — its own buildscript pins the Java 8 toolchain and swaps in
		// the src/main-java8 + src/client-1165 source roots (see build.fabric-1165.gradle.kts).
		mc("1.16.5", listOf("fabric"), fabricBuildscript = "build.fabric-1165.gradle.kts")
		// Calendar-versioned, unobfuscated era. NeoForge only published -beta builds for 26.1
		// and 26.1.1 (same situation as 1.21.6/1.21.7/1.21.9) — still real, working releases.
		mc("26.1", fabricBuildscript = unmapped)
		mc("26.1.1", fabricBuildscript = unmapped)
		mc("26.1.2", fabricBuildscript = unmapped)
		mc("26.2", fabricBuildscript = unmapped)

		vcsVersion = "1.21.1-fabric"
	}
}
