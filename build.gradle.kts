import org.gradle.jvm.toolchain.JavaCompiler
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.work.DisableCachingByDefault
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "2.1.21"
}

group = "dev.iiahmed"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-commons:9.8")
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

/**
 * Rebuilds the checked-in Java 21 test fixtures.
 *
 * Compiles each `src/test/resources/javac21/<Name>.java.txt` with a JDK 21
 * toolchain, runs it there, and writes three things per sample:
 *
 *  * `<Name>.classdata` for every compiled class. The `.class` extension is
 *    dropped so nothing mistakes these for classes on the test classpath.
 *  * `<Name>.classes.txt` listing which classes belong to the sample.
 *  * `<Name>.baseline.txt` holding its stdout on real Java 21.
 *
 * The tests downgrade the `.classdata` files and check the result still behaves
 * like the baseline, so this is how you refresh that expectation. Only generated
 * files get deleted. The `.java.txt` sources are the input.
 */
@DisableCachingByDefault(because = "Regenerates checked-in fixtures on demand")
abstract class RegenerateJavac21Fixtures @Inject constructor(
    private val exec: ExecOperations
) : DefaultTask() {

    @get:Internal
    abstract val fixtureDir: DirectoryProperty

    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:Internal
    abstract val javaCompiler: Property<JavaCompiler>

    @get:Internal
    abstract val javaLauncher: Property<JavaLauncher>

    @TaskAction
    fun regenerate() {
        val fixtures = fixtureDir.get().asFile
        val sources = fixtures.listFiles { f: File -> f.name.endsWith(".java.txt") }
            ?.sortedBy { it.name }
            .orEmpty()
        check(sources.isNotEmpty()) { "No *.java.txt fixture sources found in $fixtures" }

        val work = workDir.get().asFile
        work.deleteRecursively()

        // Drop the previous generation first, so a class that is no longer
        // produced does not linger as an orphan fixture.
        fixtures.listFiles { f: File ->
            f.name.endsWith(".classdata") || f.name.endsWith(".baseline.txt") ||
                f.name.endsWith(".classes.txt") || f.name == "toolchain.txt"
        }?.forEach { it.delete() }

        val javac = javaCompiler.get().executablePath.asFile.absolutePath
        val java = javaLauncher.get().executablePath.asFile.absolutePath

        sources.forEach { source ->
            val name = source.name.removeSuffix(".java.txt")

            // Each sample compiles into its own directory, so the class list
            // recorded for it is exact.
            val srcDir = File(work, "$name/src").apply { mkdirs() }
            val classDir = File(work, "$name/classes").apply { mkdirs() }
            source.copyTo(File(srcDir, "$name.java"), overwrite = true)

            exec.exec {
                executable = javac
                args("-d", classDir.absolutePath, File(srcDir, "$name.java").absolutePath)
            }

            val produced = classDir.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .sortedBy { it.name }
                .toList()
            check(produced.isNotEmpty()) { "$name produced no class files" }

            produced.forEach { it.copyTo(File(fixtures, "${it.nameWithoutExtension}.classdata"), overwrite = true) }
            File(fixtures, "$name.classes.txt")
                .writeText(produced.joinToString("\n") { it.nameWithoutExtension } + "\n")

            val stdout = ByteArrayOutputStream()
            exec.exec {
                executable = java
                args("-cp", classDir.absolutePath, name)
                standardOutput = stdout
            }
            val baseline = stdout.toString(Charsets.UTF_8.name()).replace("\r\n", "\n").trim()
            File(fixtures, "$name.baseline.txt").writeText(baseline + "\n")

            logger.lifecycle("$name: ${produced.size} classes, ${baseline.lines().size} baseline lines")
        }

        // Which JDK produced this generation. javac's desugaring is not identical
        // across builds of the same release. Record patterns compile to four
        // typeSwitch call sites on 21.0.3 but two on 21.0.8, so regenerating on
        // another machine can legitimately change the fixtures. Recording it
        // makes that show up in the diff instead of catching someone out.
        val metadata = javaLauncher.get().metadata
        File(fixtures, "toolchain.txt").writeText(
            "${metadata.vendor} ${metadata.javaRuntimeVersion}\n"
        )
    }
}

val javaToolchains = extensions.getByType<JavaToolchainService>()

tasks.register<RegenerateJavac21Fixtures>("regenerateJavac21Fixtures") {
    group = "lowbyte"
    description = "Recompiles the Java 21 test fixtures with a JDK 21 toolchain and re-records their baselines."

    fixtureDir.set(layout.projectDirectory.dir("src/test/resources/javac21"))
    workDir.set(layout.buildDirectory.dir("javac21-fixtures"))
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })

    outputs.upToDateWhen { false }
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
