package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.ApiBytecode.PREDICATE
import dev.iiahmed.lowbyte.api.InlineRewrite
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * `Predicate.not`, which is `negate()` and a null check.
 *
 * That is not an approximation of the lambda it stands for, it is the JDK's own
 * body: `not` returns `target.negate()`, and `negate` has been a default method
 * returning `t -> !test(t)` since Java 8. So the lambda already exists at the
 * target, and nothing here has to build one.
 */
object PredicateNotRewrite : InlineRewrite() {

    override val name = "Predicate.not"

    override val introducedIn = 11

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == PREDICATE && name == "not" && descriptor == "(L$PREDICATE;)L$PREDICATE;"

    override fun write(mv: MethodVisitor, descriptor: String) {
        // The JDK writes an explicit requireNonNull here. It is not written out
        // because it cannot be observed: invoking negate on a null receiver
        // throws the same NullPointerException at the same point, so the check
        // would be three instructions per call site that nothing can tell apart.
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PREDICATE, "negate", "()L$PREDICATE;", true)
        mv.visitInsn(Opcodes.ARETURN)
    }
}
