package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.RuntimeRewrite

/**
 * `Objects.requireNonNullElse`, on the injected utility.
 *
 * A ternary, but one with a branch in it, and a branch means a stack map frame
 * to write by hand. Java says the same thing in a line and javac works the frame
 * out.
 */
object RequireNonNullElseRewrite : RuntimeRewrite("requireNonNullElse") {
    override val name = "Objects.requireNonNullElse"
}

/**
 * `Objects.checkIndex`.
 *
 * More than a comparison: the message is part of what it promises, and building
 * one is a concatenation rather than an instruction.
 */
object ObjectsCheckIndexRewrite : RuntimeRewrite("checkIndex") {
    override val name = "Objects.checkIndex"
}

/** `Objects.checkFromToIndex`, the same in range form. */
object ObjectsCheckFromToIndexRewrite : RuntimeRewrite("checkFromToIndex") {
    override val name = "Objects.checkFromToIndex"
}

/** `Objects.checkFromIndexSize`, the same again as a start and a count. */
object ObjectsCheckFromIndexSizeRewrite : RuntimeRewrite("checkFromIndexSize") {
    override val name = "Objects.checkFromIndexSize"
}
