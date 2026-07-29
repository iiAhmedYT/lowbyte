package dev.iiahmed.lowbyte.tasks

import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import dev.iiahmed.lowbyte.downgrade.ClassDowngrader
import dev.iiahmed.lowbyte.downgrade.DowngradeContext
import dev.iiahmed.lowbyte.nest.NestRegistry
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File
import java.util.jar.JarEntry
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
            // Read everything before writing anything: a call site naming a
            // private member of a nestmate says nothing about that member's
            // access flags, so the answer lives in a class file we may not have
            // reached yet.
            val context = DowngradeContext(scanNests(jar, target))
            val written = mutableSetOf<String>()

            JarOutputStream(output.outputStream()).use { jos ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    jos.putNextEntry(ZipEntry(entry.name))

                    if (entry.name.endsWith(".class") && !isExcluded(entry.name)) {
                        val internalName = entry.name.removeSuffix(".class")
                        val classBytes = jar.getInputStream(entry).readAllBytes()
                        jos.write(ClassDowngrader.downgrade(classBytes, target, context) { feature ->
                            findings += "$internalName: $feature"
                        })
                        written += internalName
                        downgraded++
                    } else {
                        // Copy resource files (and excluded classes) unchanged
                        jar.getInputStream(entry).copyTo(jos)
                        untouched++
                    }

                    jos.closeEntry()
                }

                untouched += writeMarkerClasses(jos, context, target)
            }

            // A bridge that was never emitted is a NoSuchMethodError waiting to
            // happen, so an owner we never rewrote is reported rather than shipped.
            (context.nests.bridgedOwners - written).forEach {
                findings += "$it: holds a private member reached from its nest, but was not rewritten"
            }
        }

        logger.lifecycle("Downgraded $downgraded classes ($untouched entries copied as-is).")
        logger.lifecycle("Downgraded jar written to: ${output.absolutePath}")

        report(findings, output)
    }

    /**
     * Works out which private members are reached across a nest.
     *
     * Skipped entirely above Java 11, where the nest attributes stay and no
     * bridge is needed.
     */
    private fun scanNests(jar: JarFile, target: Int): NestRegistry {
        if (target >= NestRegistry.INTRODUCED_IN) return NestRegistry.EMPTY

        return NestRegistry.scan(
            classEntries(jar).asSequence().map { jar.getInputStream(it).readAllBytes() }
        )
    }

    private fun classEntries(jar: JarFile): List<JarEntry> {
        val result = mutableListOf<JarEntry>()
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.name.endsWith(".class") && !isExcluded(entry.name)) result += entry
        }
        return result
    }

    /**
     * Adds the empty classes that make bridged constructors unique.
     *
     * These are the only entries in the output that did not come from an entry
     * in the input.
     */
    private fun writeMarkerClasses(
        jos: JarOutputStream,
        context: DowngradeContext,
        target: Int
    ): Int {
        val markers = context.nests.markerClasses.sorted()
        markers.forEach { internalName ->
            jos.putNextEntry(ZipEntry("$internalName.class"))
            jos.write(NestRegistry.markerClassBytes(internalName, ClassFileVersion.fromJavaVersion(target)))
            jos.closeEntry()
        }
        return markers.size
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
