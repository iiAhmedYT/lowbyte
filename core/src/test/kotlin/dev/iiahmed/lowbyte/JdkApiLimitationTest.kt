package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.downgrade.ClassDowngrader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins down what a bytecode downgrade can't do.
 *
 * We rewrite bytecode, not API usage. A Java 21 class calling `List.reversed()`
 * verifies perfectly well at version 61 and then dies with [NoSuchMethodError]
 * the first time that method runs on a Java 17 JVM. Nothing here can fix that.
 *
 * ApiSample calls two Java 21 APIs and one Java 11 API. We downgrade it, run it
 * on the test JVM, and assert exactly which calls survive, so the limitation
 * stays visible instead of quietly drifting.
 *
 * Test JVM is the Java 17 toolchain from `build.gradle.kts`. If that ever gets
 * raised, [requiresJava17TestJvm] fails loudly rather than letting the
 * assertions below turn into nonsense.
 */
class JdkApiLimitationTest {

    private companion object {
        const val SAMPLE = "ApiSample"

        /**
         * What ApiSample.runAll() gives back once downgraded to 17 and run on a
         * Java 17 JVM. The recorded JDK 21 baseline differs on both Java 21
         * APIs, and [downgradeDoesNotFixApiUsage] asserts that contrast.
         */
        val EXPECTED_ON_JAVA_17 = """
            sequenced=NoSuchMethodError
            virtual=NoSuchMethodError
            older=abab
        """.trimIndent()
    }

    @Test
    fun requiresJava17TestJvm() {
        assertEquals("17", System.getProperty("java.specification.version"))
    }

    @Test
    fun downgradeDoesNotFixApiUsage() {
        // The downgrade itself reports nothing: the class file is fully
        // expressible at Java 17, only the JDK methods it calls are missing.
        val downgraded = ClassDowngrader.downgrade(Fixtures.readClass(SAMPLE), 17) { error("unsupported: $it") }

        val loaded = Fixtures.MapClassLoader(mapOf(SAMPLE to downgraded)).loadClass(SAMPLE)
        val result = loaded.getMethod("runAll").invoke(null) as String

        assertEquals(EXPECTED_ON_JAVA_17, result)
        assertNotEquals(Fixtures.baseline(SAMPLE), result, "Java 21 APIs unexpectedly worked on Java 17")
    }
}
