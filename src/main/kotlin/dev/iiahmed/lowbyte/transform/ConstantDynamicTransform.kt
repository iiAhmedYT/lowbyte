package dev.iiahmed.lowbyte.transform

import dev.iiahmed.lowbyte.downgrade.DowngradeContext
import dev.iiahmed.lowbyte.nest.NestRegistry
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** Registry entry for [ConstantDynamicDetector]. */
object ConstantDynamicTransform : FeatureTransform {

    override val name = "CONSTANT_Dynamic"

    /** Same release as nestmates, which is when the constant pool gained it. */
    override val introducedIn = NestRegistry.INTRODUCED_IN

    override fun wrap(
        next: ClassVisitor,
        context: DowngradeContext,
        onUnsupported: (String) -> Unit
    ): ClassVisitor = ConstantDynamicDetector(next, onUnsupported)
}

/**
 * Reports a `CONSTANT_Dynamic` that nothing above has managed to remove.
 *
 * This one only looks. Below class file version 55 condy is not a legal constant
 * pool entry, so a class still carrying one fails to *load*, with a
 * `ClassFormatError` that names no source position and arrives whenever the
 * class is first touched. Turning that into a build failure is worth the few
 * lines even though nothing here can be lowered.
 *
 * Lowering condy in general is not a rewrite so much as an evaluation: the
 * bootstrap would have to run at class-init time into a static field, which is
 * only sound when it has no side effects and its arguments are themselves
 * representable. `ConstantBootstraps.invoke` of an arbitrary method handle is
 * neither, so guessing is worse than reporting.
 *
 * The one shape Lowbyte does handle is the `Enum$EnumDesc` a qualified enum
 * label compiles to, and [SwitchBootstrapsTransformer] has already dealt with
 * those by the time a class reaches here, since it sits further out in the
 * chain.
 *
 * Only referenced constants need checking. An entry nothing points at cannot
 * reach the output, because [dev.iiahmed.lowbyte.downgrade.ClassDowngrader]
 * builds the constant pool from what is actually emitted rather than inheriting
 * the original.
 */
class ConstantDynamicDetector(
    classVisitor: ClassVisitor,
    private val onUnsupported: (String) -> Unit
) : ClassVisitor(Opcodes.ASM9, classVisitor) {

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        return Detector(mv)
    }

    private inner class Detector(mv: MethodVisitor) : MethodVisitor(Opcodes.ASM9, mv) {

        override fun visitLdcInsn(value: Any?) {
            report(value)
            super.visitLdcInsn(value)
        }

        override fun visitInvokeDynamicInsn(
            name: String?,
            descriptor: String?,
            bootstrapMethodHandle: Handle?,
            vararg bootstrapMethodArguments: Any?
        ) {
            bootstrapMethodArguments.forEach { report(it) }
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        }

        /**
         * Only the outermost constant is named.
         *
         * A condy nested inside another one cannot appear on its own, so
         * reporting both would say the same thing twice.
         */
        private fun report(value: Any?) {
            if (value !is ConstantDynamic) return

            val bootstrap = value.bootstrapMethod
            onUnsupported(
                "a CONSTANT_Dynamic constant `${value.name}:${value.descriptor}` from " +
                    "${bootstrap.owner}.${bootstrap.name}, which has no pre-11 equivalent"
            )
        }
    }
}
