plugins {
    `kotlin-dsl`
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradleup.shadow") version "8.3.6"
}

base.archivesName = "lowbyte-plugin"

/**
 * Folded into the plugin jar rather than resolved beside it, so that applying
 * the plugin needs nothing but the Plugin Portal and Maven Central.
 *
 * The jar attribute is not optional: without it Gradle hands back a classes
 * directory, which carries no resources, and the injected runtime is a resource.
 */
val bundled: Configuration by configurations.creating {
    attributes {
        attribute(
            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
            objects.named(LibraryElements::class.java, LibraryElements.JAR)
        )
    }
}

/** What a consumer resolves beside the jar, for ShadedJarTest to reproduce. */
val shadedRuntime: Configuration by configurations.creating

// Core is compileOnly rather than api, so that folding it in does not also
// leave it in the published POM.
configurations.compileOnly { extendsFrom(bundled) }
configurations.testImplementation { extendsFrom(bundled) }

dependencies {
    bundled(project(":core"))

    // Core carried this in its own POM until it was folded in. Both halves of
    // the jar are Kotlin and Gradle embeds an older copy, so it stays declared.
    implementation(kotlin("stdlib"))
    shadedRuntime(kotlin("stdlib"))

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()

    // An input, not a dependsOn. Ordering alone leaves the test UP-TO-DATE when
    // the jar changes, which is how a test about the jar stops being one.
    inputs.file(tasks.shadowJar.flatMap { it.archiveFile })
        .withPropertyName("shadedJar")
        .withPathSensitivity(PathSensitivity.NONE)

    systemProperty("lowbyte.shadedJar", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("lowbyte.shadedRuntime", shadedRuntime.asPath)
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

/**
 * ASM moves under our own package. A build script's classpath is shared by every
 * plugin on it, and this one rewrites bytecode for a living, so it must not be
 * the plugin that decides everyone else's ASM version.
 *
 * Core is not relocated: its package is the one it injects into other people's
 * jars. Kotlin is not bundled: Gradle already has a copy on that classpath.
 */
tasks.shadowJar {
    archiveClassifier = ""
    configurations = listOf(bundled)

    relocate("org.objectweb.asm", "dev.iiahmed.lowbyte.shaded.asm")

    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*:.*"))
        exclude(dependency("org.jetbrains:annotations:.*"))
    }
}

// The shaded jar takes the plain one's place in the java component, so the POM
// and the artifact beside it describe the same thing.
tasks.jar {
    archiveClassifier = "thin"
}

configurations.apiElements.get().outgoing.artifacts.clear()
configurations.runtimeElements.get().outgoing.artifacts.clear()

artifacts {
    add("apiElements", tasks.shadowJar)
    add("runtimeElements", tasks.shadowJar)
}

afterEvaluate {
    publishing.publications.named<MavenPublication>("pluginMaven") {
        artifactId = "lowbyte-plugin"
    }
}

publishing {
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
