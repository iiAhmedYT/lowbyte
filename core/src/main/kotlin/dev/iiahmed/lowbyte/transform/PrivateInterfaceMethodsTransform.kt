package dev.iiahmed.lowbyte.transform

import dev.iiahmed.lowbyte.downgrade.DowngradeContext
import dev.iiahmed.lowbyte.nest.NestRegistry
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** Registry entry for [PrivateInterfaceMethodsTransformer]. */
object PrivateInterfaceMethodsTransform : FeatureTransform {

    override val name = "private interface methods"

    override val introducedIn = 9

    override fun wrap(
        next: ClassVisitor,
        context: DowngradeContext,
        onUnsupported: (String) -> Unit
    ): ClassVisitor = PrivateInterfaceMethodsTransformer(next, context.nests)
}

/**
 * Calls a private interface method with `invokespecial` instead of
 * `invokeinterface`.
 *
 * The declaration itself needs nothing. `ACC_PRIVATE` on an interface method has
 * been legal since class file 52, which is why javac 8 could already put a
 * lambda body in an interface, and a Java 8 JVM loads such a class happily.
 *
 * What it will not accept is the call. javac emits `invokeinterface`, and Java 8
 * rejects that for a private method:
 *
 * ```
 * java.lang.IncompatibleClassChangeError: private interface method requires
 * invokespecial, not invokeinterface
 * ```
 *
 * A private method is not virtual, so `invokespecial` is the right instruction
 * and the dispatch is identical. Only the opcode moves.
 *
 * Whether the method is private is a question a visitor cannot answer about the
 * class it is walking, since a call site may come before the declaration it
 * names, so the answer comes from the scan that has already read the whole jar.
 */
class PrivateInterfaceMethodsTransformer(
    classVisitor: ClassVisitor,
    private val nests: NestRegistry
) : ClassVisitor(Opcodes.ASM9, classVisitor) {

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        return CallSiteVisitor(mv)
    }

    private inner class CallSiteVisitor(mv: MethodVisitor) : MethodVisitor(Opcodes.ASM9, mv) {

        override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean
        ) {
            // The isInterface flag on the call site is what says the owner is an
            // interface, which matters because the owner may be another class
            // entirely and its access flags are not ours to read.
            if (opcode == Opcodes.INVOKEINTERFACE &&
                isInterface &&
                owner != null &&
                nests.isPrivateMethod(owner, name.orEmpty(), descriptor.orEmpty())
            ) {
                super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, name, descriptor, true)
                return
            }

            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }

        /**
         * A lambda declared in an interface has a private method for its body,
         * and the call site names it with a handle rather than an instruction.
         *
         * The handle carries the same opcode, so it needs the same correction.
         * Missing this leaves a `BootstrapMethodError` wrapping the very
         * `IncompatibleClassChangeError` the instruction rewrite exists to
         * prevent, thrown when the call site links rather than when the class
         * loads.
         */
        override fun visitInvokeDynamicInsn(
            name: String?,
            descriptor: String?,
            bootstrapMethodHandle: Handle?,
            vararg bootstrapMethodArguments: Any?
        ) {
            val rewritten = bootstrapMethodArguments.map { argument ->
                if (argument is Handle) rewriteHandle(argument) else argument
            }
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *rewritten.toTypedArray())
        }

        private fun rewriteHandle(handle: Handle): Handle {
            if (handle.tag != Opcodes.H_INVOKEINTERFACE || !handle.isInterface) return handle
            if (!nests.isPrivateMethod(handle.owner, handle.name, handle.desc)) return handle

            return Handle(Opcodes.H_INVOKESPECIAL, handle.owner, handle.name, handle.desc, true)
        }
    }
}
