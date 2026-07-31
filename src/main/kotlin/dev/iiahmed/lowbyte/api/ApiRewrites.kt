package dev.iiahmed.lowbyte.api

import dev.iiahmed.lowbyte.api.rewrite.ListCopyOfRewrite
import dev.iiahmed.lowbyte.api.rewrite.ListOfRewrite
import dev.iiahmed.lowbyte.api.rewrite.MapCopyOfRewrite
import dev.iiahmed.lowbyte.api.rewrite.MapEntryRewrite
import dev.iiahmed.lowbyte.api.rewrite.MapOfEntriesRewrite
import dev.iiahmed.lowbyte.api.rewrite.MapOfRewrite
import dev.iiahmed.lowbyte.api.rewrite.OptionalIsEmptyRewrite
import dev.iiahmed.lowbyte.api.rewrite.OptionalOrElseThrowRewrite
import dev.iiahmed.lowbyte.api.rewrite.RequireNonNullElseRewrite
import dev.iiahmed.lowbyte.api.rewrite.SetCopyOfRewrite
import dev.iiahmed.lowbyte.api.rewrite.SetOfRewrite
import dev.iiahmed.lowbyte.api.rewrite.StringRepeatRewrite

/**
 * Every JDK call Lowbyte knows how to rebuild. Adding one is a class and a line.
 *
 * Deliberately short. A rewrite earns its place by keeping the contract of what
 * it replaces, and most of the three thousand members added between Java 8 and
 * 21 cannot be rebuilt that faithfully, so they are reported instead.
 */
object ApiRewrites {

    val ALL: List<ApiRewrite> = listOf(
        ListOfRewrite,
        SetOfRewrite,
        MapOfRewrite,
        MapOfEntriesRewrite,
        MapEntryRewrite,
        ListCopyOfRewrite,
        SetCopyOfRewrite,
        MapCopyOfRewrite,
        OptionalIsEmptyRewrite,
        OptionalOrElseThrowRewrite,
        StringRepeatRewrite,
        RequireNonNullElseRewrite
    )

    /** The rewrite for a call, or null when there is none. */
    fun forCall(owner: String, name: String, descriptor: String): ApiRewrite? =
        ALL.firstOrNull { it.matches(owner, name, descriptor) }
}
