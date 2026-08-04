package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.api.ApiIndex
import dev.iiahmed.lowbyte.api.ApiRewrites
import dev.iiahmed.lowbyte.api.ApiSettings
import dev.iiahmed.lowbyte.api.RuntimeApi
import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import dev.iiahmed.lowbyte.downgrade.ClassDowngrader
import dev.iiahmed.lowbyte.downgrade.DowngradeContext
import dev.iiahmed.lowbyte.nest.NestRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry

/**
 * Downgrades jars, and knows nothing about the build tool that asked.
 *
 * This is the whole of Lowbyte. A Gradle task, a Maven mojo or a command line
 * are each a few lines mapping their own settings onto a [Builder] and their own
 * logger onto a [DowngradeResult]. None of that belongs here, which is why
 * nothing in this file imports one.
 *
 * An instance is settings, not a job. The files are arguments to [downgrade], so
 * one instance downgrades as many jars as you like:
 *
 * ```
 * val lowbyte = Lowbyte.targeting(8).api(true).build()
 * jars.forEach { lowbyte.downgrade(it, output(it)) }
 * ```
 *
 * That shape is worth having because [ApiIndex] is expensive. Reading it means
 * walking a zip of thousands of signature files, and holding it here means that
 * happens once however many jars go through. The cost is that an instance is
 * lazily stateful and so not safe to share across threads.
 */
class Lowbyte private constructor(

    /** Java release to aim at: 8, 11, 17, and so on. */
    val target: Int,

    /** Whether to look at the JDK APIs the code calls, not just its bytecode. */
    val api: Boolean,

    /** Internal names to leave alone, matched on name boundaries. */
    val excludedClasses: List<String>,

    /** Whether a construct the target cannot express stops the run. */
    val failOnUnsupported: Boolean,

    /** Where the injected utility goes, or null to name it by its contents. */
    val runtimeClass: String?,

    /** The `ct.sym` to read the target's API from, or null for this JVM's own. */
    val ctSym: File?
) {

    /** What the API check was able to learn, and what it wants said about it. */
    private class ApiRecord(val index: ApiIndex, val warning: String?)

    /**
     * The target's API, read once however many jars go through.
     *
     * Reading it means walking a zip of thousands of signature files, so it is
     * lazy and shared. Nothing reads it at all when the API check is off.
     */
    private val record: ApiRecord by lazy { readApiRecord() }

    /**
     * Where the target release's API comes from, or why it could not be had.
     *
     * An empty index is not fatal. The rebuilds are decided by release alone and
     * carry on without it; only the open-ended reporting needs to know what the
     * target actually had, so losing it costs that half and nothing else.
     *
     * A `ct.sym` handed to [Builder.ctSym] is different, and does throw. Nobody
     * passes a path by accident, so an unreadable one is a mistake to be told
     * about rather than a limitation to work around.
     */
    private fun readApiRecord(): ApiRecord {
        if (!api) return ApiRecord(ApiIndex.EMPTY, null)

        val given = ctSym
        val file = given ?: ApiIndex.currentJdkCtSym() ?: return ApiRecord(
            ApiIndex.EMPTY,
            "the API check was asked for, but this JDK ships no lib/ct.sym. Calls with a " +
                "rebuild still get one; the rest cannot be reported, because nothing says " +
                "what Java $target had."
        )

        val index = try {
            ApiIndex.read(file, target)
        } catch (e: IOException) {
            if (given != null) {
                throw LowbyteException("the ct.sym at ${file.absolutePath} could not be read: ${e.message}")
            }
            return ApiRecord(
                ApiIndex.EMPTY,
                "the API check was asked for, but this JDK's ${file.absolutePath} could not be " +
                    "read: ${e.message}. Calls with a rebuild still get one; the rest cannot " +
                    "be reported."
            )
        }

        if (index.isEmpty) {
            return ApiRecord(
                index,
                "the API check was asked for, but ${file.absolutePath} has no data for Java " +
                    "$target. Calls with a rebuild still get one; the rest cannot be reported. " +
                    "Run on a newer JDK to enable that half."
            )
        }

        return ApiRecord(index, null)
    }

    /**
     * Rewrites every class in [input] and writes the result to [output].
     *
     * Reads the whole jar before writing any of it. A call site naming a private
     * member of a nestmate says nothing about that member's access flags, so the
     * answer lives in a class file that may not have been reached yet.
     *
     * @throws LowbyteException if [input] is missing, or if something cannot be
     *   expressed at [target] and [failOnUnsupported] is on, in which case
     *   [output] is deleted first.
     */
    fun downgrade(input: File, output: File): DowngradeResult {
        if (!input.exists()) {
            throw LowbyteException("Input JAR not found: ${input.absolutePath}")
        }

        output.parentFile?.mkdirs()

        // Collected instead of thrown on sight, otherwise you fix one problem
        // per build. A set keeps discovery order and drops duplicate findings.
        val findings = linkedSetOf<String>()
        val apiFindings = linkedSetOf<String>()

        var downgraded = 0
        var copied = 0
        var droppedSignatures = 0
        var droppedModuleInfo = false
        var injectedClass: String? = null
        var injectedMethods = emptySet<String>()

        JarFile(input).use { jar ->
            // The injected utility is named after what it ends up holding, so
            // which methods a jar needs has to be known before a call site can
            // be pointed at it.
            val runtimeMethods = scanRuntimeUsage(jar)
            val context = DowngradeContext(
                nests = scanNests(jar),
                api = if (api) ApiSettings(target, record.index, runtimeClassName(runtimeMethods)) else null,
                onApiFinding = { apiFindings += it }
            )
            val written = mutableSetOf<String>()

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

                    if (isDroppedModuleInfo(entry.name)) {
                        droppedModuleInfo = true
                        continue
                    }

                    jos.putNextEntry(ZipEntry(entry.name))

                    if (isManifest(entry.name)) {
                        jos.write(withoutEntryDigests(jar.getInputStream(entry).readAllBytes()))
                        copied++
                    } else if (entry.name.endsWith(".class") &&
                        !isExcluded(entry.name) &&
                        !isUnloweredModuleInfo(entry.name)
                    ) {
                        val internalName = entry.name.removeSuffix(".class")
                        val classBytes = jar.getInputStream(entry).readAllBytes()
                        jos.write(ClassDowngrader.downgrade(classBytes, target, context) { feature ->
                            findings += "$internalName: $feature"
                        })
                        written += internalName
                        downgraded++
                    } else {
                        // Resource files, and excluded classes, unchanged.
                        jar.getInputStream(entry).copyTo(jos)
                        copied++
                    }

                    jos.closeEntry()
                }

                copied += writeMarkerClasses(jos, context)

                val injected = writeRuntimeClass(jos, context, runtimeMethods)
                if (injected != null) {
                    injectedClass = injected.first
                    injectedMethods = runtimeMethods
                    copied += injected.second
                }
            }

            // A bridge that was never emitted is a NoSuchMethodError waiting to
            // happen, so an owner we never rewrote is reported rather than shipped.
            (context.nests.bridgedOwners - written).forEach {
                findings += "$it: holds a private member reached from its nest, but was not rewritten"
            }
        }

        if (failOnUnsupported && findings.isNotEmpty()) {
            // Deleted so nothing downstream picks up an artifact that would
            // break at runtime.
            output.delete()
            throw LowbyteException(
                "Lowbyte: ${findings.size} construct(s) cannot be expressed in Java $target:\n" +
                    findings.joinToString("\n") { "  - $it" }
            )
        }

        return DowngradeResult(
            target = target,
            downgraded = downgraded,
            copied = copied,
            droppedSignatures = droppedSignatures,
            droppedModuleInfo = droppedModuleInfo,
            injectedClass = injectedClass,
            injectedMethods = injectedMethods,
            unsupported = findings.toList(),
            apiFindings = apiFindings.toList(),
            warnings = listOfNotNull(record.warning)
        )
    }

    /**
     * Which utility methods the jar needs, before anything is written.
     *
     * Empty unless the API check is on, and empty when nothing in the jar calls
     * something the utility covers, in which case no class is injected at all.
     */
    private fun scanRuntimeUsage(jar: JarFile): Set<String> {
        if (!api) return emptySet()

        val needed = mutableSetOf<String>()
        classEntries(jar).forEach { entry ->
            needed += ApiRewrites.runtimeMethodsNeeded(jar.getInputStream(entry).readAllBytes(), target)
        }
        return needed
    }

    /**
     * Adds the utility, trimmed to what was used and stripped of annotations.
     *
     * Null when nothing needed it. Otherwise the name it went in under and how
     * many class files that took, which is more than one when a kept method
     * needs a helper type.
     */
    private fun writeRuntimeClass(
        jos: JarOutputStream,
        context: DowngradeContext,
        methods: Set<String>
    ): Pair<String, Int>? {
        val settings = context.api
        if (settings == null || methods.isEmpty()) return null

        val name = settings.runtimeClassName
        val injected = RuntimeApi.inject(name, methods)
        injected.forEach { (className, bytes) ->
            jos.putNextEntry(ZipEntry("$className.class"))
            jos.write(bytes)
            jos.closeEntry()
        }

        return name to injected.size
    }

    /**
     * Where the injected utility goes.
     *
     * The default is content-addressed, so two jars holding the same methods
     * agree on the name and the bytes. Setting one by hand is for builds that
     * relocate it, and then keeping it distinct is the build's problem.
     */
    private fun runtimeClassName(methods: Set<String>): String =
        runtimeClass?.replace('.', '/') ?: RuntimeApi.defaultClassName(methods)

    /**
     * Works out which private members are reached across a nest.
     *
     * Skipped entirely above Java 11, where the nest attributes stay and no
     * bridge is needed.
     */
    private fun scanNests(jar: JarFile): NestRegistry {
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
    private fun writeMarkerClasses(jos: JarOutputStream, context: DowngradeContext): Int {
        val markers = context.nests.markerClasses.sorted()
        markers.forEach { internalName ->
            jos.putNextEntry(ZipEntry("$internalName.class"))
            jos.write(NestRegistry.markerClassBytes(internalName, ClassFileVersion.fromJavaVersion(target)))
            jos.closeEntry()
        }
        return markers.size
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
        return excludedClasses.any { excluded ->
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
    private fun isDroppedModuleInfo(entryName: String): Boolean =
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
    private fun isUnloweredModuleInfo(entryName: String): Boolean =
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

    /**
     * Collects the settings, then hands over a [Lowbyte] that cannot be changed.
     *
     * Nested rather than a subclass on purpose. A builder that extended
     * [Lowbyte] would inherit [downgrade] and so be callable half configured,
     * which is a mistake worth making impossible rather than documenting.
     */
    class Builder internal constructor() {

        private var target: Int? = null
        private var api = false
        private val excluded = mutableListOf<String>()
        private var failOnUnsupported = true
        private var runtimeClass: String? = null
        private var ctSym: File? = null

        /** Java release to aim at: 8, 11, 17, and so on. Required. */
        fun target(release: Int): Builder = apply { target = release }

        /**
         * Look at the JDK APIs the code calls, not just the bytecode it is made of.
         *
         * Off by default, because it is the one part of Lowbyte that changes
         * calls the target could have linked perfectly well. On, the calls with
         * an exact pre-target equivalent are rewritten and the rest are
         * reported, never fatally.
         */
        fun api(enabled: Boolean): Builder = apply { api = enabled }

        /** Internal or dotted names to leave alone. Matched on name boundaries. */
        fun exclude(vararg names: String): Builder = apply { excluded += names }

        /** The same, from a collection. */
        fun exclude(names: Collection<String>): Builder = apply { excluded += names }

        /**
         * Whether a construct the target cannot express stops the run.
         *
         * On by default. Off turns the same findings into
         * [unsupported][DowngradeResult.unsupported] and keeps the jar.
         */
        fun failOnUnsupported(fail: Boolean): Builder = apply { failOnUnsupported = fail }

        /**
         * Where the injected utility goes.
         *
         * Left alone the name comes from the utility's own bytes and the methods
         * kept from it, so two jars holding the same methods agree on both the
         * name and the contents, and shading them together is harmless. Set one
         * to relocate it, and keeping it distinct becomes your problem.
         */
        fun runtimeClass(name: String?): Builder = apply { runtimeClass = name }

        /**
         * Where to read the target release's API from.
         *
         * Defaults to the `lib/ct.sym` of the JVM this is running on, which is
         * the file `javac --release` consults. Worth setting when the JVM
         * running the downgrade is not the one whose record you want.
         */
        fun ctSym(file: File?): Builder = apply { ctSym = file }

        /**
         * @throws IllegalArgumentException if no target was set, if it is one
         *   Lowbyte cannot produce, or if a `ct.sym` was named that is not there.
         */
        fun build(): Lowbyte {
            val release = requireNotNull(target) { "no target release was set" }
            // Checked here rather than at the first jar, so a misconfigured
            // build fails before it has read anything.
            ClassFileVersion.requireSupportedTarget(release)
            ctSym?.let { require(it.isFile) { "no ct.sym at ${it.absolutePath}" } }

            return Lowbyte(
                target = release,
                api = api,
                excludedClasses = excluded.toList(),
                failOnUnsupported = failOnUnsupported,
                runtimeClass = runtimeClass,
                ctSym = ctSym
            )
        }
    }

    companion object {

        /** A builder with nothing set. */
        @JvmStatic
        fun builder(): Builder = Builder()

        /** A builder already aimed at a release, which is the required setting. */
        @JvmStatic
        fun targeting(release: Int): Builder = Builder().target(release)
    }
}
