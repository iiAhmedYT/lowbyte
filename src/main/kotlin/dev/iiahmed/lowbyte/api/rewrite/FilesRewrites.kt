package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.RuntimeRewrite

/**
 * `Files.readString`, both overloads.
 *
 * Not `new String(Files.readAllBytes(p), charset)`: that substitutes U+FFFD for
 * input the charset cannot decode, where this throws `MalformedInputException`.
 * A strict `CharsetDecoder` is the difference, and it needs a method.
 */
object FilesReadStringRewrite : RuntimeRewrite("readString") {
    override val name = "Files.readString"
}

/**
 * `Files.writeString`, both overloads.
 *
 * The same trade going the other way: `getBytes` writes a question mark for
 * anything unmappable, where this throws `UnmappableCharacterException`.
 */
object FilesWriteStringRewrite : RuntimeRewrite("writeString") {
    override val name = "Files.writeString"
}

/** `Files.mismatch`, a block-at-a-time comparison, so a loop. */
object FilesMismatchRewrite : RuntimeRewrite("mismatch") {
    override val name = "Files.mismatch"
}
