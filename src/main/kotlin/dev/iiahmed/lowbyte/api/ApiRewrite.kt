package dev.iiahmed.lowbyte.api

import org.objectweb.asm.MethodVisitor

/**
 * One JDK call that can be replaced with something an older release already had.
 *
 * A rewrite is only worth having when the replacement keeps the observable
 * contract. `List.of` refuses nulls, refuses mutation and keeps its order, so
 * something doing exactly that is a fair substitute. Where the nearest older
 * equivalent differs on nulls, duplicates, mutability, ordering or exceptions,
 * there is no rewrite and the call is reported instead.
 *
 * Two shapes, and the rewrite picks which by what it extends:
 *
 *  * [InlineRewrite] writes a small method into the class that made the call.
 *    Right when the replacement is a few instructions.
 *  * [RuntimeRewrite] points the call at [LowbyteApi][RuntimeApi], a class
 *    written as ordinary Java and copied into the jar. Right when the
 *    replacement wants a loop, a decoder or a helper type, none of which are
 *    worth hand-emitting.
 *
 * New implementations go in [ApiRewrites.ALL].
 */
interface ApiRewrite {

    /** Shown in warnings, and the reason this rewrite exists. */
    val name: String

    /**
     * The release the API arrived in. Below this target, the call is replaced.
     *
     * Deciding on the release alone means no `ct.sym` is needed, so this keeps
     * working on a JDK that cannot supply one.
     */
    val introducedIn: Int

    /**
     * Whether this rewrite handles the call.
     *
     * Match on the descriptor as well as the name. An overload nobody has looked
     * at is better reported than replaced as though it were one that has been.
     */
    fun matches(owner: String, name: String, descriptor: String): Boolean

    /** Replaces the call. The two moves available are on [ApiCallSite]. */
    fun apply(site: ApiCallSite)
}

/**
 * A rewrite that writes its replacement into the calling class.
 *
 * The generated method takes the call's own operands, and for an instance call
 * the receiver arrives as the first parameter, so arguments always begin at
 * slot 0.
 */
abstract class InlineRewrite : ApiRewrite {

    final override fun apply(site: ApiCallSite) = site.rebuildInline(this)

    /**
     * Writes the body.
     *
     * `visitCode`, `visitMaxs` and `visitEnd` are the caller's to make.
     */
    abstract fun write(mv: MethodVisitor, descriptor: String)
}

/**
 * A rewrite that forwards to a method on the injected utility.
 *
 * Everything about the call it replaces, which method and which descriptor and
 * which release, is read off the utility's own `@LowbyteInfo` annotations, so the
 * Java source is the single description and there is nothing here to fall out
 * of step with it. Naming the utility method is the whole declaration.
 */
abstract class RuntimeRewrite(private val utilityMethod: String) : ApiRewrite {

    private val replacements: List<RuntimeReplacement>
        get() = RuntimeApi.replacements.filter { it.method == utilityMethod }

    /** The lowest of the overloads', which for one API is all the same number. */
    override val introducedIn: Int
        get() = replacements.minOfOrNull { it.introducedIn } ?: Int.MAX_VALUE

    override fun matches(owner: String, name: String, descriptor: String) =
        replacements.any { it.matches(owner, name, descriptor) }

    final override fun apply(site: ApiCallSite) {
        val replacement = requireNotNull(replacementFor(site.owner, site.name, site.descriptor))
        site.forwardToRuntime(replacement)
    }

    /** Which overload a call lands on, so the trimmer keeps exactly that one. */
    fun replacementFor(owner: String, name: String, descriptor: String): RuntimeReplacement? =
        replacements.firstOrNull { it.matches(owner, name, descriptor) }
}

/**
 * The call being replaced, and the two things a rewrite may do to it.
 *
 * Handed to [ApiRewrite.apply] so the rewrite decides its own fate rather than
 * the transformer deciding for it by kind.
 */
class ApiCallSite(
    val owner: String,
    val name: String,
    val descriptor: String,
    internal val rebuildInline: (InlineRewrite) -> Unit,
    internal val forwardToRuntime: (RuntimeReplacement) -> Unit
)
