package dev.iiahmed.lowbyte.downgrade

/**
 * JDK classes javac references on its own that don't exist on older releases.
 *
 * Lowering the class file version doesn't help here. The bytecode verifies fine
 * and then blows up with NoClassDefFoundError the first time the method runs, so
 * the reference itself has to be swapped for something the target actually has.
 */
object JdkTypeSubstitutions {

    class Substitution(val internalName: String, val replacement: String, val introducedIn: Int)

    val ALL = listOf(
        // javac throws MatchException from the unreachable arm of an exhaustive
        // pattern switch, and to rewrap anything a record accessor throws during
        // deconstruction. IllegalStateException is the closest pre-21 stand-in:
        // also a RuntimeException, and it has the (String, Throwable) constructor
        // javac calls. IncompatibleClassChangeError doesn't.
        Substitution("java/lang/MatchException", "java/lang/IllegalStateException", introducedIn = 21)
    )

    /** Shaped for ASM's SimpleRemapper. */
    fun forTarget(targetJava: Int): Map<String, String> =
        ALL.filter { targetJava < it.introducedIn }
            .associate { it.internalName to it.replacement }
}
