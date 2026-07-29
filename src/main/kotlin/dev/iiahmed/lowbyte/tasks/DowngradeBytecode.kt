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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.Manifest
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

            var droppedSignatures = 0
            var droppedModuleInfo = 0

            JarOutputStream(output.outputStream()).use { jos ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()

                    // Dropped rather than copied: every digest in them covers a
                    // class we are about to rewrite.
                    if (isSignatureFile(entry.name)) {
                        droppedSignatures++
                        continue
                    }

                    if (isDroppedModuleInfo(entry.name, target)) {
                        droppedModuleInfo++
                        continue
                    }

                    jos.putNextEntry(ZipEntry(entry.name))

                    if (isManifest(entry.name)) {
                        jos.write(withoutEntryDigests(jar.getInputStream(entry).readAllBytes()))
                        untouched++
                    } else if (entry.name.endsWith(".class") &&
                        !isExcluded(entry.name) &&
                        !isUnloweredModuleInfo(entry.name, target)
                    ) {
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

            if (droppedSignatures > 0) {
                logger.warn(
                    "Lowbyte: dropped $droppedSignatures signature file(s). Rewriting a class " +
                        "invalidates every digest covering it, so the output is unsigned. " +
                        "Re-sign it if that matters."
                )
            }

            if (droppedModuleInfo > 0) {
                logger.warn(
                    "Lowbyte: dropped module-info.class. Java $target has no module system, and " +
                        "there is no class file version at which a module descriptor is both valid " +
                        "and loadable there, so anything scanning the jar would have hit an " +
                        "UnsupportedClassVersionError. The output is no longer a module."
                )
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
            // A descriptor declares no members and references none, so it can
            // never own or reach a bridged member.
            if (entry.name.endsWith(".class") && !isExcluded(entry.name) && !isModuleInfo(entry.name)) {
                result += entry
            }
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

    /**
     * Whether an entry falls under one of the configured exclusions.
     *
     * Matched on name boundaries rather than as a bare prefix, so excluding
     * `com/foo` covers the package and not `com/foobar` next to it. A `$` counts
     * as a boundary too, which is what makes excluding a class exclude the
     * nested classes that go with it.
     */
    private fun isExcluded(entryName: String): Boolean {
        val internalName = entryName.removeSuffix(".class")
        return excludedClasses.get().any { excluded ->
            val prefix = excluded.replace('.', '/').removeSuffix("/")
            internalName == prefix ||
                internalName.startsWith("$prefix/") ||
                internalName.startsWith("$prefix\$")
        }
    }

    /**
     * Matched by suffix so the copies under `META-INF/versions/` in a
     * multi-release jar are covered too.
     */
    private fun isModuleInfo(entryName: String): Boolean = entryName.endsWith("module-info.class")

    /**
     * The descriptor at the root of the jar, as opposed to a versioned copy.
     *
     * Only this one is a class name the runtime will ever resolve.
     */
    private fun isRootModuleInfo(entryName: String): Boolean = entryName == "module-info.class"

    /**
     * A root descriptor aimed at a release with no module system.
     *
     * There is no version that helps. Left at 21 anything scanning the jar gets
     * an `UnsupportedClassVersionError`, and lowered to 8 it gets a
     * `ClassFormatError`, since `CONSTANT_Module` does not exist before class
     * file 53. Walking every `.class` entry and defining it is ordinary
     * behaviour for component scanners and shading tools, so the entry is a
     * hazard however it is written, and dropping it is the only outcome with no
     * failure mode on the target.
     *
     * A jar downgraded this far cannot be a module there in any case.
     */
    private fun isDroppedModuleInfo(entryName: String, target: Int): Boolean =
        isRootModuleInfo(entryName) && target < ClassFileVersion.MIN_MODULE_JAVA

    /**
     * A versioned descriptor that has to keep the version it arrived at.
     *
     * Down to Java 9 a descriptor is lowered like anything else, and it has to
     * be: the runtime version-checks `module-info.class` the same way it checks
     * a class, so one left at 21 in a jar targeting 11 makes that jar unusable
     * on the module path of the very JVM it was downgraded for.
     *
     * Below 9 a copy under `META-INF/versions/` is left alone rather than
     * dropped. It still describes the module for the newer JVMs that read that
     * directory, and older ones never resolve a class name from it.
     */
    private fun isUnloweredModuleInfo(entryName: String, target: Int): Boolean =
        isModuleInfo(entryName) && target < ClassFileVersion.MIN_MODULE_JAVA

    private fun isManifest(entryName: String): Boolean = entryName == JarFile.MANIFEST_NAME

    /**
     * The signature block of a signed jar.
     *
     * A signature covers digests of the entries, so rewriting any class breaks
     * it. Keeping the block would leave a jar that fails verification rather
     * than one that is merely unsigned, which is the worse of the two.
     *
     * Only the top level of `META-INF` counts, since that is the only place the
     * jar specification looks for these.
     */
    private fun isSignatureFile(entryName: String): Boolean {
        if (!entryName.startsWith("META-INF/")) return false

        val name = entryName.removePrefix("META-INF/")
        if (name.contains('/')) return false

        val upper = name.uppercase()
        return upper.endsWith(".SF") ||
            upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") ||
            upper.endsWith(".EC") ||
            upper.startsWith("SIG-")
    }

    /**
     * Strips the per-entry digests a signing run left in the manifest.
     *
     * Without the signature block these no longer verify anything, but leaving
     * them would describe classes whose bytes have changed, and would confuse a
     * later signing run. Other attributes in those sections are kept, and a
     * section emptied by the removal goes with them.
     *
     * An unsigned manifest comes back byte for byte as it arrived.
     */
    private fun withoutEntryDigests(manifestBytes: ByteArray): ByteArray {
        val manifest = Manifest(ByteArrayInputStream(manifestBytes))

        var changed = false
        val sections = manifest.entries.entries.iterator()
        while (sections.hasNext()) {
            val attributes = sections.next().value
            if (attributes.keys.removeIf { it.toString().contains("-Digest") }) changed = true
            if (attributes.isEmpty()) {
                sections.remove()
                changed = true
            }
        }

        if (!changed) return manifestBytes

        val out = ByteArrayOutputStream()
        manifest.write(out)
        return out.toByteArray()
    }

}
