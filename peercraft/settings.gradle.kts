pluginManagement {
	repositories {
		maven("https://maven.fabricmc.net/") { name = "Fabric" }
		maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
		mavenCentral()
		gradlePluginPortal()
	}
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
	id("dev.kikugie.stonecutter") version "0.9.7"
}

rootProject.name = "peercraft"

stonecutter {
	create(rootProject) {
		fun mc(mcVersion: String, loaders: Iterable<String> = listOf("fabric", "neoforge")) {
			for (loader in loaders) {
				version("$mcVersion-$loader", mcVersion).buildscript = "build.$loader.gradle.kts"
			}
		}

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

		vcsVersion = "1.21.1-fabric"
	}
}
