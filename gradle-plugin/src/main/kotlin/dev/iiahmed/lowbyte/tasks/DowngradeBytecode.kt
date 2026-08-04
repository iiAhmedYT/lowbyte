package dev.iiahmed.lowbyte.tasks

import dev.iiahmed.lowbyte.DowngradeResult
import dev.iiahmed.lowbyte.Lowbyte
import dev.iiahmed.lowbyte.LowbyteException
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import javax.inject.Inject

/**
 * The `lowbyte { }` settings, applied to the jar this project built.
 *
 * Deliberately thin. Everything about downgrading a jar lives in [Lowbyte],
 * which knows nothing about Gradle, so this maps task properties in and a
 * [DowngradeResult] out to the logger. A Maven mojo or a command line is the
 * same few lines against a different frontend.
 */
abstract class DowngradeBytecode @Inject constructor() : DefaultTask() {

    @get:Input
    abstract val targetJavaVersion: Property<Int>

    @get:Input
    abstract val excludedClasses: ListProperty<String>

    @get:Input
    abstract val failOnUnsupported: Property<Boolean>

    @get:Input
    abstract val api: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val runtimeClass: Property<String>

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    init {
        group = "lowbyte"
        description = "Downgrades the class file version of every class in the jar."

        // Matches the extension's default, and keeps the task usable on its own.
        api.convention(false)
    }

    @TaskAction
    fun downgrade() {
        val input = inputJar.get().asFile
        val output = outputJar.get().asFile
        val target = targetJavaVersion.get()

        val lowbyte = Lowbyte.targeting(target)
            .api(api.get())
            .exclude(excludedClasses.get())
            .failOnUnsupported(failOnUnsupported.get())
            .runtimeClass(runtimeClass.orNull)
            .build()

        logger.lifecycle("Downgrading ${input.name} to Java $target bytecode...")

        // Gradle prints its own exception without a stack trace, which is what
        // a configuration problem should look like. Anything else is a bug and
        // keeps its trace.
        val result = try {
            lowbyte.downgrade(input, output)
        } catch (e: LowbyteException) {
            throw GradleException(e.message.orEmpty())
        }

        report(result, output.absolutePath)
    }

    private fun report(result: DowngradeResult, outputPath: String) {
        result.warnings.forEach { logger.warn("Lowbyte: $it") }

        if (result.droppedSignatures > 0) {
            logger.warn(
                "Lowbyte: dropped ${result.droppedSignatures} signature file(s). Rewriting a class " +
                    "invalidates every digest covering it, so the output is unsigned. " +
                    "Re-sign it if that matters."
            )
        }

        if (result.droppedModuleInfo) {
            logger.warn(
                "Lowbyte: dropped module-info.class. Java ${result.target} has no module system, and " +
                    "there is no class file version at which a module descriptor is both valid " +
                    "and loadable there, so anything scanning the jar would have hit an " +
                    "UnsupportedClassVersionError. The output is no longer a module."
            )
        }

        // A call into a newer JDK may sit behind a runtime version check, in
        // which case the reference is real, the code is correct, and it is never
        // reached. Failing that build would be wrong, so these stay warnings
        // however failOnUnsupported is set.
        if (result.apiFindings.isNotEmpty()) {
            logger.warn(
                "Lowbyte: ${result.apiFindings.size} call(s) into APIs Java ${result.target} does not " +
                    "have. These were left alone, and will throw at runtime unless guarded by a " +
                    "version check:\n" + result.apiFindings.joinToString("\n") { "  - $it" }
            )
        }

        // Only reached with the check off, since on it threw instead.
        if (result.unsupported.isNotEmpty()) {
            logger.warn(
                "Lowbyte: ${result.unsupported.size} construct(s) cannot be expressed in " +
                    "Java ${result.target}:\n" + result.unsupported.joinToString("\n") { "  - $it" }
            )
        }

        result.injectedClass?.let { injected ->
            logger.lifecycle(
                "Injected $injected with ${result.injectedMethods.size} method(s): " +
                    result.injectedMethods.sorted().joinToString(", ")
            )
        }

        logger.lifecycle("Downgraded ${result.downgraded} classes (${result.copied} entries copied as-is).")
        logger.lifecycle("Downgraded jar written to: $outputPath")
    }
}
