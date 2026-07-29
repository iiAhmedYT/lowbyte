package dev.iiahmed.lowbyte.transform

import org.objectweb.asm.ClassVisitor

/** Every transform Lowbyte knows about. Adding one is a single entry in [ALL]. */
object FeatureTransforms {

    val ALL: List<FeatureTransform> = listOf(
        RecordsTransform,
        SealedTypesTransform,
        SwitchBootstrapsTransform
    )

    /** Oldest feature first. */
    fun forTarget(targetJava: Int, transforms: List<FeatureTransform> = ALL): List<FeatureTransform> =
        transforms.filter { targetJava < it.introducedIn }.sortedBy { it.introducedIn }

    /**
     * Stacks the applicable transforms on top of [base], newest feature outermost.
     *
     * Order matters. Whatever a transform emits can only use features as new as
     * the one it lowers, so putting newer transforms on the outside means their
     * output still falls through the older ones underneath. Folding over
     * [forTarget]'s ascending list gets that for free.
     */
    fun chain(
        base: ClassVisitor,
        targetJava: Int,
        onUnsupported: (String) -> Unit,
        transforms: List<FeatureTransform> = ALL
    ): ClassVisitor = forTarget(targetJava, transforms)
        .fold(base) { next, transform -> transform.wrap(next, onUnsupported) }
}
