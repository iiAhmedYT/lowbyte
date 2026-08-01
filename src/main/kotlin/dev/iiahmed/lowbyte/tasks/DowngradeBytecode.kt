package dev.iiahmed.lowbyte.tasks

import dev.iiahmed.lowbyte.api.ApiIndex
import dev.iiahmed.lowbyte.api.ApiRewrites
import dev.iiahmed.lowbyte.api.ApiSettings
import dev.iiahmed.lowbyte.api.RuntimeApi
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
            val apiFindings = linkedSetOf<String>()

            // The injected utility is named after what it ends up holding, so
            // which methods a jar needs has to be known before a call site can
            // be pointed at it.
            val runtimeMethods = scanRuntimeUsage(jar, target)
            val context = DowngradeContext(
                nests = scanNests(jar, target),
                api = apiSettings(target, runtimeMethods),
                onApiFinding = { apiFindings += it }
            )
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
                untouched += writeRuntimeClass(jos, context, runtimeMethods)
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

            reportApi(apiFindings, target)

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
     * What the target release's JDK actually had, when asked for.
     *
     * Read from the build JDK's own `ct.sym`, the file `javac --release` uses.
     */
    /**
     * Which utility methods the jar needs, before anything is written.
     *
     * Empty unless the API check is on, and empty when nothing in the jar calls
     * something the utility covers, in which case no class is injected at all.
     */
    private fun scanRuntimeUsage(jar: JarFile, target: Int): Set<String> {
        if (!api.get()) return emptySet()

        val needed = mutableSetOf<String>()
        classEntries(jar).forEach { entry ->
            needed += ApiRewrites.runtimeMethodsNeeded(jar.getInputStream(entry).readAllBytes(), target)
        }
        return needed
    }

    /**
     * Adds the utility, trimmed to what was used and stripped of annotations.
     *
     * Nothing is written when nothing needed it.
     */
    private fun writeRuntimeClass(
        jos: JarOutputStream,
        context: DowngradeContext,
        methods: Set<String>
    ): Int {
        val settings = context.api
        if (settings == null || methods.isEmpty()) return 0

        val name = settings.runtimeClassName
        // More than one class when a kept method needs a helper type, since a
        // nested class is a class file of its own.
        val injected = RuntimeApi.inject(name, methods)
        injected.forEach { (className, bytes) ->
            jos.putNextEntry(ZipEntry("$className.class"))
            jos.write(bytes)
            jos.closeEntry()
        }

        logger.lifecycle("Injected $name with ${methods.size} method(s): ${methods.sorted().joinToString(", ")}")
        return injected.size
    }

    private fun apiSettings(target: Int, runtimeMethods: Set<String>): ApiSettings? {
        if (!api.get()) return null

        val ctSym = ApiIndex.currentJdkCtSym()
        if (ctSym == null) {
            logger.warn(
                "Lowbyte: the API check was asked for, but the JDK running Gradle ships no " +
                    "lib/ct.sym. Calls with a rebuild still get one; the rest cannot be " +
                    "reported, because nothing says what Java $target had."
            )
            return ApiSettings(target, ApiIndex.EMPTY, runtimeClassName(runtimeMethods))
        }

        val index = ApiIndex.read(ctSym, target)
        if (index.isEmpty) {
            logger.warn(
                "Lowbyte: the API check was asked for, but ${ctSym.absolutePath} has no data for " +
                    "Java $target. Calls with a rebuild still get one; the rest cannot be " +
                    "reported. Run Gradle on a newer JDK to enable that half."
            )
        }
        return ApiSettings(target, index, runtimeClassName(runtimeMethods))
    }

    /**
     * Where the injected utility goes.
     *
     * The default is content-addressed, so two jars holding the same methods
     * agree on the name and the bytes. Setting one by hand is for builds that
     * relocate it, and then keeping it distinct is the build's problem.
     */
    private fun runtimeClassName(methods: Set<String>): String =
        runtimeClass.orNull?.replace('.', '/') ?: RuntimeApi.defaultClassName(methods)

    /**
     * API findings are warnings, whatever `failOnUnsupported` says.
     *
     * A call into a newer JDK may sit behind a runtime version check, in which
     * case the reference is real, the code is correct, and it is never reached.
     * Failing that build would be wrong.
     */
    private fun reportApi(findings: Set<String>, target: Int) {
        if (findings.isEmpty()) return

        logger.warn(
            "Lowbyte: ${findings.size} call(s) into APIs Java $target does not have. These were " +
                "left alone, and will throw at runtime unless guarded by a version check:\n" +
                findings.joinToString("\n") { "  - $it" }
        )
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
