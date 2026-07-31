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
