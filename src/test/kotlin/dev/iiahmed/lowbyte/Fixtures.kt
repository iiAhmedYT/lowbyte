package dev.iiahmed.lowbyte

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

    private const val ROOT = "/javac21"

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
    fun downgrade(sample: String, targetJava: Int): Map<String, ByteArray> {
        val originals = classNames(sample).associateWith { readClass(it) }

        val nests = if (targetJava < NestRegistry.INTRODUCED_IN) {
            NestRegistry.scan(originals.values.asSequence())
        } else {
            NestRegistry.EMPTY
        }
        val context = DowngradeContext(nests)

        val downgraded = originals.mapValues { (name, classBytes) ->
            ClassDowngrader.downgrade(classBytes, targetJava, context) { fail("$name: unsupported: $it") }
        }.toMutableMap()

        nests.markerClasses.forEach { internalName ->
            downgraded[internalName] =
                NestRegistry.markerClassBytes(internalName, ClassFileVersion.fromJavaVersion(targetJava))
        }

        return downgraded
    }

    /** The nest registry a sample's own classes produce. */
    fun nestsOf(sample: String): NestRegistry =
        NestRegistry.scan(classNames(sample).asSequence().map { readClass(it) })

    /** The class names produced by compiling [sample]. */
    fun classNames(sample: String): List<String> =
        readText("$sample.classes.txt").lines().filter { it.isNotBlank() }

    /** What [sample] printed when run on a real JDK 21. */
    fun baseline(sample: String): String = readText("$sample.baseline.txt").trim()

    fun readClass(name: String): ByteArray =
        javaClass.getResourceAsStream("$ROOT/$name.classdata")?.use { it.readBytes() }
            ?: fail("missing fixture $name.classdata, run `gradlew regenerateJavac21Fixtures`")

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
