package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.RuntimeRewrite

/**
 * `List.of`, on the injected utility.
 *
 * Twelve overloads: one per arity up to ten, and the varargs one javac calls
 * past that. Each wants a null check per element, which is a loop, and a loop is
 * worth writing as Java rather than emitting with hand-written frames.
 */
object ListOfRewrite : RuntimeRewrite("listOf") {
    override val name = "List.of"
}

/** `List.copyOf`, a snapshot refusing a null collection and null elements. */
object ListCopyOfRewrite : RuntimeRewrite("listCopyOf") {
    override val name = "List.copyOf"
}
