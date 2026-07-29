package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClassFileVersionTest {

    @Test
    fun javaVersionRoundTrip() {
        assertEquals(52, ClassFileVersion.fromJavaVersion(8))
        assertEquals(61, ClassFileVersion.fromJavaVersion(17))
        assertEquals(17, ClassFileVersion.toJavaVersion(61))
    }

    @Test
    fun previewFlagStripped() {
        // Preview-feature classes carry minor version 0xFFFF in the high bits.
        val previewJava21 = ClassFileVersion.fromJavaVersion(21) or (0xFFFF shl 16)
        assertEquals(65, ClassFileVersion.majorOf(previewJava21))
        assertTrue(ClassFileVersion.isPreview(previewJava21))
        assertFalse(ClassFileVersion.isPreview(ClassFileVersion.fromJavaVersion(21)))
    }

    @Test
    fun supportedTargetRange() {
        ClassFileVersion.requireSupportedTarget(8)
        ClassFileVersion.requireSupportedTarget(17)
        ClassFileVersion.requireSupportedTarget(25)

        assertFailsWith<IllegalArgumentException> { ClassFileVersion.requireSupportedTarget(7) }
        assertFailsWith<IllegalArgumentException> { ClassFileVersion.requireSupportedTarget(26) }
    }

}
