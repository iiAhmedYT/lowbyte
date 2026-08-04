plugins {
    `kotlin-dsl`
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
}

// Keeps the coordinate users already depend on, dev.iiahmed:lowbyte, rather
// than letting it follow the directory name.
base.archivesName = "lowbyte"

dependencies {
    // api, not implementation: the task's own inputs are Lowbyte's settings, so
    // anything configuring this plugin programmatically sees core anyway.
    api(project(":core"))

    // No fixtures here on purpose. What is left to test is the mapping onto
    // Lowbyte and the translation of its exception, and the classes for that
    // are two dozen lines of ASM rather than a compiled sample.
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

java {
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    jvmToolchain(17)
}

gradlePlugin {
    plugins {
        create("lowbytePlugin") {
            id = "dev.iiahmed.lowbyte"
            displayName = "Lowbyte Plugin"
            implementationClass = "dev.iiahmed.lowbyte.LowbytePlugin"
            description = "A Gradle plugin for downgrading the bytecode version of compiled class files and jars."
            tags.set(listOf("bytecode", "downgrade", "asm", "java", "compatibility"))
        }
    }
}

publishing {
    // java-gradle-plugin creates this one, plus a marker for the plugin id.
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") artifactId = "lowbyte"
    }
    repositories {
        maven {
            name = "GraveMC"
            url = uri("https://repo.gravemc.net/releases")
            credentials {
                username = findProperty("gravemc.repo.user") as String? ?: System.getenv("REPO_USER")
                password = findProperty("gravemc.repo.password") as String? ?: System.getenv("REPO_PASSWORD")
            }
        }
    }
}
