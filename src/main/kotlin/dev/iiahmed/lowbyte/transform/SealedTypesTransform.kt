package dev.iiahmed.lowbyte.transform

import dev.iiahmed.lowbyte.downgrade.DowngradeContext
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/** Registry entry for [SealedTypesTransformer]. */
object SealedTypesTransform : FeatureTransform {

    override val name = "sealed types"

    override val introducedIn = 17

    override fun wrap(
        next: ClassVisitor,
        context: DowngradeContext,
        onUnsupported: (String) -> Unit
    ): ClassVisitor = SealedTypesTransformer(next)
}

/**
 * Drops the `PermittedSubclasses` attribute, which is the whole of sealedness in
 * a class file.
 *
 * `sealed` has no access flag. javac checks the `permits` clause at compile time
 * and the JVM re-checks it at link time against this attribute, so removing it
 * leaves a class that is simply not sealed. Every `final` and `non-sealed`
 * marker on the subclasses is already either a plain flag or nothing at all, so
 * nothing else has to change.
 *
 * The one thing lost is the link-time check: a subclass compiled later against
 * the downgraded jar will no longer be rejected. Source still can't extend the
 * type without recompiling against the original, so this only matters to someone
 * deliberately working around it.
 */
class SealedTypesTransformer(
    classVisitor: ClassVisitor
) : ClassVisitor(Opcodes.ASM9, classVisitor) {

    override fun visitPermittedSubclass(permittedSubclass: String?) {
        // Not forwarded: without a single visit the writer emits no attribute.
    }
}
