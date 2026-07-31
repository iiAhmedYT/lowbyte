package dev.iiahmed.lowbyte.api

import org.objectweb.asm.MethodVisitor

/**
 * One JDK call that can be rebuilt out of things an older release already had.
 *
 * A rewrite is only worth having when the rebuild keeps the observable
 * contract. `List.of` refuses nulls, refuses mutation and keeps its order, so a
 * generated method doing the same is a fair substitute. `String.isBlank` has no
 * such substitute, since `trim().isEmpty()` disagrees with it about Unicode
 * whitespace, so there is no rewrite for it and the call is reported instead.
 *
 * New implementations go in [ApiRewrites.ALL].
 */
interface ApiRewrite {

    /** Shown in warnings, and the reason this rewrite exists. */
    val name: String

    /**
     * The release the API arrived in.
     *
     * This is what decides whether a call is rebuilt, so it needs nothing from
     * `ct.sym` and keeps working on a JDK that cannot supply one. `ApiIndexTest`
     * checks every one of these against `ct.sym` in both directions, so a wrong
     * number cannot sit here unnoticed.
     */
    val introducedIn: Int

    /**
     * Whether this rewrite handles the call.
     *
     * Match on the descriptor as well as the name. An overload nobody has looked
     * at is better reported than rebuilt as though it were one that has been.
     */
    fun matches(owner: String, name: String, descriptor: String): Boolean

    /**
     * Writes the body of the method that replaces the call.
     *
     * [descriptor] is the generated method's own, which for an instance call has
     * the receiver prepended as a first parameter, so the arguments always begin
     * at slot 0. `visitCode`, `visitMaxs` and `visitEnd` are the caller's to make.
     */
    fun write(mv: MethodVisitor, descriptor: String)
}
