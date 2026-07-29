package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import dev.iiahmed.lowbyte.downgrade.ClassDowngrader
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals

/**
 * Sealedness lives entirely in the `PermittedSubclasses` attribute, so the whole
 * transform is whether that attribute survives.
 */
class SealedTypesTransformerTest {

    private companion object {
        const val INTERNAL_NAME = "lowbyte/sample/Sealed"
        val PERMITTED = listOf("lowbyte/sample/First", "lowbyte/sample/Second")
    }

    @Test
    fun permittedSubclassesAreDroppedBelow17() {
        val downgraded = ClassDowngrader.downgrade(sealedInterface(), 16) { error("unexpected: $it") }

        assertEquals(emptyList(), Fixtures.shapeOf(downgraded).permittedSubclasses)
        assertEquals(16, ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(downgraded)))
    }

    @Test
    fun targetsAtOrAbove17KeepTheSeal() {
        val untouched = ClassDowngrader.downgrade(sealedInterface(), 17) { error("unexpected: $it") }

        assertEquals(PERMITTED, Fixtures.shapeOf(untouched).permittedSubclasses)
    }

    @Test
    fun realJavacSealedInterfaceIsUnsealed() {
        // RecordSample$Shape is `sealed interface Shape permits Circle, Rect, Group`.
        val downgraded = Fixtures.downgrade("RecordSample", targetJava = 11)

        assertEquals(emptyList(), Fixtures.shapeOf(downgraded.getValue("RecordSample\$Shape")).permittedSubclasses)
    }

    private fun sealedInterface(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            ClassFileVersion.fromJavaVersion(21),
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
            INTERNAL_NAME,
            null,
            "java/lang/Object",
            null
        )
        PERMITTED.forEach { cw.visitPermittedSubclass(it) }
        cw.visitEnd()
        return cw.toByteArray()
    }
}
