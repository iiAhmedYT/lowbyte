package dev.iiahmed.lowbyte

import java.io.File
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * Building and reading the jars the jar-level tests work on.
 *
 * Kept apart from [Fixtures], which is about the checked-in javac21 samples.
 * These are about the container those samples get put into, and half the
 * entries the tests care about are not classes at all.
 */
object Jars {

    /** A jar holding exactly [entries], written into [directory]. */
    fun of(directory: File, entries: Map<String, ByteArray>): File {
        val file = File(directory, "in-${entries.keys.hashCode()}.jar")
        JarOutputStream(file.outputStream()).use { jos ->
            entries.forEach { (name, bytes) ->
                jos.putNextEntry(ZipEntry(name))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
        return file
    }

    /** Every entry of a jar, by name. */
    fun read(file: File): Map<String, ByteArray> = JarFile(file).use { jar ->
        jar.entries().asSequence().associate { it.name to jar.getInputStream(it).readAllBytes() }
    }

    /** The classes of a checked-in sample, keyed the way a jar keys them. */
    fun entriesOf(sample: String): Map<String, ByteArray> =
        Fixtures.classNames(sample).associate { "$it.class" to Fixtures.readClass(it) }
}
