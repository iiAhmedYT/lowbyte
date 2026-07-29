package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.downgrade.ClassDowngrader
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
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
    const val SWITCH_BOOTSTRAPS = "java/lang/runtime/SwitchBootstraps"

    /** Class files of [sample], downgraded to [targetJava], keyed by class name. */
    fun downgrade(sample: String, targetJava: Int): Map<String, ByteArray> =
        classNames(sample).associateWith { name ->
            ClassDowngrader.downgrade(readClass(name), targetJava) { fail("$name: unsupported: $it") }
        }

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

    /** Counts every `invokedynamic` in a class, whatever its bootstrap. */
    fun invokeDynamicCount(classBytes: ByteArray): Int = countInvokeDynamic(classBytes) { true }

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
