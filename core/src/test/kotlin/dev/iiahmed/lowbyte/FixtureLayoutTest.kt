package dev.iiahmed.lowbyte

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The fixture sources and the lists that drive them, held to each other.
 *
 * Which samples run is decided by hand, because the lists carry intent a
 * directory listing cannot: `ApiSample` is meant to fail on an older JVM, and
 * two samples only make sense with the API conversion turned on.
 *
 * Deciding by hand is what makes this worth checking. A sample added without
 * being listed does not fail anything, it silently stops being tested, and a
 * name left behind after a sample is deleted fails only much later and somewhere
 * unrelated.
 */
class FixtureLayoutTest {

    private fun sourceNames(): List<String> {
        val directory = Fixtures.sourceDir
        return directory.listFiles { file: File -> file.name.endsWith(".java.txt") }
            ?.map { it.name.removeSuffix(".java.txt") }
            ?.sorted()
            ?: fail("no fixture sources at ${directory.absolutePath}")
    }

    @Test
    fun everySourceIsListedAndEveryListedNameHasASource() {
        assertEquals(
            sourceNames(),
            Fixtures.ALL_SAMPLES.sorted(),
            "the sources in ${Fixtures.sourceDir} and Fixtures.SAMPLES + API_SAMPLES + EXCLUDED disagree"
        )
    }

    @Test
    fun noSampleIsListedTwice() {
        val duplicates = Fixtures.ALL_SAMPLES.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "listed in more than one of the three lists: $duplicates")
    }

    @Test
    fun everySampleHasBeenGenerated() {
        // Catches a source added without running regenerateJavac21Fixtures, which
        // would otherwise surface as a confusing failure in an unrelated test.
        Fixtures.ALL_SAMPLES.forEach { sample ->
            assertTrue(Fixtures.classNames(sample).isNotEmpty(), "$sample has no classes")
            assertTrue(Fixtures.baseline(sample).isNotEmpty(), "$sample has no baseline")
        }
    }
}
