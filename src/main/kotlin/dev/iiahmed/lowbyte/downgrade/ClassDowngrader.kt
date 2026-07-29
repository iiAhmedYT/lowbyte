package dev.iiahmed.lowbyte.downgrade

import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import dev.iiahmed.lowbyte.transform.FeatureTransforms
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper

/**
 * Puts one class file through the visitor chain for a given target.
 *
 * Outermost to innermost: [FeatureTransforms] lower the language features,
 * [VersionClassVisitor] rewrites the header, then [JdkTypeSubstitutions] swaps
 * out JDK types the target doesn't have. The substitutions sit at the bottom on
 * purpose, so they catch references the transforms above just emitted.
 */
object ClassDowngrader {

    /** [onUnsupported] gets a description of anything the target can't express. */
    fun downgrade(classBytes: ByteArray, targetJava: Int, onUnsupported: (String) -> Unit): ByteArray {
        val targetMajor = ClassFileVersion.fromJavaVersion(targetJava)

        val reader = ClassReader(classBytes)
        // COMPUTE_MAXS, never COMPUTE_FRAMES: the latter loads the classes it is
        // processing, and those aren't on our classpath.
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)

        var visitor: ClassVisitor = writer

        val substitutions = JdkTypeSubstitutions.forTarget(targetJava)
        if (substitutions.isNotEmpty()) {
            visitor = ClassRemapper(visitor, SimpleRemapper(substitutions))
        }

        visitor = VersionClassVisitor(visitor, targetMajor, onUnsupported)

        // Goes on last so the transforms still see the original version.
        visitor = FeatureTransforms.chain(visitor, targetJava, onUnsupported)

        reader.accept(visitor, 0)

        return writer.toByteArray()
    }
}
