package dev.iiahmed.lowbyte.tasks

import dev.iiahmed.lowbyte.downgrade.ClassDowngrader
import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import javax.inject.Inject

abstract class DowngradeBytecode @Inject constructor() : DefaultTask() {

    @get:Input
    abstract val targetJavaVersion: Property<Int>

    @get:Input
    abstract val excludedClasses: ListProperty<String>

    @get:Input
    abstract val failOnUnsupported: Property<Boolean>

    @get:InputFile
    abstract val inputJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    init {
        group = "lowbyte"
        description = "Downgrades the class file version of every class in the jar."
    }

    @TaskAction
    fun downgrade() {
        val input = inputJar.get().asFile
        val output = outputJar.get().asFile

        if (!input.exists()) {
            throw IllegalStateException("Input JAR not found: ${input.absolutePath}")
        }

        output.parentFile?.mkdirs()

        val target = targetJavaVersion.get()
        ClassFileVersion.requireSupportedTarget(target)

        logger.lifecycle("Downgrading ${input.name} to Java $target bytecode...")

        var downgraded = 0
        var untouched = 0

        // Collected instead of thrown on sight, otherwise you fix one problem per
        // build. Set keeps discovery order and drops duplicate findings.
        val findings = linkedSetOf<String>()

        JarFile(input).use { jar ->
            JarOutputStream(output.outputStream()).use { jos ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    jos.putNextEntry(ZipEntry(entry.name))

                    if (entry.name.endsWith(".class") && !isExcluded(entry.name)) {
                        val classBytes = jar.getInputStream(entry).readAllBytes()
                        jos.write(ClassDowngrader.downgrade(classBytes, target) { feature ->
                            findings += "${entry.name.removeSuffix(".class")}: $feature"
                        })
                        downgraded++
                    } else {
                        // Copy resource files (and excluded classes) unchanged
                        jar.getInputStream(entry).copyTo(jos)
                        untouched++
                    }

                    jos.closeEntry()
                }
            }
        }

        logger.lifecycle("Downgraded $downgraded classes ($untouched entries copied as-is).")
        logger.lifecycle("Downgraded jar written to: ${output.absolutePath}")

        report(findings, output)
    }

    /**
     * Dumps everything we couldn't handle, as a failure or as warnings.
     *
     * The jar is deleted when failing so nothing downstream picks up an artifact
     * that would break at runtime.
     */
    private fun report(findings: Set<String>, output: File) {
        if (findings.isEmpty()) return

        val summary = "Lowbyte: ${findings.size} construct(s) cannot be expressed in " +
            "Java ${targetJavaVersion.get()}:\n" + findings.joinToString("\n") { "  - $it" }

        if (!failOnUnsupported.get()) {
            logger.warn(summary)
            return
        }

        output.delete()
        throw GradleException(summary)
    }

    private fun isExcluded(entryName: String): Boolean {
        val internalName = entryName.removeSuffix(".class")
        return excludedClasses.get().any { internalName.startsWith(it.replace('.', '/')) }
    }

}
