package dev.iiahmed.lowbyte

/**
 * A downgrade that could not be completed.
 *
 * Thrown rather than reported when the output would not be usable: an input that
 * is not there, or a construct the target cannot express with
 * [failOnUnsupported][Lowbyte.Builder.failOnUnsupported] left on. In the second
 * case the output file is deleted before this is thrown, so nothing downstream
 * picks up a jar that would break at runtime.
 *
 * Deliberately not a Gradle exception. The frontends translate it into whatever
 * their build tool expects to see.
 */
class LowbyteException(message: String) : RuntimeException(message)
