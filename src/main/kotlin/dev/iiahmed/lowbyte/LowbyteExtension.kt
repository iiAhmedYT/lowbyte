package dev.iiahmed.lowbyte

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/** The `lowbyte { }` block. */
abstract class LowbyteExtension @Inject constructor(objects: ObjectFactory) {

    /** Java release to target: 8, 11, 17, and so on. */
    val targetJavaVersion: Property<Int> = objects.property(Int::class.java)

    /** Jar to read, relative to the build dir. Defaults to `libs/<project>.jar`. */
    val jarFilePattern: Property<String> = objects.property(String::class.java)

    /** Internal names (slash-separated) to skip. Matched as prefixes. */
    val excludedClasses: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())

    /** Off turns the same findings into warnings. Either way the whole jar is scanned. */
    val failOnUnsupported: Property<Boolean> = objects.property(Boolean::class.java).convention(true)

    /**
     * Look at the JDK APIs the code calls, not just the bytecode it is made of.
     *
     * Off by default, because it is the one part of Lowbyte that changes calls
     * the target could have linked perfectly well.
     *
     * On, the calls with an exact pre-target equivalent are rewritten, and
     * everything else that the target's JDK does not have is warned about.
     * Those warnings never fail the build however [failOnUnsupported] is set:
     * code that guards a newer call behind a version check still has the call in
     * its bytecode, and that is correct code we must not reject.
     */
    val api: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    /**
     * Where the injected utility class goes, when [api] needs one.
     *
     * Left alone the name is derived from the utility's own bytes and the
     * methods kept from it, so two jars holding the same methods agree on both
     * the name and the contents and shading them together is harmless. Set one
     * to relocate it, and keeping it distinct becomes your problem.
     */
    val runtimeClass: Property<String> = objects.property(String::class.java)
}
