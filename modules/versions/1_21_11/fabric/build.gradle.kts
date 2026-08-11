plugins {
    id("net.fabricmc.fabric-loom-remap")
    `photonics-fabric`
}

val mainLibs = libs12111

dependencies {
    // Required by Sodium at runtime and by the property-gated shader game-test
    // reporter's client tick callback.
    modImplementation(mainLibs.fabric.api)
}

loom {
    runs {
        create("shaderGameTestClient") {
            client()
            name("Shader Game Test Client")
            runDir("run")

            // Keep the test isolated from the ordinary development client and
            // load the portable shader-test world by name.
            programArgs("--quickPlaySingleplayer", "backup")

            // The engine-side automation implementation is intentionally
            // separate from this launch configuration. When it is added, it
            // must write the generic JSON result described in the root README.
            property("photonicengine.shaderGameTest.reportFile", "automation/shader-game-test-report.json")
        }
    }
}

val prepareShaderGameTestFixture by tasks.registering(Copy::class) {
    description = "Installs the tracked shader pack used by the shader game test."
    from(layout.projectDirectory.dir("src/shaderGameTest"))
    into(layout.projectDirectory.dir("run"))
}

tasks.named("runShaderGameTestClient") {
    dependsOn(prepareShaderGameTestFixture)
}

tasks {
    processResources {
        inputs.property("version", project.version)

        // Resolve catalog values during configuration. The CopySpec action is
        // serialized for the configuration cache, so it must not capture this
        // Gradle script to access version catalogs.
        val fabricModProperties = mapOf(
            "photonics_version" to constants.versions.photonics.get(),
            "minecraft_version" to mainLibs.versions.minecraft.get(),
            "fabric_loader_version" to mainLibs.versions.fabric.loader.get()
        )
        inputs.properties(fabricModProperties)

        filesMatching("fabric.mod.json") {
            expand(fabricModProperties)
        }
    }
}
