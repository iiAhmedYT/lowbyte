package dev.iiahmed.lowbyte.api

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipFile

/**
 * Which JDK members existed at a given release.
 *
 * The data is not ours. Every JDK ships `lib/ct.sym`, the same file `javac
 * --release` consults, holding a stripped class file per type per release. So
 * "does `java.util.List` have `of` on 8" is answered by the compiler's own
 * record rather than by a list we would have to maintain and get wrong.
 *
 * Inside, releases are single characters: `8`, `9`, then `A` for 10 up to `L`
 * for 21. A directory is named with every release its contents apply to, so
 * `89ABCDEFGHIJK` holds the types that were the same from 8 through 20. Finding
 * one release therefore means reading every directory whose name contains that
 * character.
 */
class ApiIndex private constructor(
    private val types: Map<String, Type>
) {

    /** One type's own members, and where the rest of them come from. */
    internal class Type(
        val members: Set<String>,
        val superName: String?,
        val interfaces: List<String>
    )

    val isEmpty: Boolean get() = types.isEmpty()

    /** How many types the index knows about, for reporting. */
    val typeCount: Int get() = types.size

    /** Whether the index has anything to say about this type at all. */
    fun knowsType(owner: String): Boolean = types.containsKey(owner)

    /**
     * Whether [owner] had this member at the indexed release, inherited or not.
     *
     * The supertypes have to be walked. A `.sig` file lists what its type
     * *declares*, so `LinkedHashSet` has no `add` of its own and `ArrayList` no
     * `hashCode`, and asking only the named type would report half the ordinary
     * calls in a program as missing.
     *
     * Only meaningful when [knowsType] is true. A type the JDK never had is not
     * a missing member, it is somebody else's class.
     */
    fun hasMember(owner: String, name: String, descriptor: String): Boolean {
        val wanted = key(name, descriptor)
        val seen = mutableSetOf<String>()
        val pending = ArrayDeque<String>().apply { add(owner) }

        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue

            val type = types[current] ?: continue
            if (wanted in type.members) return true

            type.superName?.let { pending.add(it) }
            pending.addAll(type.interfaces)
        }

        return false
    }

    companion object {

        val EMPTY = ApiIndex(emptyMap())

        private fun key(name: String, descriptor: String) = "$name$descriptor"

        /**
         * The character `ct.sym` uses for a release.
         *
         * Releases run `1`-`9` then `A` onwards, so 10 is `A` and 21 is `L`.
         */
        fun releaseCharacter(release: Int): Char? = when {
            release in 1..9 -> '0' + release
            release in 10..35 -> 'A' + (release - 10)
            else -> null
        }

        /** The `ct.sym` of the JVM currently running, if it has one. */
        fun currentJdkCtSym(): File? =
            File(System.getProperty("java.home"), "lib/ct.sym").takeIf { it.isFile }

        /**
         * Reads every type that existed at [release].
         *
         * Returns [EMPTY] when the file has nothing for that release, which is
         * what happens on a JDK too old to know about it. The caller decides
         * whether that is worth complaining about, since it only matters if the
         * API check was asked for.
         */
        fun read(ctSym: File, release: Int): ApiIndex {
            val character = releaseCharacter(release) ?: return EMPTY
            val types = mutableMapOf<String, Type>()

            ZipFile(ctSym).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory || !entry.name.endsWith(".sig")) return@forEach

                    val releases = entry.name.substringBefore('/')
                    if (!releases.contains(character)) return@forEach

                    // <releases>/<module>/<package>/<Type>.sig
                    val type = entry.name
                        .substringAfter('/')
                        .substringAfter('/')
                        .removeSuffix(".sig")

                    zip.getInputStream(entry).use { stream ->
                        val collector = Collector()
                        ClassReader(stream.readBytes()).accept(collector, ClassReader.SKIP_CODE)
                        types[type] = collector.toType()
                    }
                }
            }

            return ApiIndex(types)
        }

        /**
         * A stripped class file carries the members and nothing else, so the
         * names and descriptors are all we take.
         */
        private class Collector : ClassVisitor(Opcodes.ASM9) {

            private val into = mutableSetOf<String>()
            private var superName: String? = null
            private var interfaces: List<String> = emptyList()

            fun toType() = Type(into, superName, interfaces)

            override fun visit(
                version: Int,
                access: Int,
                name: String?,
                signature: String?,
                superClass: String?,
                interfaceNames: Array<out String>?
            ) {
                superName = superClass
                interfaces = interfaceNames?.toList().orEmpty()
            }

            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? {
                into += key(name.orEmpty(), descriptor.orEmpty())
                return null
            }

            override fun visitField(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                value: Any?
            ): FieldVisitor? {
                into += key(name.orEmpty(), descriptor.orEmpty())
                return null
            }
        }
    }
}
