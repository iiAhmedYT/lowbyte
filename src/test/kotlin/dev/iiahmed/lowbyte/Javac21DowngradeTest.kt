package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.downgrade.ClassDowngrader
import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Differential test against real javac output.
 *
 * `src/test/resources/javac21` holds what javac 21 produced for the `.java.txt`
 * sources sitting next to them. EnumSample covers enumSwitch plus typeSwitch with
 * type labels and a String constant label. RecordSample covers record
 * deconstruction (JEP 440), nested and generic patterns, guards, and an
 * exhaustive switch over a sealed interface with no default.
 *
 * Expected values come from each sample's `.baseline.txt`, which is what it
 * printed running on an actual JDK 21 with the bootstraps linked by the JDK. We
 * downgrade the same classes to 17 and demand identical output, so the generated
 * matchers get checked against real bootstrap behaviour instead of my reading of
 * the spec.
 *
 * Everything here except the `.java.txt` files comes from the
 * `regenerateJavac21Fixtures` task.
 */
class Javac21DowngradeTest {

    private companion object {
        val SAMPLES = listOf("EnumSample", "RecordSample")
    }

    @Test
    fun downgradedSwitchesBehaveLikeJava21() {
        SAMPLES.forEach { sample ->
            val downgraded = Fixtures.downgrade(sample, targetJava = 17)

            val loaded = Fixtures.MapClassLoader(downgraded).loadClass(sample)
            val result = loaded.getMethod("runAll").invoke(null) as String

            assertEquals(Fixtures.baseline(sample), result, sample)
        }
    }

    @Test
    fun everySwitchBootstrapCallSiteIsRewritten() {
        SAMPLES.forEach { sample ->
            val downgraded = Fixtures.downgrade(sample, targetJava = 17)

            val before = Fixtures.switchCallSites(Fixtures.readClass(sample))
            val after = Fixtures.switchCallSites(downgraded.getValue(sample))

            // Not pinned to an exact count: javac desugars record patterns into a
            // different number of call sites depending on the JDK build that
            // produced the fixtures (see javac21/toolchain.txt).
            assertTrue(before > 0, "$sample no longer covers any SwitchBootstraps call site")
            assertEquals(0, after, "$sample: SwitchBootstraps call sites survived the downgrade")
        }
    }

    @Test
    fun everyClassIsRewrittenToTheTarget() {
        SAMPLES.forEach { sample ->
            Fixtures.downgrade(sample, targetJava = 17).forEach { (name, bytes) ->
                assertEquals(17, ClassFileVersion.toJavaVersion(Fixtures.majorVersionOf(bytes)), name)
            }
        }
    }
}
