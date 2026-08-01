package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.api.ApiRewrites
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Every rewrite is named in APIs.md.
 *
 * That document is the answer to "will my call survive a downgrade", so a
 * rewrite missing from it is worse than undocumented: it reads as *not*
 * rewritten. Asking [ApiRewrites.ALL] rather than reading the source is what
 * makes this hold for a name the rewrite computes rather than spells out.
 */
class ApiDocumentationTest {

    @Test
    fun everyRewriteIsListedInApisMd() {
        val document = File("APIs.md")
        assertTrue(document.isFile, "APIs.md is not where this test expects it: ${document.absolutePath}")
        val text = document.readText().replace("()`", "`")
        val missing = ApiRewrites.ALL.map { it.name }.filterNot { "`$it`" in text }

        assertTrue(missing.isEmpty(), "rewrites missing from APIs.md: $missing")
    }
}
