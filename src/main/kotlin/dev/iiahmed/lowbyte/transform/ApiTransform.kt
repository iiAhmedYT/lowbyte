package dev.iiahmed.lowbyte.transform

import dev.iiahmed.lowbyte.api.ApiCallSite
import dev.iiahmed.lowbyte.api.ApiRewrites
import dev.iiahmed.lowbyte.api.ApiSettings
import dev.iiahmed.lowbyte.api.InlineRewrite
import dev.iiahmed.lowbyte.api.RuntimeReplacement
import dev.iiahmed.lowbyte.downgrade.DowngradeContext
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/** Registry entry for [ApiTransformer]. */
object ApiTransform : FeatureTransform {

    override val name = "JDK API usage"

    /**
     * Applies at every target, unlike the rest.
     *
     * An API can be missing at 17 as easily as at 8, `List.reversed` being a
     * Java 21 method, so there is no release below which this stops mattering.
     */
    override val introducedIn = Int.MAX_VALUE

    override fun wrap(
        next: ClassVisitor,
        context: DowngradeContext,
        onUnsupported: (String) -> Unit
    ): ClassVisitor =
        // Switched off, and off is the default, so nothing is wrapped at all.
        context.api?.let { ApiTransformer(next, it, context.onApiFinding) } ?: next
}

/**
 * Rebuilds calls into the JDK the target release did not have, and reports the
 * ones with no rebuild.
 *
 * This is the only part of Lowbyte that touches something the target could have
 * linked. Everything else corrects bytecode that would not verify or would not
 * load; here the class file is fine and the *library* is what is missing, which
 * is why it is opt-in.
 *
 * The rebuilds themselves live in [dev.iiahmed.lowbyte.api.ApiRewrites]. This
 * class only decides which one applies, whether the target needs it, and where
 * the generated method goes.
 */
class ApiTransformer(
    classVisitor: ClassVisitor,
    private val settings: ApiSettings,
    private val onApiFinding: (String) -> Unit
) : ClassVisitor(Opcodes.ASM9, classVisitor) {

    private companion object {
        const val HELPER_PREFIX = "lowbyte\$api\$"

        /**
         * The handle kinds that name a method.
         *
         * The field kinds carry a field descriptor, which no rewrite can match
         * and the index cannot be asked about, so they are left alone. So is
         * `H_NEWINVOKESPECIAL`, a constructor reference, which names `<init>`.
         */
        val INVOKE_TAGS = setOf(
            Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKESTATIC,
            Opcodes.H_INVOKESPECIAL, Opcodes.H_INVOKEINTERFACE
        )
    }

    private class Helper(val name: String, val descriptor: String, val rewrite: InlineRewrite)

    private var className = ""
    private var isInterface = false

    /** Collected as we go, written out in visitEnd. */
    private val helpers = mutableListOf<Helper>()

    /** Reported once each, however many times the call appears. */
    private val reported = mutableSetOf<String>()

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        className = name.orEmpty()
        isInterface = (access and Opcodes.ACC_INTERFACE) != 0
        super.visit(version, access, name, signature, superName, interfaces)
    }

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

    override fun visitEnd() {
        helpers.forEach { generateHelper(it) }
        super.visitEnd()
    }

    private inner class CallSiteVisitor(mv: MethodVisitor) : MethodVisitor(Opcodes.ASM9, mv) {

        override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean
        ) {
            if (owner == null || name == null || descriptor == null) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                return
            }

            // Whether to replace a call is decided by the release alone. The
            // index is not consulted, so this keeps working where it cannot be
            // read.
            val rewrite = ApiRewrites.forCall(owner, name, descriptor)
            if (rewrite == null || settings.targetJava >= rewrite.introducedIn) {
                reportIfMissing(owner, name, descriptor)
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                return
            }

            // What happens next is the rewrite's to decide, not ours.
            rewrite.apply(
                ApiCallSite(
                    owner = owner,
                    name = name,
                    descriptor = descriptor,
                    rebuildInline = { inline -> rebuildInline(opcode, owner, descriptor, inline) },
                    forwardToRuntime = { replacement -> forwardToRuntime(replacement) }
                )
            )
        }

        /**
         * A method reference names its target in a bootstrap argument.
         *
         * `String::isBlank` puts `java/lang/String.isBlank` in a [Handle], not in
         * an instruction, so checking call sites alone never sees it. Left alone
         * it is neither rebuilt nor reported, and fails at the first use with a
         * `BootstrapMethodError` wrapping a `NoSuchMethodError`.
         *
         * Only the arguments are walked. The bootstrap itself is
         * `LambdaMetafactory` or one of its kind, never something with a rebuild,
         * and a constant dynamic is left to [ConstantDynamicTransform] rather
         * than rebuilt from underneath it.
         */
        override fun visitInvokeDynamicInsn(
            name: String?,
            descriptor: String?,
            bootstrapMethodHandle: Handle?,
            vararg bootstrapMethodArguments: Any?
        ) {
            val arguments = Array(bootstrapMethodArguments.size) { index ->
                when (val argument = bootstrapMethodArguments[index]) {
                    is Handle -> rewriteHandle(argument)
                    else -> argument
                }
            }
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *arguments)
        }

        /**
         * The same decision as a call site, reached through a handle.
         *
         * Field handles carry a field descriptor rather than a method one, so
         * they are passed straight through: nothing here could match, and asking
         * the index about them would invent a finding.
         */
        private fun rewriteHandle(handle: Handle): Handle {
            if (handle.tag !in INVOKE_TAGS) return handle

            val rewrite = ApiRewrites.forCall(handle.owner, handle.name, handle.desc)
            if (rewrite == null || settings.targetJava >= rewrite.introducedIn) {
                reportIfMissing(handle.owner, handle.name, handle.desc)
                return handle
            }

            // Dispatch stays the rewrite's, exactly as at a call site. The two
            // moves just produce a handle here instead of an instruction.
            var replaced = handle
            rewrite.apply(
                ApiCallSite(
                    owner = handle.owner,
                    name = handle.name,
                    descriptor = handle.desc,
                    rebuildInline = { inline ->
                        val helper = addHelper(
                            helperDescriptor(handle.tag == Opcodes.H_INVOKESTATIC, handle.owner, handle.desc),
                            inline
                        )
                        replaced = Handle(
                            Opcodes.H_INVOKESTATIC, className, helper.name, helper.descriptor,
                            this@ApiTransformer.isInterface
                        )
                    },
                    forwardToRuntime = { replacement ->
                        // An unbound receiver becomes the first parameter, which
                        // is the shape the utility method already has.
                        replaced = Handle(
                            Opcodes.H_INVOKESTATIC, settings.runtimeClassName,
                            replacement.method, replacement.methodDescriptor, false
                        )
                    }
                )
            )
            return replaced
        }

        /**
         * Points the call at the injected utility.
         *
         * An instance call already has its receiver below the arguments, which
         * is the order the utility method takes them in, so this is a straight
         * swap of opcode and owner with nothing generated at the call site.
         */
        private fun forwardToRuntime(replacement: RuntimeReplacement) {
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC, settings.runtimeClassName,
                replacement.method, replacement.methodDescriptor, false
            )
        }

        /** Points the call at a generated method in this class. */
        private fun rebuildInline(opcode: Int, owner: String, descriptor: String, rewrite: InlineRewrite) {
            val helper = addHelper(
                helperDescriptor(opcode == Opcodes.INVOKESTATIC, owner, descriptor),
                rewrite
            )
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC, className, helper.name, helper.descriptor,
                this@ApiTransformer.isInterface
            )
        }

        /**
         * Reporting is the open-ended half, so it does need the index.
         *
         * A type the JDK never had at this release is somebody else's class, not
         * a missing member.
         */
        private fun reportIfMissing(owner: String, name: String, descriptor: String) {
            val index = settings.index
            if (!index.knowsType(owner) || index.hasMember(owner, name, descriptor)) return

            val signature = "$owner.$name$descriptor"
            if (reported.add(signature)) {
                onApiFinding("$signature does not exist on the target and has no faithful equivalent")
            }
        }
    }

    /**
     * The descriptor a generated method needs to stand in for a call.
     *
     * An instance call leaves its receiver on the stack, so the generated method
     * has to take it as a first parameter or the operands no longer match. The
     * rewrites all read their arguments from slot 0. A method reference is the
     * same shape: an unbound receiver arrives as the first argument.
     */
    private fun helperDescriptor(isStatic: Boolean, owner: String, descriptor: String): String =
        if (isStatic) {
            descriptor
        } else {
            Type.getMethodDescriptor(
                Type.getReturnType(descriptor),
                Type.getObjectType(owner),
                *Type.getArgumentTypes(descriptor)
            )
        }

    private fun addHelper(descriptor: String, rewrite: InlineRewrite): Helper =
        Helper("$HELPER_PREFIX${helpers.size}", descriptor, rewrite).also { helpers += it }

    private fun generateHelper(helper: Helper) {
        val access = Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC or
            if (isInterface) Opcodes.ACC_PUBLIC else Opcodes.ACC_PRIVATE

        val mv = cv.visitMethod(access, helper.name, helper.descriptor, null, null)
        mv.visitCode()

        helper.rewrite.write(mv, helper.descriptor)

        // ClassWriter has COMPUTE_MAXS.
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }
}
