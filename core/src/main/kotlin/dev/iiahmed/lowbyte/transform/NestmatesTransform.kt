package dev.iiahmed.lowbyte.transform

import dev.iiahmed.lowbyte.downgrade.DowngradeContext
import dev.iiahmed.lowbyte.nest.Bridge
import dev.iiahmed.lowbyte.nest.BridgeKind
import dev.iiahmed.lowbyte.nest.NestRegistry
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/** Registry entry for [NestmatesTransformer]. */
object NestmatesTransform : FeatureTransform {

    override val name = "nestmates"

    override val introducedIn = NestRegistry.INTRODUCED_IN

    override fun wrap(
        next: ClassVisitor,
        context: DowngradeContext,
        onUnsupported: (String) -> Unit
    ): ClassVisitor = NestmatesTransformer(next, context.nests, onUnsupported)
}

/**
 * Drops the nest attributes and restores the access that went with them.
 *
 * Since Java 11 a class may reach straight into a private member of another
 * class in its nest, and javac emits exactly that: a plain `getfield` on someone
 * else's private field. The permission comes from `NestHost` and `NestMembers`,
 * so removing them turns every such access into an `IllegalAccessError`.
 *
 * The fix is the one javac used before nestmates existed. Each reached member
 * gets a package-private static accessor on the class that owns it, and the call
 * site invokes that instead. Package-private is enough because a nest is a
 * top-level class and its nested classes, which are always in one package.
 *
 * Which members those are cannot be seen from here, so it is worked out ahead of
 * time by [NestRegistry] and handed over in the context.
 *
 * Constructors are the exception. `new Foo(...)` compiles to `new`, `dup`,
 * arguments, `invokespecial`, and the `new` may be arbitrarily far from the
 * `invokespecial`, so swapping in a static factory would mean pairing them up by
 * dataflow. Instead the owner gains a package-private constructor overload taking
 * one extra argument of an otherwise empty generated type, and the call site
 * passes null. That leaves the `new`/`dup` shape untouched.
 */
class NestmatesTransformer(
    classVisitor: ClassVisitor,
    private val nests: NestRegistry,
    private val onUnsupported: (String) -> Unit
) : ClassVisitor(Opcodes.ASM9, classVisitor) {

    private var className = ""
    private var isInterface = false

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

    /** Both nest attributes are dropped by not forwarding them. */
    override fun visitNestHost(nestHost: String?) = Unit

    override fun visitNestMember(nestMember: String?) = Unit

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        return AccessSiteVisitor(mv)
    }

    override fun visitEnd() {
        nests.bridgesOwnedBy(className).forEach { generateBridge(it) }
        super.visitEnd()
    }

    private inner class AccessSiteVisitor(mv: MethodVisitor) : MethodVisitor(Opcodes.ASM9, mv) {

        override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) {
            val kind = if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) {
                BridgeKind.FIELD_GET
            } else {
                BridgeKind.FIELD_SET
            }
            val bridge = bridgeFor(kind, owner, name, descriptor)
            if (bridge == null) {
                super.visitFieldInsn(opcode, owner, name, descriptor)
                return
            }
            // Reads leave the value where the field access would have; writes
            // consume the same operands and return void, so the stack matches
            // either way.
            super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, bridge.name, bridge.descriptor, false)
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean
        ) {
            val bridge = bridgeFor(kindOf(name), owner, name, descriptor)
            if (bridge == null) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                return
            }

            if (bridge.kind == BridgeKind.CONSTRUCTOR) {
                // The arguments are already on the stack, so the marker goes on
                // last and the `new`/`dup` above are left exactly as they were.
                super.visitInsn(Opcodes.ACONST_NULL)
                super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", bridge.descriptor, false)
                return
            }

            super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, bridge.name, bridge.descriptor, false)
        }

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

        /**
         * A handle reaches a member the same way an instruction does.
         *
         * Turning an instance handle into a static one with the receiver as its
         * first argument is a shape `LambdaMetafactory` already accepts, so the
         * call site keeps working.
         */
        private fun rewriteHandle(handle: Handle): Handle {
            if (handle.owner == className) return handle

            if (handle.tag == Opcodes.H_NEWINVOKESPECIAL) {
                // A constructor reference has nowhere to put the marker argument.
                if (nests.bridge(BridgeKind.CONSTRUCTOR, handle.owner, handle.name, handle.desc) != null) {
                    onUnsupported(
                        "a constructor reference to the private ${handle.owner}.${handle.desc}, " +
                            "which cannot take the marker argument a bridged constructor needs"
                    )
                }
                return handle
            }

            val kind = when (handle.tag) {
                Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC -> BridgeKind.FIELD_GET
                Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC -> BridgeKind.FIELD_SET
                else -> kindOf(handle.name)
            }
            val bridge = nests.bridge(kind, handle.owner, handle.name, handle.desc) ?: return handle

            return Handle(Opcodes.H_INVOKESTATIC, handle.owner, bridge.name, bridge.descriptor, false)
        }

        private fun bridgeFor(
            kind: BridgeKind,
            owner: String?,
            name: String?,
            descriptor: String?
        ): Bridge? {
            if (owner == null || owner == className || descriptor == null) return null
            return nests.bridge(kind, owner, name.orEmpty(), descriptor)
        }
    }

    /**
     * Writes one accessor.
     *
     * Every body here is straight-line, so there are no frames to work out and
     * COMPUTE_MAXS covers the rest.
     */
    private fun generateBridge(bridge: Bridge) {
        val access = if (bridge.kind == BridgeKind.CONSTRUCTOR) {
            // Package-private, which is all a nestmate needs.
            Opcodes.ACC_SYNTHETIC
        } else {
            // An interface has no package-private members, so its accessors are
            // public. Everywhere else package-private keeps them out of the API.
            Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC or
                if (isInterface) Opcodes.ACC_PUBLIC else 0
        }

        val mv = cv.visitMethod(access, bridge.name, bridge.descriptor, null, null)
        mv.visitCode()

        when (bridge.kind) {
            BridgeKind.FIELD_GET -> {
                if (!bridge.isStatic) mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitFieldInsn(
                    if (bridge.isStatic) Opcodes.GETSTATIC else Opcodes.GETFIELD,
                    bridge.member.owner, bridge.member.name, bridge.member.descriptor
                )
                mv.visitInsn(Type.getType(bridge.member.descriptor).getOpcode(Opcodes.IRETURN))
            }

            BridgeKind.FIELD_SET -> {
                loadArguments(mv, bridge.descriptor)
                mv.visitFieldInsn(
                    if (bridge.isStatic) Opcodes.PUTSTATIC else Opcodes.PUTFIELD,
                    bridge.member.owner, bridge.member.name, bridge.member.descriptor
                )
                mv.visitInsn(Opcodes.RETURN)
            }

            BridgeKind.METHOD -> {
                loadArguments(mv, bridge.descriptor)
                mv.visitMethodInsn(
                    // A private method is not virtual, so the owner reaches its
                    // own with invokespecial exactly as javac does.
                    if (bridge.isStatic) Opcodes.INVOKESTATIC else Opcodes.INVOKESPECIAL,
                    bridge.member.owner, bridge.member.name, bridge.member.descriptor, isInterface
                )
                mv.visitInsn(Type.getReturnType(bridge.member.descriptor).getOpcode(Opcodes.IRETURN))
            }

            BridgeKind.CONSTRUCTOR -> {
                // `this(...)`, dropping the marker the caller passed.
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                loadArguments(mv, bridge.descriptor, skipLast = 1, firstSlot = 1)
                mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL, bridge.member.owner, "<init>", bridge.member.descriptor, false
                )
                mv.visitInsn(Opcodes.RETURN)
            }
        }

        // ClassWriter has COMPUTE_MAXS.
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun loadArguments(
        mv: MethodVisitor,
        descriptor: String,
        skipLast: Int = 0,
        firstSlot: Int = 0
    ) {
        var slot = firstSlot
        val arguments = Type.getArgumentTypes(descriptor)
        arguments.dropLast(skipLast).forEach { argument ->
            mv.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot)
            slot += argument.size
        }
    }

    private fun kindOf(name: String?): BridgeKind =
        if (name == "<init>") BridgeKind.CONSTRUCTOR else BridgeKind.METHOD
}
