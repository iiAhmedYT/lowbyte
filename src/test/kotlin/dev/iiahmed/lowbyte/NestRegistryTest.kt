package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.nest.BridgeKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the scan decides, before any bytecode is rewritten.
 *
 * The registry is the only part of Lowbyte that reasons about more than one
 * class at a time, and everything the nestmate transform does follows from it,
 * so the shape of what it produces is pinned here.
 */
class NestRegistryTest {

    private companion object {
        const val OUTER = "NestSample"
        const val SECRET = "NestSample\$Secret"
        const val OUTER_MARKER = "NestSample\$lowbyte\$Nest"
        const val SECRET_MARKER = "NestSample\$Secret\$lowbyte\$Nest"
    }

    private val nests = Fixtures.nestsOf("NestSample")

    @Test
    fun instanceMembersTakeTheirOwnerAsTheFirstArgument() {
        assertEquals("(L$OUTER;)I", descriptorOf(BridgeKind.FIELD_GET, OUTER, "field", "I"))
        assertEquals("(L$OUTER;I)V", descriptorOf(BridgeKind.FIELD_SET, OUTER, "field", "I"))
        assertEquals("(L$OUTER;I)I", descriptorOf(BridgeKind.METHOD, OUTER, "method", "(I)I"))
    }

    @Test
    fun staticMembersKeepTheirOwnSignature() {
        assertEquals("()I", descriptorOf(BridgeKind.FIELD_GET, OUTER, "staticField", "I"))
        assertEquals("(I)V", descriptorOf(BridgeKind.FIELD_SET, OUTER, "staticField", "I"))
        assertEquals("(I)I", descriptorOf(BridgeKind.METHOD, OUTER, "staticMethod", "(I)I"))
    }

    @Test
    fun constructorsGainAMarkerArgument() {
        val bridge = assertNotNull(nests.bridge(BridgeKind.CONSTRUCTOR, OUTER, "<init>", "(I)V"))

        assertEquals("<init>", bridge.name)
        assertEquals("(IL$OUTER_MARKER;)V", bridge.descriptor)
        assertEquals(OUTER_MARKER, bridge.markerClass)
    }

    @Test
    fun eachOwnerGetsItsOwnMarker() {
        assertEquals(listOf(SECRET_MARKER, OUTER_MARKER).sorted(), nests.markerClasses.sorted())
    }

    @Test
    fun readsAndWritesGetSeparateBridges() {
        val read = assertNotNull(nests.bridge(BridgeKind.FIELD_GET, SECRET, "hidden", "I"))
        val write = assertNotNull(nests.bridge(BridgeKind.FIELD_SET, SECRET, "hidden", "I"))

        assertTrue(read.name != write.name, "a read and a write cannot share one accessor")
    }

    @Test
    fun bothDirectionsAcrossTheNestAreCovered() {
        // Outer reaches into the nested class and the nested class reaches back.
        assertEquals(setOf(OUTER, SECRET), nests.bridgedOwners)
    }

    @Test
    fun untouchedMembersGetNoBridge() {
        // runAll is public, and nothing reaches it from outside its own class
        // as a private member would.
        assertNull(nests.bridge(BridgeKind.METHOD, OUTER, "runAll", "()Ljava/lang/String;"))
        // outerReadsInner is private, but only NestSample itself calls it.
        assertNull(nests.bridge(BridgeKind.METHOD, OUTER, "outerReadsInner", "()Ljava/lang/String;"))
    }

    @Test
    fun samplesThatStayInsideTheirOwnClassNeedNothing() {
        // Records read their own fields and nothing else's, so nest-stripping
        // these is purely an attribute removal.
        listOf("RecordOpsSample", "RecordSample", "EnumSample").forEach {
            assertTrue(Fixtures.nestsOf(it).isEmpty, "$it should need no bridges")
        }
    }

    private fun descriptorOf(kind: BridgeKind, owner: String, name: String, descriptor: String): String =
        assertNotNull(
            nests.bridge(kind, owner, name, descriptor),
            "no bridge for $kind $owner.$name$descriptor"
        ).descriptor
}
