plugins {
    `kotlin-dsl`
}

kotlin {
    // The Minecraft 1.21.11 modules target Java 21; build logic does not use
    // Java 22-specific APIs, so compiling it with the same LTS toolchain keeps
    // a standard Java 21 development environment runnable.
    jvmToolchain(21)
}

repositories {
    maven("https://maven.fabricmc.net") {
        name = "Fabric"
    }

    maven("https://maven.minecraftforge.net/") {
        name = "Forge"
    }

    maven("https://maven.architectury.dev/") {
        name = "Architectury"
    }

    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"

        content {
            includeGroup("maven.modrinth")
        }
    }

    google()
    mavenCentral()

    gradlePluginPortal()
}

dependencies {
    implementation(plugin(sharedLibs.plugins.idea.gradle))
    implementation(plugin(sharedLibs.plugins.shadow))

    implementation(plugin(mcLibs.plugins.loom.gradle))
}

fun plugin(plugin: Provider<PluginDependency>): Provider<String> =
    plugin.map {
        val pluginId = it.pluginId
        val version = it.version

        "$pluginId:$pluginId.gradle.plugin:$version"
    }
