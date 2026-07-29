package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
 * NestSample covers every way one class reaches a private member of another in
 * its nest. EnumDescSample covers qualified enum constants as switch labels,
 * which reach the bootstrap as CONSTANT_Dynamic rather than as strings.
 *
 * Expected values come from each sample's `.baseline.txt`, which is what it
 * printed running on an actual JDK 21 with the bootstraps linked by the JDK. We
 * downgrade the same classes and demand identical output, so the generated code
 * gets checked against real bootstrap behaviour instead of my reading of the
 * spec.
 *
 * Every target is exercised. 17 keeps records and sealed types intact and only
 * lowers the switches, 11 additionally strips both, and 9 also unpicks the
 * nests, which is the whole point of running the same samples three times.
 *
 * Everything here except the `.java.txt` files comes from the
 * `regenerateJavac21Fixtures` task.
 */
class Javac21DowngradeTest {

    private companion object {
        val SAMPLES = listOf("EnumSample", "RecordSample", "RecordOpsSample", "NestSample", "EnumDescSample")

        /** Which samples actually contain a pattern switch. */
        val SWITCH_SAMPLES = listOf("EnumSample", "RecordSample", "EnumDescSample")

        /** Which samples actually contain a record. */
        val RECORD_SAMPLES = listOf("EnumSample", "RecordSample", "RecordOpsSample")

        /**
         * 17 lowers switches only, 11 also lowers records and sealed types, and
         * 9 additionally unpicks the nests.
         */
        val TARGETS = listOf(17, 11, 9)
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
    fun nestAttributesSurviveAJava11Target() {
        // Nestmates are expressible at 11, so nothing should move.
        val shapes = Fixtures.downgrade("NestSample", targetJava = 11)

        // Sorted, since the order javac lists members in is its business.
        assertEquals(
            listOf("NestSample\$Inner", "NestSample\$Inner\$Deeper", "NestSample\$Secret"),
            Fixtures.shapeOf(shapes.getValue("NestSample")).nestMembers.sorted()
        )
        assertEquals("NestSample", Fixtures.shapeOf(shapes.getValue("NestSample\$Inner")).nestHost)
    }

    @Test
    fun nestsAreUnpickedForAJava9Target() {
        val shapes = Fixtures.downgrade("NestSample", targetJava = 9)

        shapes.forEach { (name, bytes) ->
            val shape = Fixtures.shapeOf(bytes)
            assertNull(shape.nestHost, "$name still has a NestHost")
            assertEquals(emptyList(), shape.nestMembers, "$name still has NestMembers")
        }

        // The bridges have to actually be there, on the classes that own the
        // members, or the rewritten call sites would not link.
        val outer = Fixtures.shapeOf(shapes.getValue("NestSample")).methods
        assertTrue(
            outer.any { it.startsWith("lowbyte\$access\$") },
            "NestSample gained no accessors: $outer"
        )
        assertTrue(
            outer.any { it.startsWith("<init>") && it.contains("lowbyte\$Nest") },
            "NestSample gained no bridged constructor: $outer"
        )
    }

    @Test
    fun markerClassesAreEmittedForBridgedConstructors() {
        val shapes = Fixtures.downgrade("NestSample", targetJava = 9)

        val markers = shapes.keys.filter { it.endsWith("lowbyte\$Nest") }
        assertEquals(
            listOf("NestSample\$Secret\$lowbyte\$Nest", "NestSample\$lowbyte\$Nest").sorted(),
            markers.sorted()
        )
        markers.forEach {
            assertEquals(emptyList(), Fixtures.shapeOf(shapes.getValue(it)).methods, "$it should be empty")
        }
    }

    @Test
    fun qualifiedEnumLabelsAreLowered() {
        // `case Color.RED` reaches the bootstrap as a CONSTANT_Dynamic EnumDesc,
        // not as a string, so it is the one label kind that needs a constant read
        // back out of the pool rather than taken at face value.
        val before = Fixtures.constantDynamicCount(Fixtures.readClass("EnumDescSample"))
        assertTrue(before > 0, "EnumDescSample no longer covers any condy label")

        TARGETS.forEach { target ->
            val downgraded = Fixtures.downgrade("EnumDescSample", target).getValue("EnumDescSample")
            assertEquals(
                0, Fixtures.constantDynamicCount(downgraded),
                "EnumDescSample at Java $target still carries a CONSTANT_Dynamic"
            )
        }
    }

    @Test
    fun noConstantDynamicSurvivesAnywhere() {
        // Below class file 55 a leftover condy is a ClassFormatError even if
        // nothing refers to it, so the constant pool has to come out clean.
        forEachSampleAndTarget { sample, target, downgraded ->
            downgraded.forEach { (name, bytes) ->
                assertEquals(
                    0, Fixtures.constantDynamicCount(bytes),
                    "$name from $sample at Java $target still carries a CONSTANT_Dynamic"
                )
            }
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
