package dev.iiahmed.lowbyte.api

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.nio.file.FileSystems
import java.net.URI
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

    /** Every type the index holds, for tooling that diffs one release against another. */
    val typeNames: Set<String> get() = types.keys

    /**
     * What [owner] declares itself, without the supertype walk [hasMember] does.
     *
     * Diffing two releases wants exactly this: a member that moved up into a
     * supertype was not added, and counting it as added would bury the ones that
     * were. `String.chars` reads as new in 9 by this measure and is not, because
     * `CharSequence` had it in 8, which is why the report says so rather than
     * leaving it to be rediscovered.
     */
    fun declaredMembers(owner: String): Set<String> = types[owner]?.members.orEmpty()

    /**
     * The same call with a different return type, if this release had one.
     *
     * Java 9 gave the buffers covariant overrides: `ByteBuffer.flip` returned
     * `Buffer` in 8 and returns `ByteBuffer` from 9 on. Nothing was added in any
     * useful sense, but the descriptor changed, so a call compiled on 9 names a
     * method Java 8 does not have and dies with a `NoSuchMethodError`. Those
     * are worth telling apart from real additions: the older one is right there
     * to call, needing only a cast.
     */
    fun covariantOf(owner: String, name: String, descriptor: String): String? {
        val parameters = descriptor.substringBefore(')') + ')'
        val seen = mutableSetOf<String>()
        val pending = ArrayDeque<String>().apply { add(owner) }

        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue

            val type = types[current] ?: continue
            type.members.firstOrNull { it.startsWith(name + parameters) && it != name + descriptor }
                ?.let { return "$current.$it" }

            type.superName?.let { pending.add(it) }
            pending.addAll(type.interfaces)
        }

        return null
    }

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
         * The API of the JVM running right now, read from its own image.
         *
         * `ct.sym` records every release *except* the current one, because javac
         * compiles against the live platform for that. So the newest release can
         * only be had this way, and only by running on it.
         *
         * Unlike a `.sig` file, an image class carries its private members too,
         * so those are dropped here to leave the same shape [read] returns.
         */
        fun readRunningPlatform(module: String): ApiIndex {
            val root = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules", module)
            if (!Files.isDirectory(root)) return EMPTY

            val types = mutableMapOf<String, Type>()
            Files.walk(root).use { paths ->
                paths.filter { it.toString().endsWith(".class") }.forEach { path ->
                    val name = root.relativize(path).toString().replace('\\', '/').removeSuffix(".class")
                    val collector = Collector(apiOnly = true)
                    ClassReader(Files.readAllBytes(path)).accept(collector, ClassReader.SKIP_CODE)
                    types[name] = collector.toType()
                }
            }
            return ApiIndex(types)
        }

        /**
         * Reads every type that existed at [release].
         *
         * Returns [EMPTY] when the file has nothing for that release, which is
         * what happens on a JDK too old to know about it. The caller decides
         * whether that is worth complaining about, since it only matters if the
         * API check was asked for.
         */
        fun read(ctSym: File, release: Int, module: String? = null): ApiIndex {
            val character = releaseCharacter(release) ?: return EMPTY
            val types = mutableMapOf<String, Type>()

            ZipFile(ctSym).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory || !entry.name.endsWith(".sig")) return@forEach

                    val releases = entry.name.substringBefore('/')
                    if (!releases.contains(character)) return@forEach

                    // <releases>/<module>/<package>/<Type>.sig
                    val withoutReleases = entry.name.substringAfter('/')
                    if (module != null && withoutReleases.substringBefore('/') != module) return@forEach

                    val type = withoutReleases
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
        private class Collector(private val apiOnly: Boolean = false) : ClassVisitor(Opcodes.ASM9) {

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
                if (!apiOnly || isApi(access)) into += key(name.orEmpty(), descriptor.orEmpty())
                return null
            }

            override fun visitField(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                value: Any?
            ): FieldVisitor? {
                if (!apiOnly || isApi(access)) into += key(name.orEmpty(), descriptor.orEmpty())
                return null
            }

            /** What a `.sig` file would have kept: the members callers can see. */
            private fun isApi(access: Int) =
                (access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED)) != 0
        }
    }
}
