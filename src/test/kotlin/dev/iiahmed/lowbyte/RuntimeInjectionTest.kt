package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.api.RuntimeApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The injected utility, end to end.
 *
 * `String.isBlank` and `strip` are the point of it: they go by
 * `Character.isWhitespace` where `trim` goes by code point value, so no inline
 * rebuild expresses them and the baseline here is what real Java 21 printed.
 */
class RuntimeInjectionTest {

    private companion object {
        const val SAMPLE = "RuntimeApiSample"

        /** The map is keyed by binary name; only the injected class has a package. */
        val PACKAGE = RuntimeApi.DEFAULT_PACKAGE.replace('/', '.')
    }

    @Test
    fun theUtilityCarriesTheBehaviour() {
        val downgraded = Fixtures.downgrade(SAMPLE, targetJava = 8, api = true)
        val loaded = Fixtures.MapClassLoader(downgraded).loadClass(SAMPLE)

        assertEquals(Fixtures.baseline(SAMPLE), loaded.getMethod("runAll").invoke(null) as String)
    }

    @Test
    fun theCallsPointAtTheInjectedClass() {
        val downgraded = Fixtures.downgrade(SAMPLE, targetJava = 8, api = true)
        val injected = downgraded.keys.single { it.startsWith(PACKAGE) }
        val calls = Fixtures.methodCallTargets(downgraded.getValue(SAMPLE))

        assertTrue(calls.none { it.startsWith("java/lang/String.isBlank(") }, "isBlank survived")
        assertTrue(calls.none { it.startsWith("java/lang/String.strip(") }, "strip survived")
        assertTrue(
            calls.any { it == "${injected.replace('.', '/')}.isBlank(Ljava/lang/String;)Z" },
            calls.toString()
        )
    }

    @Test
    fun nothingIsInjectedWhenNothingNeedsIt() {
        // A target that already has the APIs, and the flag off, both mean no class.
        listOf(
            Fixtures.downgrade(SAMPLE, targetJava = 17, api = true),
            Fixtures.downgrade(SAMPLE, targetJava = 8, api = false)
        ).forEach { downgraded ->
            assertTrue(
                downgraded.keys.none { it.startsWith(PACKAGE) },
                "an unused utility was injected: ${downgraded.keys}"
            )
        }
    }

    @Test
    fun onlyTheUsedMethodsAreInjected() {
        val downgraded = Fixtures.downgrade(SAMPLE, targetJava = 8, api = true)
        val injected = downgraded.keys.single { it.startsWith(PACKAGE) }
        val methods = Fixtures.shapeOf(downgraded.getValue(injected)).methods

        // What the sample calls, plus the private helpers those reach. The
        // utility holds more than this. repeat, requireNonNullElse and eleven
        // mapOf overloads and none of it should be here.
        assertEquals(
            listOf(
                "isBlank(Ljava/lang/String;)Z",
                "strip(Ljava/lang/String;)Ljava/lang/String;",
                "stripLeading(Ljava/lang/String;)Ljava/lang/String;",
                "stripTrailing(Ljava/lang/String;)Ljava/lang/String;",
                "lines(Ljava/lang/String;)Ljava/util/stream/Stream;",
                "indent(Ljava/lang/String;I)Ljava/lang/String;",
                "stripIndent(Ljava/lang/String;)Ljava/lang/String;",
                "transform(Ljava/lang/String;Ljava/util/function/Function;)Ljava/lang/Object;",
                "formatted(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;",
                "translateEscapes(Ljava/lang/String;)Ljava/lang/String;",
                "firstNonWhitespace(Ljava/lang/String;)I",
                "lastNonWhitespace(Ljava/lang/String;)I",
                "splitLines(Ljava/lang/String;)Ljava/util/List;",
                "commonIndent(Ljava/util/List;)I"
            ).sorted(),
            methods.sorted()
        )
    }
}
