package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Differential test against real javac output.
 *
 * `src/test/resources/javac21` holds what javac 21 produced for the `.java.txt`
 * sources sitting next to them. EnumSample covers enumSwitch plus typeSwitch with
 * type labels and a String constant label. RecordSample covers record
 * deconstruction (JEP 440), nested and generic patterns, guards, and an
 * exhaustive switch over a sealed interface with no default. RecordOpsSample
 * covers the generated equals/hashCode/toString over every component type.
 *
 * Expected values come from each sample's `.baseline.txt`, which is what it
 * printed running on an actual JDK 21 with the bootstraps linked by the JDK. We
 * downgrade the same classes and demand identical output, so the generated code
 * gets checked against real bootstrap behaviour instead of my reading of the
 * spec.
 *
 * Both targets are exercised. 17 keeps records and sealed types intact and only
 * lowers the switches; 11 additionally strips both, which is the whole point of
 * running the same samples twice.
 *
 * Everything here except the `.java.txt` files comes from the
 * `regenerateJavac21Fixtures` task.
 */
class Javac21DowngradeTest {

    private companion object {
        val SAMPLES = listOf("EnumSample", "RecordSample", "RecordOpsSample")

        /** Which samples actually contain a pattern switch. */
        val SWITCH_SAMPLES = listOf("EnumSample", "RecordSample")

        /** Which samples actually contain a record. */
        val RECORD_SAMPLES = listOf("EnumSample", "RecordSample", "RecordOpsSample")

        /** 17 lowers switches only; 11 also lowers records and sealed types. */
        val TARGETS = listOf(17, 11)
    }

    @Test
    fun downgradedSamplesBehaveLikeJava21() {
        forEachSampleAndTarget { sample, target, downgraded ->
            val loaded = Fixtures.MapClassLoader(downgraded).loadClass(sample)
            val result = loaded.getMethod("runAll").invoke(null) as String

            assertEquals(Fixtures.baseline(sample), result, "$sample at Java $target")
        }
    }

    @Test
    fun everySwitchBootstrapCallSiteIsRewritten() {
        SWITCH_SAMPLES.forEach { sample ->
            val before = Fixtures.switchCallSites(Fixtures.readClass(sample))
            // Not pinned to an exact count: javac desugars record patterns into a
            // different number of call sites depending on the JDK build that
            // produced the fixtures (see javac21/toolchain.txt).
            assertTrue(before > 0, "$sample no longer covers any SwitchBootstraps call site")

            TARGETS.forEach { target ->
                val after = Fixtures.switchCallSites(Fixtures.downgrade(sample, target).getValue(sample))
                assertEquals(0, after, "$sample at Java $target: SwitchBootstraps call sites survived")
            }
        }
    }

    @Test
    fun everyObjectMethodsCallSiteIsRewrittenBelow16() {
        RECORD_SAMPLES.forEach { sample ->
            val before = Fixtures.classNames(sample).sumOf {
                Fixtures.objectMethodsCallSites(Fixtures.readClass(it))
            }
            assertTrue(before > 0, "$sample no longer covers any ObjectMethods call site")

            Fixtures.downgrade(sample, targetJava = 11).forEach { (name, bytes) ->
                assertEquals(0, Fixtures.objectMethodsCallSites(bytes), "$name: ObjectMethods call site survived")
            }
        }
    }

    @Test
    fun recordsAndSealedTypesSurviveAJava17Target() {
        // Both are expressible at 17, so the downgrade must leave them alone.
        val shapes = Fixtures.downgrade("RecordSample", targetJava = 17)

        val point = Fixtures.shapeOf(shapes.getValue("RecordSample\$Point"))
        assertTrue(point.isRecord, "Point stopped being a record at a Java 17 target")
        assertEquals("java/lang/Record", point.superName)
        assertEquals(listOf("x", "y"), point.recordComponents)

        val shape = Fixtures.shapeOf(shapes.getValue("RecordSample\$Shape"))
        assertEquals(
            listOf("RecordSample\$Circle", "RecordSample\$Rect", "RecordSample\$Group"),
            shape.permittedSubclasses
        )
    }

    @Test
    fun recordsAndSealedTypesAreStrippedForAJava11Target() {
        Fixtures.downgrade("RecordSample", targetJava = 11).forEach { (name, bytes) ->
            val shape = Fixtures.shapeOf(bytes)

            assertFalse(shape.isRecord, "$name is still a record")
            assertEquals(emptyList(), shape.recordComponents, "$name kept its record components")
            assertEquals(emptyList(), shape.permittedSubclasses, "$name is still sealed")
            assertTrue(
                shape.superName != "java/lang/Record",
                "$name still extends java/lang/Record"
            )
        }
    }

    @Test
    fun everyClassIsRewrittenToTheTarget() {
        forEachSampleAndTarget { _, target, downgraded ->
            downgraded.forEach { (name, bytes) ->
                assertEquals(target, ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(bytes)), name)
            }
        }
    }

    private fun forEachSampleAndTarget(check: (String, Int, Map<String, ByteArray>) -> Unit) {
        SAMPLES.forEach { sample ->
            TARGETS.forEach { target ->
                check(sample, target, Fixtures.downgrade(sample, target))
            }
        }
    }
}
