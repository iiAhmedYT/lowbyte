package dev.iiahmed.lowbyte.transform

import dev.iiahmed.lowbyte.downgrade.DowngradeContext
import org.objectweb.asm.ClassVisitor

/**
 * Lowers one language feature the target is too old to express.
 *
 * Only applied when the target predates [introducedIn]. Classes that never touch
 * the feature go straight through.
 *
 * New implementations go in [FeatureTransforms.ALL].
 */
interface FeatureTransform {

    /** Shown in warnings and build failures. */
    val name: String

    val introducedIn: Int

    /**
     * Wrap [next] so this transform sees the class first.
     *
     * Call [onUnsupported] for constructs you can't lower, and leave them alone
     * rather than emitting something that won't run. The caller decides whether
     * that's a warning or a hard failure.
     *
     * [context] carries what the class in front of you cannot answer on its own.
     * Most transforms have no use for it.
     */
    fun wrap(next: ClassVisitor, context: DowngradeContext, onUnsupported: (String) -> Unit): ClassVisitor
}
