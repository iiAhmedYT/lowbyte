package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.api.ApiIndex
import dev.iiahmed.lowbyte.api.ApiSettings
import dev.iiahmed.lowbyte.downgrade.ClassDowngrader
import dev.iiahmed.lowbyte.downgrade.DowngradeContext
import dev.iiahmed.lowbyte.nest.NestRegistry
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The opt-in API conversion, against what the real JDK 21 factories did.
 *
 * The sample asserts contract rather than implementation. `Set.of` and `Map.of`
 * document their iteration order as unspecified, so the rebuilt collections are
 * only ever compared on the things the contract actually promises.
 */
class ApiConversionTest {

    private companion object {
        const val SAMPLE = "ApiConversionSample"
    }

    private fun downgraded(target: Int, api: Boolean) =
        Fixtures.downgrade(SAMPLE, targetJava = target, api = api)

    private fun requireIndex() =
        assumeTrue(!Fixtures.apiIndexFor(8).isEmpty, "this JDK's ct.sym has no data for Java 8")

    @Test
    fun rebuiltFactoriesKeepTheContract() {
        requireIndex()

        val loaded = Fixtures.MapClassLoader(downgraded(8, api = true)).loadClass(SAMPLE)
        val result = loaded.getMethod("runAll").invoke(null) as String

        assertEquals(Fixtures.baseline(SAMPLE), result)
    }

    @Test
    fun mapOfNowGoesThroughTheInjectedUtility() {
        requireIndex()

        val downgraded = downgraded(8, api = true)
        val injected = downgraded.keys.single { it.startsWith("dev.iiahmed.lowbyte.runtime") }
        val calls = Fixtures.methodCallTargets(downgraded.getValue(SAMPLE))

        assertTrue(
            calls.any { it.startsWith("${injected.replace('.', '/')}.mapOf(") },
            "Map.of should forward to the utility: $calls"
        )
        // Only the arities the sample used, not all eleven.
        val kept = Fixtures.shapeOf(downgraded.getValue(injected)).methods.filter { it.startsWith("mapOf") }
        assertTrue(kept.size in 1..4, "unused Map.of overloads were injected: $kept")
    }

    @Test
    fun theCallsAreActuallyGone() {
        requireIndex()

        val rebuilt = downgraded(8, api = true).getValue(SAMPLE)
        val calls = Fixtures.methodCallTargets(rebuilt)

        // The paren matters: without it "Map.entry" also matches "Map.entrySet",
        // which is a Java 8 method that must stay exactly where it is.
        listOf(
            "java/util/List.of(", "java/util/Set.of(", "java/util/Map.of(", "java/util/Map.entry(",
            "java/util/Map.ofEntries(", "java/util/List.copyOf(", "java/util/Set.copyOf(",
            "java/util/Map.copyOf(", "java/util/Optional.isEmpty(", "java/lang/String.repeat(",
            "java/util/Objects.requireNonNullElse("
        ).forEach { call ->
            assertTrue(calls.none { it.startsWith(call) }, "$call survived the conversion")
        }

        // ...and the Java 8 methods really are still there.
        assertTrue(calls.any { it.startsWith("java/util/Map.entrySet(") }, "entrySet was rewritten")
    }

    @Test
    fun rebuildsDoNotNeedAnIndex() {
        // A rebuild is decided by the release alone, so it still happens on a
        // JDK whose ct.sym cannot answer for the target. Only the reporting
        // half goes quiet.
        val findings = mutableListOf<String>()
        val context = DowngradeContext(
            nests = NestRegistry.EMPTY,
            api = ApiSettings(8, ApiIndex.EMPTY),
            onApiFinding = { findings += it }
        )
        val rebuilt = ClassDowngrader.downgrade(Fixtures.readClass(SAMPLE), 8, context) {}

        assertTrue(
            Fixtures.methodCallTargets(rebuilt).none { it.startsWith("java/util/List.of(") },
            "List.of should still be rebuilt without an index"
        )
        assertEquals(emptyList(), findings, "nothing can be reported without an index")
    }

    @Test
    fun eachRebuildStartsAtItsOwnRelease() {
        // Target 8 is below every one of them, so it cannot tell a wrong release
        // from a right one. Ten sits in the middle and does.
        val calls = Fixtures.methodCallTargets(downgraded(10, api = true).getValue(SAMPLE))

        // Java 10 already has these, so they must be left exactly as they are.
        assertTrue(calls.any { it.startsWith("java/util/List.of(") }, "List.of is Java 9")
        assertTrue(calls.any { it.startsWith("java/util/Map.entry(") }, "Map.entry is Java 9")
        assertTrue(calls.any { it.startsWith("java/util/List.copyOf(") }, "List.copyOf is Java 10")
        assertTrue(calls.any { it.startsWith("java/util/Optional.orElseThrow(") }, "orElseThrow() is Java 10")
        assertTrue(
            calls.any { it.startsWith("java/util/Objects.requireNonNullElse(") },
            "requireNonNullElse is Java 9"
        )

        // Java 10 does not have these yet, so they must be rebuilt.
        assertTrue(calls.none { it.startsWith("java/lang/String.repeat(") }, "String.repeat is Java 11")
        assertTrue(calls.none { it.startsWith("java/util/Optional.isEmpty(") }, "Optional.isEmpty is Java 11")
    }

    @Test
    fun nothingHappensWithTheFlagOff() {
        // Off is the default, and off must mean untouched.
        val calls = Fixtures.methodCallTargets(downgraded(8, api = false).getValue(SAMPLE))

        assertTrue(calls.any { it.startsWith("java/util/List.of(") }, "the flag should be opt-in")
    }

    @Test
    fun aTargetThatHasTheApiIsLeftAlone() {
        requireIndex()
        assumeTrue(!Fixtures.apiIndexFor(17).isEmpty, "no ct.sym data for Java 17")

        // List.of exists on 17, so asking for the check changes nothing.
        val calls = Fixtures.methodCallTargets(downgraded(17, api = true).getValue(SAMPLE))

        assertTrue(calls.any { it.startsWith("java/util/List.of(") }, "Java 17 has List.of already")
    }
}
