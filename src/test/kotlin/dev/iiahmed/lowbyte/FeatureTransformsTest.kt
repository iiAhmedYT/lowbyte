package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.transform.FeatureTransform
import dev.iiahmed.lowbyte.transform.FeatureTransforms
import dev.iiahmed.lowbyte.transform.SwitchBootstrapsTransform
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTransformsTest {

    /** Records the order in which the chain hands a class to each transform. */
    private class Recorder(
        override val name: String,
        override val introducedIn: Int,
        private val visited: MutableList<String>
    ) : FeatureTransform {

        override fun wrap(next: ClassVisitor, onUnsupported: (String) -> Unit): ClassVisitor =
            object : ClassVisitor(Opcodes.ASM9, next) {
                override fun visit(
                    version: Int,
                    access: Int,
                    className: String?,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?
                ) {
                    visited += name
                    super.visit(version, access, className, signature, superName, interfaces)
                }
            }
    }

    private fun recorders(visited: MutableList<String>) = listOf(
        Recorder("old", introducedIn = 11, visited = visited),
        Recorder("newest", introducedIn = 21, visited = visited),
        Recorder("middle", introducedIn = 16, visited = visited)
    )

    @Test
    fun onlyTransformsNewerThanTheTargetApply() {
        val visited = mutableListOf<String>()

        assertEquals(
            listOf("old", "middle", "newest"),
            FeatureTransforms.forTarget(8, recorders(visited)).map { it.name }
        )
        assertEquals(
            listOf("middle", "newest"),
            FeatureTransforms.forTarget(11, recorders(visited)).map { it.name }
        )
        assertEquals(
            listOf("newest"),
            FeatureTransforms.forTarget(17, recorders(visited)).map { it.name }
        )
        assertEquals(
            emptyList(),
            FeatureTransforms.forTarget(21, recorders(visited)).map { it.name }
        )
    }

    @Test
    fun newestFeatureIsLoweredFirst() {
        val visited = mutableListOf<String>()

        val chain = FeatureTransforms.chain(ClassWriter(0), targetJava = 8, onUnsupported = {}, transforms = recorders(visited))
        chain.visit(52, Opcodes.ACC_PUBLIC, "Sample", null, "java/lang/Object", null)

        // Outermost runs first: anything a newer transform emits still passes
        // through the older ones below it.
        assertEquals(listOf("newest", "middle", "old"), visited)
    }

    @Test
    fun chainIsAPassthroughWhenNothingApplies() {
        val visited = mutableListOf<String>()

        val writer = ClassWriter(0)
        val chain = FeatureTransforms.chain(writer, targetJava = 21, onUnsupported = {}, transforms = recorders(visited))

        assertTrue(chain === writer, "an empty chain should not wrap the writer")
        assertEquals(emptyList(), visited)
    }

    @Test
    fun switchTransformIsRegistered() {
        assertTrue(
            FeatureTransforms.ALL.contains(SwitchBootstrapsTransform),
            "SwitchBootstrapsTransform should be in the registry"
        )
        assertEquals(21, SwitchBootstrapsTransform.introducedIn)
        assertEquals(
            listOf(SwitchBootstrapsTransform),
            FeatureTransforms.forTarget(17),
            "the switch transform should apply to a Java 17 target"
        )
        assertEquals(emptyList(), FeatureTransforms.forTarget(21))
    }
}
