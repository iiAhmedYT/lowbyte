package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.RuntimeRewrite

/**
 * `Set.of`, on the injected utility.
 *
 * The same twelve overloads as [ListOfRewrite], and one thing more: a repeated
 * element is refused with an `IllegalArgumentException`.
 */
object SetOfRewrite : RuntimeRewrite("setOf") {
    override val name = "Set.of"
}

/**
 * `Set.copyOf`, which is not `Set.of` twice over.
 *
 * `Set.of` refuses a repeated element. `copyOf` keeps one of them and says so,
 * so there is deliberately no duplicate check on this path.
 */
object SetCopyOfRewrite : RuntimeRewrite("setCopyOf") {
    override val name = "Set.copyOf"
}
