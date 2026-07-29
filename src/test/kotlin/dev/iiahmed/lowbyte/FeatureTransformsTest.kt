package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.transform.FeatureTransform
import dev.iiahmed.lowbyte.transform.FeatureTransforms
import dev.iiahmed.lowbyte.transform.RecordsTransform
import dev.iiahmed.lowbyte.transform.SealedTypesTransform
import dev.iiahmed.lowbyte.transform.SwitchBootstrapsTransform
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertSame
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

        assertSame(chain, writer, "an empty chain should not wrap the writer")
        assertEquals(emptyList(), visited)
    }

    @Test
    fun everyTransformIsRegistered() {
        assertTrue(
            FeatureTransforms.ALL.containsAll(
                listOf(RecordsTransform, SealedTypesTransform, SwitchBootstrapsTransform)
            ),
            "every transform should be in the registry"
        )
        assertEquals(16, RecordsTransform.introducedIn)
        assertEquals(17, SealedTypesTransform.introducedIn)
        assertEquals(21, SwitchBootstrapsTransform.introducedIn)
    }

    @Test
    fun eachTargetPicksUpTheFeaturesItCannotExpress() {
        assertEquals(emptyList(), FeatureTransforms.forTarget(21))
        assertEquals(
            listOf(SwitchBootstrapsTransform),
            FeatureTransforms.forTarget(17),
            "17 can express records and sealed types, but not pattern switches"
        )
        assertEquals(
            listOf(SealedTypesTransform, SwitchBootstrapsTransform),
            FeatureTransforms.forTarget(16),
            "16 has records but not sealed types"
        )
        assertEquals(
            listOf(RecordsTransform, SealedTypesTransform, SwitchBootstrapsTransform),
            FeatureTransforms.forTarget(11)
        )
    }
}
