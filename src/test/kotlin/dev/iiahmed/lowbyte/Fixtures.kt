package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.api.ApiIndex
import dev.iiahmed.lowbyte.api.ApiRewrites
import dev.iiahmed.lowbyte.api.ApiSettings
import dev.iiahmed.lowbyte.api.RuntimeApi
import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import dev.iiahmed.lowbyte.downgrade.ClassDowngrader
import dev.iiahmed.lowbyte.downgrade.DowngradeContext
import dev.iiahmed.lowbyte.nest.NestRegistry
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor
import java.util.zip.ZipInputStream
import kotlin.test.fail

/**
 * Access to the checked-in `javac21` test fixtures, plus the bytecode inspection
 * used to assert things about them.
 *
 * Everything read here except the `.java.txt` sources is produced by the
 * `regenerateJavac21Fixtures` Gradle task, which compiles and runs each sample on
 * a real JDK 21. Run it after editing a `.java.txt` source.
 */
object Fixtures {

    private const val ROOT = "/javac21/generated"

    /** Where the hand-written sources live, as a resource path. */
    const val SOURCES = "/javac21/sources"

    /**
     * Every sample the differential test runs.
     *
     * ApiSample is deliberately absent: it calls Java 9 APIs on purpose, so it
     * is the one sample that cannot run on an older JVM. See JdkApiLimitationTest.
     */
    val SAMPLES = listOf(
        "EnumSample", "RecordSample", "RecordOpsSample", "NestSample",
        "EnumDescSample", "ConcatSample", "InterfaceSample"
    )

    /** Samples that only make sense with the opt-in API conversion turned on. */
    val API_SAMPLES = listOf("ApiConversionSample", "RuntimeApiSample")

    /**
     * Samples that exist but are deliberately not run by the differential test.
     *
     * ApiSample calls Java 9 APIs on purpose to show what a downgrade cannot fix
     * on its own, so running it on an older JVM is supposed to fail.
     */
    val EXCLUDED = listOf("ApiSample")

    /**
     * Every sample, however it is used.
     *
     * [sourcesAreAllAccountedFor][FixtureLayoutTest] holds this to the sources on
     * disk, so a sample cannot be added and then silently never run.
     */
    val ALL_SAMPLES: List<String> get() = SAMPLES + API_SAMPLES + EXCLUDED

    const val SWITCH_BOOTSTRAPS = "java/lang/runtime/SwitchBootstraps"
    const val OBJECT_METHODS = "java/lang/runtime/ObjectMethods"
    const val STRING_CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory"

    /**
     * Class files of [sample], downgraded to [targetJava], keyed by class name.
     *
     * The nest scan and the marker classes are done the same way
     * [dev.iiahmed.lowbyte.tasks.DowngradeBytecode] does them, so a sample that
     * reaches across its own nest is downgraded as it would be in a real jar.
     */
    fun downgrade(sample: String, targetJava: Int, api: Boolean = false): Map<String, ByteArray> {
        val originals = classNames(sample).associateWith { readClass(it) }

        val nests = if (targetJava < NestRegistry.INTRODUCED_IN) {
            NestRegistry.scan(originals.values.asSequence())
        } else {
            NestRegistry.EMPTY
        }
        // Mirrors the task: work out what the injected utility needs before
        // anything is rewritten, since call sites have to name it.
        val runtimeMethods = if (api) {
            originals.values.flatMapTo(mutableSetOf()) { ApiRewrites.runtimeMethodsNeeded(it, targetJava) }
        } else {
            emptySet()
        }
        val runtimeClassName = RuntimeApi.defaultClassName(runtimeMethods)

        val apiFindings = mutableListOf<String>()
        val context = DowngradeContext(
            nests = nests,
            api = if (api) ApiSettings(targetJava, apiIndexFor(targetJava), runtimeClassName) else null,
            onApiFinding = { apiFindings += it }
        )

        val downgraded = originals.mapValues { (name, classBytes) ->
            ClassDowngrader.downgrade(classBytes, targetJava, context) { fail("$name: unsupported: $it") }
        }.toMutableMap()

        nests.markerClasses.forEach { internalName ->
            downgraded[internalName] =
                NestRegistry.markerClassBytes(internalName, ClassFileVersion.fromJavaVersion(targetJava))
        }

        if (runtimeMethods.isNotEmpty()) {
            // Keyed the way MapClassLoader asks for it, which is the binary name.
            // The samples themselves have no package, so only this one differs.
            downgraded[runtimeClassName.replace('/', '.')] =
                RuntimeApi.inject(runtimeClassName, runtimeMethods)
        }

        return downgraded
    }

    /** The API index for a release, or empty when this JDK cannot supply one. */
    fun apiIndexFor(targetJava: Int): ApiIndex =
        ApiIndex.currentJdkCtSym()?.let { ApiIndex.read(it, targetJava) } ?: ApiIndex.EMPTY

    /** The nest registry a sample's own classes produce. */
    fun nestsOf(sample: String): NestRegistry =
        NestRegistry.scan(classNames(sample).asSequence().map { readClass(it) })

    /**
     * Each sample's classes, read once.
     *
     * The archive is the class list as well as the classes, so there is no
     * separate manifest to fall out of step with what is beside it.
     */
    private val classesBySample: Map<String, Map<String, ByteArray>> by lazy {
        ALL_SAMPLES.associateWith { readClasses(it) }
    }

    private fun readClasses(sample: String): Map<String, ByteArray> {
        val archive = javaClass.getResourceAsStream("$ROOT/$sample.classes.zip")
            ?: fail("missing fixture $sample.classes.zip, run `gradlew regenerateJavac21Fixtures`")

        val classes = linkedMapOf<String, ByteArray>()
        archive.use { stream ->
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.endsWith(".class")) {
                        classes[entry.name.removeSuffix(".class")] = zip.readBytes()
                    }
                }
            }
        }
        check(classes.isNotEmpty()) { "$sample.classes.zip holds no classes" }
        return classes
    }

    /** The class names produced by compiling [sample], in the order they were written. */
    fun classNames(sample: String): List<String> =
        classesBySample[sample]?.keys?.toList()
            ?: fail("unknown sample $sample, add it to one of Fixtures.SAMPLES, API_SAMPLES or EXCLUDED")

    /** What [sample] printed when run on a real JDK 21. */
    fun baseline(sample: String): String = readText("$sample.baseline.txt").trim()

    /**
     * A single class by name, from whichever sample compiled it.
     *
     * Samples compile separately and their top-level names are distinct, so a
     * name identifies one class across the whole fixture set.
     */
    fun readClass(name: String): ByteArray =
        classesBySample.values.firstNotNullOfOrNull { it[name] }
            ?: fail("missing fixture class $name, run `gradlew regenerateJavac21Fixtures`")

    private fun readText(fileName: String): String =
        javaClass.getResourceAsStream("$ROOT/$fileName")?.use { it.readBytes() }
            ?.toString(Charsets.UTF_8)?.replace("\r\n", "\n")
            ?: fail("missing fixture $fileName, run `gradlew regenerateJavac21Fixtures`")

    /** Counts the `SwitchBootstraps` call sites left in a class. */
    fun switchCallSites(classBytes: ByteArray): Int =
        countInvokeDynamic(classBytes) { it?.owner == SWITCH_BOOTSTRAPS }

    /** Counts the `ObjectMethods` call sites left in a class. */
    fun objectMethodsCallSites(classBytes: ByteArray): Int =
        countInvokeDynamic(classBytes) { it?.owner == OBJECT_METHODS }

    /** Counts the `StringConcatFactory` call sites left in a class. */
    fun stringConcatCallSites(classBytes: ByteArray): Int =
        countInvokeDynamic(classBytes) { it?.owner == STRING_CONCAT_FACTORY }

    /** Counts every `invokedynamic` in a class, whatever its bootstrap. */
    fun invokeDynamicCount(classBytes: ByteArray): Int = countInvokeDynamic(classBytes) { true }

    /** Every method a class calls, as `owner.name+descriptor`. */
    fun methodCallTargets(classBytes: ByteArray): Set<String> {
        val targets = mutableSetOf<String>()
        ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?
            ) = object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(
                    opcode: Int,
                    insnOwner: String?,
                    insnName: String?,
                    insnDescriptor: String?,
                    isInterface: Boolean
                ) {
                    targets += "${insnOwner.orEmpty()}.${insnName.orEmpty()}${insnDescriptor.orEmpty()}"
                }
            }
        }, 0)
        return targets
    }

    /** The methods of [owner] reached by `invokeinterface`, as `name+descriptor`. */
    fun invokeInterfaceTargets(classBytes: ByteArray, owner: String): Set<String> {
        val targets = mutableSetOf<String>()
        ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?
            ) = object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(
                    opcode: Int,
                    insnOwner: String?,
                    insnName: String?,
                    insnDescriptor: String?,
                    isInterface: Boolean
                ) {
                    if (opcode == Opcodes.INVOKEINTERFACE && insnOwner == owner) {
                        targets += "${insnName.orEmpty()}${insnDescriptor.orEmpty()}"
                    }
                }
            }
        }, 0)
        return targets
    }

    /**
     * Counts `CONSTANT_Dynamic` entries by walking the raw constant pool.
     *
     * A visitor would only see the ones something still refers to, and the
     * entries that matter here are exactly the orphans: below class file version
     * 55 a leftover condy is a `ClassFormatError` whether or not any instruction
     * mentions it.
     */
    fun constantDynamicCount(classBytes: ByteArray): Int {
        val reader = ClassReader(classBytes)
        var count = 0
        for (item in 1 until reader.itemCount) {
            // Zero marks the unused second slot of a long or double entry.
            val offset = reader.getItem(item)
            if (offset > 0 && reader.readByte(offset - 1) == CONSTANT_DYNAMIC_TAG) count++
        }
        return count
    }

    /** JVMS table 4.4-B. */
    private const val CONSTANT_DYNAMIC_TAG = 17

    /** Everything about a class that says "record", "sealed" or "nest". */
    class Shape(
        val superName: String?,
        val isRecord: Boolean,
        val recordComponents: List<String>,
        val permittedSubclasses: List<String>,
        val nestHost: String?,
        val nestMembers: List<String>,
        val methods: List<String>
    )

    fun shapeOf(classBytes: ByteArray): Shape {
        var superName: String? = null
        var isRecord = false
        var nestHost: String? = null
        val nestMembers = mutableListOf<String>()
        val methods = mutableListOf<String>()
        val components = mutableListOf<String>()
        val permitted = mutableListOf<String>()

        ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                access: Int,
                name: String?,
                signature: String?,
                superClass: String?,
                interfaces: Array<out String>?
            ) {
                superName = superClass
                // ClassReader sets this pseudo flag from the Record attribute.
                isRecord = (access and Opcodes.ACC_RECORD) != 0
            }

            override fun visitRecordComponent(
                name: String?,
                descriptor: String?,
                signature: String?
            ): RecordComponentVisitor? {
                components += name.orEmpty()
                return null
            }

            override fun visitPermittedSubclass(permittedSubclass: String?) {
                permitted += permittedSubclass.orEmpty()
            }

            override fun visitNestHost(host: String?) {
                nestHost = host
            }

            override fun visitNestMember(member: String?) {
                nestMembers += member.orEmpty()
            }

            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? {
                methods += "${name.orEmpty()}${descriptor.orEmpty()}"
                return null
            }
        }, 0)

        return Shape(superName, isRecord, components, permitted, nestHost, nestMembers, methods)
    }

    private fun countInvokeDynamic(classBytes: ByteArray, predicate: (Handle?) -> Boolean): Int {
        var count = 0
        ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?
            ) = object : MethodVisitor(Opcodes.ASM9) {
                override fun visitInvokeDynamicInsn(
                    name: String?,
                    descriptor: String?,
                    bootstrapMethodHandle: Handle?,
                    vararg bootstrapMethodArguments: Any?
                ) {
                    if (predicate(bootstrapMethodHandle)) count++
                }
            }
        }, 0)
        return count
    }

    fun majorVersionOf(classBytes: ByteArray): Int {
        var major = 0
        ClassReader(classBytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                access: Int,
                name: String?,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?
            ) {
                major = version and 0xFFFF
            }
        }, 0)
        return major
    }

    /** Loads a set of class files without putting them on the test classpath. */
    class MapClassLoader(
        private val classes: Map<String, ByteArray>
    ) : ClassLoader(Fixtures::class.java.classLoader) {

        override fun findClass(name: String): Class<*> {
            val bytes = classes[name] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }
}
