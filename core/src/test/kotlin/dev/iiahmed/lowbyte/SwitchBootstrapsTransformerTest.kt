package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.downgrade.ClassDowngrader
import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.lang.reflect.Method
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Builds a Java 21 class containing a `SwitchBootstraps` call site, downgrades it
 * to Java 17, then loads and runs it on the test JVM. If the rewrite were wrong
 * the class would fail verification or return wrong indices.
 *
 * These cover the edge cases directly; [Javac21DowngradeTest] covers real javac
 * output against behaviour recorded on a real JDK 21.
 */
class SwitchBootstrapsTransformerTest {

    private enum class Color { RED, GREEN, BLUE }

    private companion object {
        const val CLASS_NAME = "lowbyte.sample.SwitchSample"
        const val INTERNAL_NAME = "lowbyte/sample/SwitchSample"

        /** Labels of the synthesized typeSwitch, in match order. */
        val TYPE_LABELS = arrayOf<Any>(
            Type.getObjectType("java/lang/Integer"),
            Type.getObjectType("java/lang/String"),
            42,
            "abc"
        )

        /** Labels of the synthesized enumSwitch: two constant names, then a type. */
        val ENUM_LABELS = arrayOf<Any>(
            "RED",
            "BLUE",
            Type.getObjectType("java/lang/Comparable")
        )
    }

    @Test
    fun rewritesTypeSwitchCallSite() {
        val test = downgradedSample(17)

        // null selector -> -1, regardless of restart index.
        assertEquals(-1, test.call(null, 0))
        assertEquals(-1, test.call(null, 2))

        // Type labels.
        assertEquals(0, test.call(1, 0))
        assertEquals(1, test.call("x", 0))

        // Constant labels are only reachable once the type labels are skipped.
        assertEquals(2, test.call(42, 1))
        assertEquals(3, test.call("abc", 2))

        // Integer constants also match other Number types and Character.
        assertEquals(2, test.call(42L, 1))
        assertEquals(2, test.call('*', 1))

        // Nothing matches -> labels.length.
        assertEquals(TYPE_LABELS.size, test.call(3.5, 0))
        assertEquals(TYPE_LABELS.size, test.call("abc", 4))
    }

    @Test
    fun rewritesEnumSwitchCallSite() {
        val test = downgradedSample(17, bootstrapName = "enumSwitch", labels = ENUM_LABELS)

        assertEquals(-1, test.call(null, 0))

        // String labels match the enum constant's name, not the constant itself.
        assertEquals(0, test.call(Color.RED, 0))
        assertEquals(1, test.call(Color.BLUE, 0))

        // GREEN is named by no label, so it falls through to the type label.
        assertEquals(2, test.call(Color.GREEN, 0))

        // Restarting past a matching name falls through to the type label.
        assertEquals(2, test.call(Color.RED, 1))

        // Nothing left to match -> labels.length.
        assertEquals(ENUM_LABELS.size, test.call(Color.RED, 3))
    }

    @Test
    fun restartIndexSkipsEarlierLabels() {
        val test = downgradedSample(17)

        // An Integer selector matches label 0, but restarting past it must fall
        // through to the constant label rather than re-matching.
        assertEquals(0, test.call(42, 0))
        assertEquals(2, test.call(42, 1))
        assertEquals(TYPE_LABELS.size, test.call(42, 3))
    }

    @Test
    fun headerIsRewrittenAndCallSiteIsGone() {
        val downgraded = ClassDowngrader.downgrade(sampleClass(), 17) { error("unexpected: $it") }

        assertEquals(17, ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(downgraded)))
        assertEquals(0, Fixtures.invokeDynamicCount(downgraded), "typeSwitch call site was not rewritten")
    }

    @Test
    fun targetsAtOrAbove21KeepTheCallSite() {
        val untouched = ClassDowngrader.downgrade(sampleClass(), 21) { error("unexpected: $it") }

        assertEquals(1, Fixtures.invokeDynamicCount(untouched), "call site should survive a Java 21 target")
    }

    @Test
    fun integerLabelInEnumSwitchIsReported() {
        val reported = mutableListOf<String>()
        // Integer constants are a typeSwitch-only label kind.
        val sample = sampleClass(bootstrapName = "enumSwitch", labels = arrayOf("RED", 42))
        val result = ClassDowngrader.downgrade(sample, 17) { reported += it }

        assertEquals(1, reported.size)
        assertTrue(reported.single().contains("enumSwitch"), reported.single())
        assertEquals(1, Fixtures.invokeDynamicCount(result), "an unsupported call site must be left intact")
    }

    @Test
    fun unknownBootstrapIsReported() {
        val reported = mutableListOf<String>()
        ClassDowngrader.downgrade(sampleClass(bootstrapName = "somethingElse"), 17) { reported += it }

        assertEquals(1, reported.size)
        assertTrue(reported.single().contains("somethingElse"), reported.single())
    }

    @Test
    fun unsupportedLabelKindIsReported() {
        val reported = mutableListOf<String>()
        // A Handle is not a label kind javac emits, and not one we can rewrite.
        val bogusLabel = Handle(Opcodes.H_INVOKESTATIC, "Foo", "bar", "()V", false)
        ClassDowngrader.downgrade(sampleClass(labels = arrayOf(bogusLabel)), 17) { reported += it }

        assertEquals(1, reported.size)
        assertTrue(reported.single().contains("unsupported kind"), reported.single())
    }

    // helpers

    private fun downgradedSample(
        targetJava: Int,
        bootstrapName: String = "typeSwitch",
        labels: Array<out Any> = TYPE_LABELS
    ): Method {
        val sample = sampleClass(bootstrapName, labels)
        val downgraded = ClassDowngrader.downgrade(sample, targetJava) { error("unexpected: $it") }
        val loaded = Fixtures.MapClassLoader(mapOf(CLASS_NAME to downgraded)).loadClass(CLASS_NAME)
        return loaded.getMethod("test", Any::class.java, Int::class.javaPrimitiveType)
    }

    private fun Method.call(selector: Any?, restartIndex: Int): Int =
        invoke(null, selector, restartIndex) as Int

    /**
     * Emits `public static int test(Object selector, int restartIndex)` whose body
     * is exactly the `invokedynamic` javac produces for a pattern switch.
     */
    private fun sampleClass(
        bootstrapName: String = "typeSwitch",
        labels: Array<out Any> = TYPE_LABELS
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(
            ClassFileVersion.fromJavaVersion(21),
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            INTERNAL_NAME,
            null,
            "java/lang/Object",
            null
        )

        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "test", "(Ljava/lang/Object;I)I", null, null
        )
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        mv.visitInvokeDynamicInsn(
            bootstrapName,
            "(Ljava/lang/Object;I)I",
            Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/runtime/SwitchBootstraps",
                bootstrapName,
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;" +
                    "Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false
            ),
            *labels
        )
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

}
