package dev.iiahmed.lowbyte

/**
 * What one jar's downgrade did, and what it wants said about it.
 *
 * Nothing here is printed. A core that logged would have to pick a logger, and
 * the three frontends that will use it report in three different ways, so the
 * facts come back and each one words them itself.
 *
 * The three lists are not the same kind of thing and must not be run together:
 *
 *  * [unsupported] is fatal when the caller asked for that. The construct cannot
 *    be expressed at the target at all, so the class would fail on the JVM it
 *    was aimed at.
 *  * [apiFindings] is never fatal. A call into a newer JDK may sit behind a
 *    runtime version check, in which case the reference is real, the code is
 *    correct, and it is never reached.
 *  * [warnings] is about the downgrade rather than the code, and says which half
 *    of the API check was able to run.
 */
class DowngradeResult(

    /** The release this was aimed at. */
    val target: Int,

    /** Classes rewritten. */
    val downgraded: Int,

    /** Entries copied through untouched, resources and excluded classes alike. */
    val copied: Int,

    /**
     * Signature files dropped.
     *
     * Every digest in them covers a class that has just been rewritten, so the
     * output is unsigned rather than wrongly signed.
     */
    val droppedSignatures: Int,

    /** Whether the root module descriptor had to go, which happens below Java 9. */
    val droppedModuleInfo: Boolean,

    /** Where the utility was injected, or null when nothing needed one. */
    val injectedClass: String?,

    /** Which of its methods were kept, as name and descriptor. */
    val injectedMethods: Set<String>,

    /** Constructs the target cannot express, as `class: what`. */
    val unsupported: List<String>,

    /** Calls into APIs the target's JDK does not have, left alone. */
    val apiFindings: List<String>,

    /** Anything that limited the run without stopping it. */
    val warnings: List<String>
) {

    /** Whether a utility class was added to the jar. */
    val injected: Boolean get() = injectedClass != null
}
