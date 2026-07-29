package dev.iiahmed.lowbyte.downgrade

import dev.iiahmed.lowbyte.nest.NestRegistry

/**
 * What a transform can only learn by looking at more than one class.
 *
 * Almost everything Lowbyte does is a property of the class in front of it, and
 * those transforms ignore this. Nestmates are the exception: a call site names
 * an owner, a name and a descriptor, but never the access flags, so whether it
 * needs rewriting is a fact about a different class file.
 */
class DowngradeContext(val nests: NestRegistry) {

    companion object {
        /** For a single class with nothing else around it, as the tests use. */
        val EMPTY = DowngradeContext(NestRegistry.EMPTY)
    }
}
