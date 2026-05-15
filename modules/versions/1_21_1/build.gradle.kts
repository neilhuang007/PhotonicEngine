val mainLibs = libs1211

architectury {
    minecraft = mainLibs.versions.minecraft.get()
    javaVersion = JavaVersion.VERSION_21

    commonDependencies {
        mappings(loom.officialMojangMappings())

        // Use by fabric (for obvious reasons) & common for mixin dependencies
        fabricLoader(mainLibs.fabric.loader)

        shadow(sharedLibs.semver)

        runtimeOnly(mainLibs.antlr4.runtime)
        implementation(mainLibs.glsl.transformer)
        implementation(mainLibs.jcpp)

        modImplementation(mainLibs.sodium)
        modImplementation(mainLibs.iris)
    }
}

subprojects {
    plugins.withId("dev.architectury.loom") {
        extensions.configure<net.fabricmc.loom.api.LoomGradleExtensionAPI>("loom") {
            val accessWidener = file("src/main/resources/.accesswidener")
            if (accessWidener.exists()) {
                accessWidenerPath.set(accessWidener)
            }
        }
    }
}
