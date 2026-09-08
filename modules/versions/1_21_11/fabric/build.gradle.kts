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
            // Exercise the installed mod's shader snapshot, not live source
            // files that can drift from the already compiled Java client.
            property("photonics.usePackagedShaders", "true")
            property("photonics.traceSceneChanges", providers.gradleProperty("traceSceneChanges").orElse("false").get())
            property("photonics.traceLighting", providers.gradleProperty("traceLighting").orElse("false").get())
            property("photonics.profileGpu", providers.gradleProperty("profileGpu").orElse("false").get())
            property("photonicengine.shaderGameTest.freezeTicks", providers.gradleProperty("shaderGameTestFreezeTicks").orElse("false").get())
            property("photonicengine.shaderGameTest.traceStartup", providers.gradleProperty("shaderGameTestTraceStartup").orElse("false").get())
            property("photonicengine.shaderGameTest.scenario", providers.gradleProperty("shaderGameTestScenario").orElse("standard").get())
            providers.gradleProperty("shaderGameTestRenderDistance").orNull?.let {
                property("photonicengine.shaderGameTest.renderDistance", it)
            }
            providers.gradleProperty("shaderGameTestPitch").orNull?.let {
                property("photonicengine.shaderGameTest.pitch", it)
            }
            providers.gradleProperty("shaderGameTestYaw").orNull?.let {
                property("photonicengine.shaderGameTest.yaw", it)
            }
            providers.gradleProperty("shaderGameTestHotbarSlot").orNull?.let {
                property("photonicengine.shaderGameTest.hotbarSlot", it)
            }
            providers.gradleProperty("shaderGameTestDenoiserCamera").orNull?.let {
                property("photonicengine.shaderGameTest.denoiser.camera", it)
            }
            providers.gradleProperty("shaderGameTestDenoiserEditBlock").orNull?.let {
                property("photonicengine.shaderGameTest.denoiser.editBlock", it)
            }
            providers.gradleProperty("shaderGameTestDenoiserSourceBlock").orNull?.let {
                property("photonicengine.shaderGameTest.denoiser.sourceBlock", it)
            }
            providers.gradleProperty("shaderGameTestDenoiserReceiverBlock").orNull?.let {
                property("photonicengine.shaderGameTest.denoiser.receiverBlock", it)
            }
            providers.gradleProperty("shaderGameTestDenoiserRoi").orNull?.let {
                property("photonicengine.shaderGameTest.denoiser.roi", it)
            }
            providers.gradleProperty("shaderGameTestDenoiserEdgeAxis").orNull?.let {
                property("photonicengine.shaderGameTest.denoiser.edgeAxis", it)
            }
        }
    }
}

val selectedShaderGameTestPack = providers.gradleProperty("shaderGameTestPack")
    .orElse("Shrimple-ph-0.4.zip").get()

val prepareShaderGameTestFixture by tasks.registering(Copy::class) {
    val packName = selectedShaderGameTestPack
    description = "Installs the tracked shader pack used by the shader game test."
    from(layout.projectDirectory.dir("src/shaderGameTest"))
    into(layout.projectDirectory.dir("run"))
    inputs.property("shaderGameTestPack", packName)
    filesMatching("config/iris.properties") {
        filter { line ->
            if (line.startsWith("shaderPack=")) "shaderPack=$packName" else line
        }
    }
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
