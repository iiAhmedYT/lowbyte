package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import dev.iiahmed.lowbyte.downgrade.ClassDowngrader
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Condy that nothing else could remove.
 *
 * There is no rewrite to check here, only that the build stops. A class file
 * below version 55 carrying a `CONSTANT_Dynamic` fails to load, so the point of
 * the detector is to turn a `ClassFormatError` on some later machine into a
 * failure on this one.
 */
class ConstantDynamicDetectorTest {

    private companion object {
        const val INTERNAL_NAME = "lowbyte/sample/Condy"

        const val CONSTANT_BOOTSTRAPS = "java/lang/invoke/ConstantBootstraps"
        const val NULL_CONSTANT_DESCRIPTOR =
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;"
        const val METAFACTORY_DESCRIPTOR =
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                "Ljava/lang/invoke/CallSite;"
    }

    @Test
    fun anLdcOfACondyIsReportedBelow11() {
        val reported = report(sampleClass(), targetJava = 9)

        assertEquals(1, reported.size, reported.toString())
        assertTrue(reported.single().contains("CONSTANT_Dynamic"), reported.single())
        assertTrue(reported.single().contains(CONSTANT_BOOTSTRAPS), reported.single())
    }

    @Test
    fun aCondyBootstrapArgumentIsReportedBelow11() {
        val reported = report(sampleClass(asBootstrapArgument = true), targetJava = 9)

        assertEquals(1, reported.size, reported.toString())
        assertTrue(reported.single().contains("CONSTANT_Dynamic"), reported.single())
    }

    @Test
    fun targetsAtOrAbove11KeepQuiet() {
        // Condy arrived in 11, so at 11 and above it needs no help.
        assertEquals(emptyList(), report(sampleClass(), targetJava = 11))
        assertEquals(emptyList(), report(sampleClass(), targetJava = 17))
    }

    @Test
    fun theConstantIsLeftIntact() {
        // Nothing here can be lowered, so the class must come through unchanged
        // apart from its version, and the caller decides what to do about it.
        val downgraded = ClassDowngrader.downgrade(sampleClass(), 9) {}

        assertEquals(9, ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(downgraded)))
        assertEquals(1, Fixtures.constantDynamicCount(downgraded))
    }

    @Test
    fun aQualifiedEnumLabelIsNotReported() {
        // SwitchBootstrapsTransformer sits further out and has already turned
        // these into field reads, so the detector should find nothing left.
        val reported = mutableListOf<String>()
        Fixtures.classNames("EnumDescSample").forEach { name ->
            ClassDowngrader.downgrade(Fixtures.readClass(name), 9) { reported += "$name: $it" }
        }

        assertEquals(emptyList(), reported)
    }

    private fun report(classBytes: ByteArray, targetJava: Int): List<String> {
        val reported = mutableListOf<String>()
        ClassDowngrader.downgrade(classBytes, targetJava) { reported += it }
        return reported
    }

    /** `ConstantBootstraps.nullConstant`, the simplest condy there is. */
    private fun nullConstant() = ConstantDynamic(
        "constant",
        "Ljava/lang/Object;",
        Handle(Opcodes.H_INVOKESTATIC, CONSTANT_BOOTSTRAPS, "nullConstant", NULL_CONSTANT_DESCRIPTOR, false)
    )

    /**
     * A class holding one condy, either loaded directly or handed to a call site
     * as a bootstrap argument.
     */
    private fun sampleClass(asBootstrapArgument: Boolean = false): ByteArray {
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
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "get", "()Ljava/lang/Object;", null, null
        )
        mv.visitCode()

        if (asBootstrapArgument) {
            mv.visitInvokeDynamicInsn(
                "run",
                "()Ljava/lang/Runnable;",
                Handle(
                    Opcodes.H_INVOKESTATIC,
                    "java/lang/invoke/LambdaMetafactory",
                    "metafactory",
                    METAFACTORY_DESCRIPTOR,
                    false
                ),
                nullConstant()
            )
        } else {
            mv.visitLdcInsn(nullConstant())
        }

        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }
}
