package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.api.RuntimeApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `Arrays` comparison methods Java 9 added, fifty-nine overloads of four.
 *
 * Worth a test of their own rather than a few lines in another, because of how
 * they fail. A rewrite matches on the exact descriptor, so one wrong character
 * leaves the call pointing at a method Java 8 does not have. Nothing says so at
 * downgrade time and nothing says so on the JVM these tests run on, which
 * already has the method. It fails on somebody else's Java 8.
 *
 * So the coverage check below is the important one: it holds the fixture to the
 * utility, and an overload nobody exercised is a failure rather than a gap.
 */
class ArraysApiTest {

    private companion object {
        const val SAMPLE = "ArraysSample"
        const val ARRAYS = "java/util/Arrays"

        val PACKAGE = RuntimeApi.DEFAULT_PACKAGE.replace('/', '.')

        /** The four names, so an unrelated `Arrays` call is not swept up. */
        val REWRITTEN = listOf("mismatch", "equals", "compare", "compareUnsigned")
    }

    /** Every `java.util.Arrays` call the sample makes, across all of its classes. */
    private fun sampleCalls(): Set<String> =
        Fixtures.classNames(SAMPLE)
            .flatMap { Fixtures.methodCallTargets(Fixtures.readClass(it)) }
            .filter { it.startsWith("$ARRAYS.") }
            .toSet()

    /** Every `Arrays` overload the utility claims to replace. */
    private fun claimed(): Set<String> =
        RuntimeApi.replacements
            .filter { it.owner == ARRAYS }
            .mapTo(mutableSetOf()) { "${it.owner}.${it.name}${it.descriptor}" }

    @Test
    fun theUtilityCarriesTheBehaviour() {
        val downgraded = Fixtures.downgrade(SAMPLE, targetJava = 8, api = true)
        val loaded = Fixtures.MapClassLoader(downgraded).loadClass(SAMPLE)

        assertEquals(Fixtures.baseline(SAMPLE), loaded.getMethod("runAll").invoke(null) as String)
    }

    @Test
    fun everyOverloadIsExercisedByTheFixture() {
        // An overload the sample never calls is an overload nothing has checked,
        // and the descriptor is the part most likely to be wrong.
        val untested = claimed() - sampleCalls()

        assertTrue(
            untested.isEmpty(),
            "${untested.size} Arrays overload(s) are replaced but never called by $SAMPLE:\n" +
                untested.sorted().joinToString("\n")
        )
    }

    @Test
    fun theFixtureCallsNothingTheUtilityDoesNotCover() {
        // The other direction. A call the sample makes that no replacement
        // matches is left alone, so it would die on Java 8, and locally it
        // passes because this JVM has the method.
        val uncovered = sampleCalls()
            .filter { call -> REWRITTEN.any { call.startsWith("$ARRAYS.$it(") } }
            .toSet() - claimed()

        assertTrue(
            uncovered.isEmpty(),
            "$SAMPLE calls Arrays methods nothing replaces:\n" + uncovered.sorted().joinToString("\n")
        )
    }

    @Test
    fun theCallsPointAtTheInjectedClass() {
        val downgraded = Fixtures.downgrade(SAMPLE, targetJava = 8, api = true)
        val injected = downgraded.keys.single { it.startsWith(PACKAGE) && '$' !in it }
        val prefix = injected.replace('.', '/')

        val survivors = Fixtures.classNames(SAMPLE)
            .flatMap { Fixtures.methodCallTargets(downgraded.getValue(it)) }
            .filter { call -> REWRITTEN.any { call.startsWith("$ARRAYS.$it(") } }

        assertTrue(survivors.isEmpty(), "these survived the conversion: $survivors")

        val forwarded = Fixtures.classNames(SAMPLE)
            .flatMap { Fixtures.methodCallTargets(downgraded.getValue(it)) }
            .filter { it.startsWith("$prefix.arrays") }

        // Every call has to land somewhere, and they all land here.
        assertEquals(
            claimed().size,
            forwarded.toSet().size,
            "not every overload forwarded to the utility"
        )
    }

    @Test
    fun aTargetThatHasThemIsLeftAlone() {
        // Java 9 has all of these, so asking for the check at 9 changes nothing.
        val downgraded = Fixtures.downgrade(SAMPLE, targetJava = 9, api = true)

        val kept = Fixtures.classNames(SAMPLE)
            .flatMap { Fixtures.methodCallTargets(downgraded.getValue(it)) }
            .filter { it.startsWith("$ARRAYS.mismatch(") }

        assertTrue(kept.isNotEmpty(), "Arrays.mismatch is Java 9 and should have been left alone")
        assertTrue(
            downgraded.keys.none { it.startsWith(PACKAGE) },
            "an unused utility was injected: ${downgraded.keys}"
        )
    }

    @Test
    fun onlyTheOverloadsUsedAreInjected() {
        // The sample uses all fifty-nine, so a jar that uses one must not get
        // the other fifty-eight. Checked with a single call rather than the
        // sample, since the sample cannot show the difference.
        val one = "arraysMismatch([I[I)I"
        val methods = Fixtures.shapeOf(
            RuntimeApi.inject("com/example/Util", setOf(one)).getValue("com/example/Util")
        ).methods

        // The whole-array form, the range form it delegates to, and the shared
        // bounds check. Nothing else.
        assertEquals(
            listOf("arraysMismatch([I[I)I", "arraysMismatch([III[III)I", "arraysRange(III)V").sorted(),
            methods.filter { it.startsWith("arrays") }.sorted(),
            "trimming kept more than the call needed"
        )
    }
}
