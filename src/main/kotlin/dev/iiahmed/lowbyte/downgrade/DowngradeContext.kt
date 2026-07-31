package dev.iiahmed.lowbyte.downgrade

import dev.iiahmed.lowbyte.api.ApiSettings
import dev.iiahmed.lowbyte.nest.NestRegistry

/**
 * What a transform can only learn by looking at more than one class.
 *
 * Almost everything Lowbyte does is a property of the class in front of it, and
 * those transforms ignore this. The exceptions are nestmates, where a call site
 * names an owner and a member but never the access flags, and the API check,
 * whose answers come from the JDK's own record of what each release had.
 */
class DowngradeContext(
    val nests: NestRegistry,
    /** Null when the API check is switched off, which is the default. */
    val api: ApiSettings? = null,
    /**
     * Where API findings go.
     *
     * Deliberately not the `onUnsupported` the other transforms use. A call into
     * a newer JDK may sit behind a runtime version check, in which case the code
     * is correct and the reference is never reached, so these can only ever be
     * warnings.
     */
    val onApiFinding: (String) -> Unit = {}
) {

    companion object {
        /** For a single class with nothing else around it, as the tests use. */
        val EMPTY = DowngradeContext(NestRegistry.EMPTY)
    }
}
