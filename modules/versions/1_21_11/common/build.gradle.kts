plugins {
    id("net.fabricmc.fabric-loom-remap")
    `photonics-common`
}

dependencies {
    add("mappings", loom.officialMojangMappings())

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks {
    test {
        useJUnitPlatform()
    }
}
