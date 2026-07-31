package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.RuntimeRewrite

/**
 * `String.isBlank`.
 *
 * Not `trim().isEmpty()`. `trim` cuts everything at or below U+0020 and nothing
 * above it, while this goes by `Character.isWhitespace`, so the two disagree on
 * U+00A0, which is not whitespace, and on U+2028, which is. Getting that right
 * is a walk over code points, so it lives on the utility.
 */
object StringIsBlankRewrite : RuntimeRewrite("isBlank") {
    override val name = "String.isBlank"
}

/** `String.strip`, by `Character.isWhitespace` from both ends, for the same reason. */
object StringStripRewrite : RuntimeRewrite("strip") {
    override val name = "String.strip"
}

/** `String.stripLeading`, the front half of [StringStripRewrite]. */
object StringStripLeadingRewrite : RuntimeRewrite("stripLeading") {
    override val name = "String.stripLeading"
}

/** `String.stripTrailing`, the back half. */
object StringStripTrailingRewrite : RuntimeRewrite("stripTrailing") {
    override val name = "String.stripTrailing"
}

/** `String.repeat`, a counted loop, with a negative count refused as the JDK refuses it. */
object StringRepeatRewrite : RuntimeRewrite("repeat") {
    override val name = "String.repeat"
}

/**
 * `String.lines`.
 *
 * Neither `split` shape is this: `split("\n")` leaves carriage returns behind,
 * and `split("\R")` drops every trailing empty line rather than the single one a
 * final terminator implies.
 */
object StringLinesRewrite : RuntimeRewrite("lines") {
    override val name = "String.lines"
}

/** `String.indent`, which re-terminates every line as well as shifting it. */
object StringIndentRewrite : RuntimeRewrite("indent") {
    override val name = "String.indent"
}

/**
 * `String.stripIndent`.
 *
 * Only ever seen from an explicit call. Text blocks are stripped by javac at
 * compile time and reach the class file as an ordinary constant, so they leave
 * nothing here to rewrite.
 */
object StringStripIndentRewrite : RuntimeRewrite("stripIndent") {
    override val name = "String.stripIndent"
}

/** `String.transform`, the function applied to the receiver. */
object StringTransformRewrite : RuntimeRewrite("transform") {
    override val name = "String.transform"
}

/** `String.formatted`, which is `String.format` with the receiver as the format. */
object StringFormattedRewrite : RuntimeRewrite("formatted") {
    override val name = "String.formatted"
}

/** `String.translateEscapes`, a decoder, and so written as Java rather than emitted. */
object StringTranslateEscapesRewrite : RuntimeRewrite("translateEscapes") {
    override val name = "String.translateEscapes"
}
