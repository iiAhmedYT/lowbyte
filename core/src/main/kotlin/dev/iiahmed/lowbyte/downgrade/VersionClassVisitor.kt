package dev.iiahmed.lowbyte.downgrade

import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/**
 * Rewrites the class file header to the target version.
 *
 * The one step that always runs. Anything needing real rewriting is handled by
 * the [dev.iiahmed.lowbyte.transform.FeatureTransform]s stacked above, which is
 * why this still gets handed the original version.
 */
class VersionClassVisitor(
    cv: ClassVisitor,
    private val targetMajor: Int,
    private val onUnsupported: (String) -> Unit
) : ClassVisitor(Opcodes.ASM9, cv) {

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        val sourceMajor = ClassFileVersion.majorOf(version)

        if (sourceMajor < targetMajor) {
            // Already older than we're aiming for, leave it be.
            super.visit(version, access, name, signature, superName, interfaces)
            return
        }

        if (ClassFileVersion.isPreview(version)) {
            // Only loads on the JDK that compiled it, so dropping the version
            // here would just produce a class that never verifies.
            onUnsupported("preview features (Java ${ClassFileVersion.toJavaVersion(sourceMajor)})")
        }

        super.visit(targetMajor, access, name, signature, superName, interfaces)
    }
}
