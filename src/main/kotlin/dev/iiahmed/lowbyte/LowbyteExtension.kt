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
}
