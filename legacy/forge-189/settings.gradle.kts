pluginManagement {
	repositories {
		maven("https://repo.essential.gg/repository/maven-public")
		maven("https://maven.architectury.dev/")
		maven("https://maven.fabricmc.net")
		maven("https://maven.minecraftforge.net/")
		mavenCentral()
		gradlePluginPortal()
	}
	resolutionStrategy {
		eachPlugin {
			// Essential's fork of Architectury Loom: the maintained Gradle plugin for Forge 1.8.9.
			if (requested.id.id == "gg.essential.loom") useModule("gg.essential:architectury-loom:${requested.version}")
		}
	}
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "peercraft-forge-189"
