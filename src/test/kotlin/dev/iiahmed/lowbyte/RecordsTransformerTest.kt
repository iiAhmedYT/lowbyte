package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import dev.iiahmed.lowbyte.downgrade.ClassDowngrader
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Builds a Java 21 record by hand, downgrades it, then loads and runs it on the
 * test JVM.
 *
 * These cover the edge cases directly; [Javac21DowngradeTest] covers real javac
 * output against behaviour recorded on a real JDK 21.
 */
class RecordsTransformerTest {

    private companion object {
        const val INTERNAL_NAME = "lowbyte/sample/Carrier"
        const val CLASS_NAME = "lowbyte.sample.Carrier"

        const val BOOTSTRAP_DESCRIPTOR =
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/TypeDescriptor;" +
                "Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;"

        /** What the bootstrap would have built for `Carrier(int x, String s)`. */
        const val EXPECTED_TO_STRING = "Carrier[x=7, s=hi]"
        val EXPECTED_HASH_CODE = (0 * 31 + 7) * 31 + "hi".hashCode()
    }

    @Test
    fun rewritesToStringHashCodeAndEquals() {
        val carrier = downgradedCarrier(11)

        assertEquals(EXPECTED_TO_STRING, carrier.toStringOf(7, "hi"))
        assertEquals(EXPECTED_HASH_CODE, carrier.hashCodeOf(7, "hi"))

        assertTrue(carrier.equalsOf(7, "hi", carrier.instance(7, "hi")), "equal components should be equal")
        assertFalse(carrier.equalsOf(7, "hi", carrier.instance(8, "hi")), "a differing int component")
        assertFalse(carrier.equalsOf(7, "hi", carrier.instance(7, "no")), "a differing reference component")
    }

    @Test
    fun generatedEqualsRejectsNullAndForeignTypes() {
        val carrier = downgradedCarrier(11)

        assertFalse(carrier.equalsOf(7, "hi", null))
        assertFalse(carrier.equalsOf(7, "hi", "Carrier[x=7, s=hi]"))
    }

    @Test
    fun nullComponentsAreCompared() {
        val carrier = downgradedCarrier(11)

        assertTrue(carrier.equalsOf(7, null, carrier.instance(7, null)), "two null components should be equal")
        assertFalse(carrier.equalsOf(7, null, carrier.instance(7, "hi")))
        assertFalse(carrier.equalsOf(7, "hi", carrier.instance(7, null)))
        assertEquals("Carrier[x=7, s=null]", carrier.toStringOf(7, null))
        // Objects.hashCode(null) is 0, so the null component contributes nothing.
        assertEquals((0 * 31 + 7) * 31, carrier.hashCodeOf(7, null))
    }

    @Test
    fun simpleNameDropsTheEnclosingClassAndLocalNumber() {
        // Class.getSimpleName() is what the bootstrap puts before the bracket.
        assertEquals("Inner[x=7, s=hi]", downgradedCarrier(11, "p/Outer\$Inner").toStringOf(7, "hi"))
        assertEquals("Local[x=7, s=hi]", downgradedCarrier(11, "p/Outer\$1Local").toStringOf(7, "hi"))
    }

    @Test
    fun recordShapeIsStrippedBelow16() {
        val downgraded = ClassDowngrader.downgrade(carrierClass(), 11) { error("unexpected: $it") }
        val shape = Fixtures.shapeOf(downgraded)

        assertEquals(11, ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(downgraded)))
        assertEquals(0, Fixtures.invokeDynamicCount(downgraded), "ObjectMethods call sites were not rewritten")
        assertFalse(shape.isRecord, "the class is still flagged as a record")
        assertEquals(emptyList(), shape.recordComponents)
        assertEquals("java/lang/Object", shape.superName)
    }

    @Test
    fun targetsAtOrAbove16KeepTheRecord() {
        val untouched = ClassDowngrader.downgrade(carrierClass(), 16) { error("unexpected: $it") }
        val shape = Fixtures.shapeOf(untouched)

        assertEquals(3, Fixtures.invokeDynamicCount(untouched), "call sites should survive a Java 16 target")
        assertTrue(shape.isRecord)
        assertEquals(listOf("x", "s"), shape.recordComponents)
        assertEquals("java/lang/Record", shape.superName)
    }

    @Test
    fun unknownBootstrapMethodIsReported() {
        val reported = report(oneCallSite(bootstrapName = "somethingElse"))

        assertEquals(1, reported.size)
        assertTrue(reported.single().contains("somethingElse"), reported.single())
    }

    @Test
    fun unknownCallSiteNameIsReported() {
        val reported = report(oneCallSite(callSiteName = "compareTo"))

        assertEquals(1, reported.size)
        assertTrue(reported.single().contains("compareTo"), reported.single())
    }

    @Test
    fun unsupportedGetterKindIsReported() {
        // The bootstrap accepts any MethodHandle, but we only lower field reads
        // and accessor calls.
        val staticGetter = Handle(Opcodes.H_INVOKESTATIC, INTERNAL_NAME, "x", "()I", false)
        val reported = report(oneCallSite(getters = arrayOf(staticGetter)))

        assertEquals(1, reported.size)
        assertTrue(reported.single().contains("getters"), reported.single())
    }

    @Test
    fun nameCountMismatchIsReported() {
        val reported = report(oneCallSite(names = "x;s;extra"))

        assertEquals(1, reported.size)
        assertTrue(reported.single().contains("getters"), reported.single())
    }

    @Test
    fun unsupportedSignatureIsReported() {
        val reported = report(
            oneCallSite(callSiteDescriptor = "(Llowbyte/sample/Carrier;)J", returnOpcode = Opcodes.LRETURN)
        )

        assertEquals(1, reported.size)
        assertTrue(reported.single().contains("unsupported signature"), reported.single())
    }

    @Test
    fun anUnsupportedCallSiteIsLeftIntact() {
        val result = ClassDowngrader.downgrade(oneCallSite(callSiteName = "compareTo"), 11) {}

        assertEquals(1, Fixtures.invokeDynamicCount(result))
    }

    // helpers

    private fun report(classBytes: ByteArray): List<String> {
        val reported = mutableListOf<String>()
        ClassDowngrader.downgrade(classBytes, 11) { reported += it }
        return reported
    }

    /** The downgraded carrier, reached only through reflection. */
    private class Carrier(private val loaded: Class<*>) {

        fun instance(x: Int, s: String?): Any =
            loaded.getConstructor(Int::class.javaPrimitiveType, String::class.java).newInstance(x, s)

        fun toStringOf(x: Int, s: String?): String =
            loaded.getMethod("ts", loaded).invoke(null, instance(x, s)) as String

        fun hashCodeOf(x: Int, s: String?): Int =
            loaded.getMethod("hc", loaded).invoke(null, instance(x, s)) as Int

        fun equalsOf(x: Int, s: String?, other: Any?): Boolean =
            loaded.getMethod("eq", loaded, Any::class.java).invoke(null, instance(x, s), other) as Boolean
    }

    private fun downgradedCarrier(targetJava: Int, internalName: String = INTERNAL_NAME): Carrier {
        val downgraded = ClassDowngrader.downgrade(carrierClass(internalName), targetJava) {
            error("unexpected: $it")
        }
        val className = internalName.replace('/', '.')
        return Carrier(Fixtures.MapClassLoader(mapOf(className to downgraded)).loadClass(className))
    }

    /**
     * Emits `record Carrier(int x, String s)` as javac would, plus three static
     * entry points so the generated methods can be called without knowing the
     * record type at compile time.
     */
    private fun carrierClass(internalName: String = INTERNAL_NAME): ByteArray {
        val descriptor = "L$internalName;"
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)

        cw.visit(
            ClassFileVersion.fromJavaVersion(21),
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER or Opcodes.ACC_RECORD,
            internalName,
            null,
            "java/lang/Record",
            null
        )
        cw.visitRecordComponent("x", "I", null).visitEnd()
        cw.visitRecordComponent("s", "Ljava/lang/String;", null).visitEnd()
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "x", "I", null, null).visitEnd()
        cw.visitField(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "s", "Ljava/lang/String;", null, null
        ).visitEnd()

        val ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(ILjava/lang/String;)V", null, null)
        ctor.visitCode()
        ctor.visitVarInsn(Opcodes.ALOAD, 0)
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Record", "<init>", "()V", false)
        ctor.visitVarInsn(Opcodes.ALOAD, 0)
        ctor.visitVarInsn(Opcodes.ILOAD, 1)
        ctor.visitFieldInsn(Opcodes.PUTFIELD, internalName, "x", "I")
        ctor.visitVarInsn(Opcodes.ALOAD, 0)
        ctor.visitVarInsn(Opcodes.ALOAD, 2)
        ctor.visitFieldInsn(Opcodes.PUTFIELD, internalName, "s", "Ljava/lang/String;")
        ctor.visitInsn(Opcodes.RETURN)
        ctor.visitMaxs(0, 0)
        ctor.visitEnd()

        entryPoint(cw, internalName, "ts", "($descriptor)Ljava/lang/String;", "toString", 1, Opcodes.ARETURN)
        entryPoint(cw, internalName, "hc", "($descriptor)I", "hashCode", 1, Opcodes.IRETURN)
        entryPoint(
            cw, internalName, "eq", "(${descriptor}Ljava/lang/Object;)Z", "equals", 2, Opcodes.IRETURN
        )

        cw.visitEnd()
        return cw.toByteArray()
    }

    /** `public static <name><descriptor>` whose body is the bootstrap call site. */
    private fun entryPoint(
        cw: ClassWriter,
        internalName: String,
        name: String,
        descriptor: String,
        callSiteName: String,
        arguments: Int,
        returnOpcode: Int
    ) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, descriptor, null, null)
        mv.visitCode()
        repeat(arguments) { mv.visitVarInsn(Opcodes.ALOAD, it) }
        mv.visitInvokeDynamicInsn(
            callSiteName,
            descriptor,
            bootstrapHandle(),
            Type.getObjectType(internalName),
            "x;s",
            Handle(Opcodes.H_GETFIELD, internalName, "x", "I", false),
            Handle(Opcodes.H_GETFIELD, internalName, "s", "Ljava/lang/String;", false)
        )
        mv.visitInsn(returnOpcode)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** A class holding exactly one call site, for the cases we cannot lower. */
    private fun oneCallSite(
        bootstrapName: String = "bootstrap",
        callSiteName: String = "toString",
        callSiteDescriptor: String = "(Llowbyte/sample/Carrier;)Ljava/lang/String;",
        names: String = "x;s",
        getters: Array<Handle> = arrayOf(
            Handle(Opcodes.H_GETFIELD, INTERNAL_NAME, "x", "I", false),
            Handle(Opcodes.H_GETFIELD, INTERNAL_NAME, "s", "Ljava/lang/String;", false)
        ),
        returnOpcode: Int = Opcodes.ARETURN
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
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "call", callSiteDescriptor, null, null
        )
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitInvokeDynamicInsn(
            callSiteName,
            callSiteDescriptor,
            bootstrapHandle(bootstrapName),
            *(listOf<Any>(Type.getObjectType(INTERNAL_NAME), names) + getters).toTypedArray()
        )
        mv.visitInsn(returnOpcode)
        mv.visitMaxs(0, 0)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun bootstrapHandle(name: String = "bootstrap") = Handle(
        Opcodes.H_INVOKESTATIC, "java/lang/runtime/ObjectMethods", name, BOOTSTRAP_DESCRIPTOR, false
    )
}
