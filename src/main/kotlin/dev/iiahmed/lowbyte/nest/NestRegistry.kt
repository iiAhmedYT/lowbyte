package dev.iiahmed.lowbyte.nest

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/** What a bridge stands in for. */
enum class BridgeKind { FIELD_GET, FIELD_SET, METHOD, CONSTRUCTOR }

/** A private member of one nestmate, named the way a call site names it. */
data class MemberRef(val owner: String, val name: String, val descriptor: String)

/**
 * One generated accessor, and everything needed to emit it and to call it.
 *
 * [name] and [descriptor] describe the bridge; [member] describes what it
 * reaches. For [BridgeKind.CONSTRUCTOR] the bridge is not a static method but a
 * package-private constructor overload, so [name] is `<init>` and [markerClass]
 * holds the empty class whose type makes that overload unique.
 */
class Bridge(
    val kind: BridgeKind,
    val member: MemberRef,
    val isStatic: Boolean,
    val name: String,
    val descriptor: String,
    val markerClass: String? = null
)

/**
 * Which private members are reached from another class in the same nest, and
 * what to call instead once the nest is gone.
 *
 * This is the piece that cannot be worked out from one class file. A call site
 * naming `Outer.secret` carries the owner, name and descriptor but not the
 * access flags, so whether it needs a bridge is a fact about *another* class.
 * Hence the scan: read every class first, then rewrite.
 */
class NestRegistry private constructor(
    private val bridges: Map<Pair<BridgeKind, MemberRef>, Bridge>
) {

    val isEmpty: Boolean get() = bridges.isEmpty()

    /** Every class that has to gain a bridge, so the caller can check they all exist. */
    val bridgedOwners: Set<String> get() = bridges.values.map { it.member.owner }.toSet()

    /** The empty marker classes that have to be added to the jar. */
    val markerClasses: Set<String> get() = bridges.values.mapNotNull { it.markerClass }.toSet()

    fun bridge(kind: BridgeKind, owner: String, name: String, descriptor: String): Bridge? =
        bridges[kind to MemberRef(owner, name, descriptor)]

    /** Sorted so the generated class is byte-for-byte stable across runs. */
    fun bridgesOwnedBy(className: String): List<Bridge> =
        bridges.values.filter { it.member.owner == className }.sortedBy { it.name }

    companion object {

        /** Nestmates arrived in 11, so below that the attributes have to go. */
        const val INTRODUCED_IN = 11

        val EMPTY = NestRegistry(emptyMap())

        /** Prefix of every generated accessor, matching the other transforms. */
        private const val BRIDGE_PREFIX = "lowbyte\$access\$"

        /**
         * Suffix of the empty class that disambiguates a constructor overload.
         *
         * javac 8 used the next anonymous class number for this. A fixed name
         * keyed off the owner does the same job and cannot collide with one of
         * ours or one of javac's.
         */
        private const val MARKER_SUFFIX = "\$lowbyte\$Nest"

        /**
         * Reads every class, then works out which references need a bridge.
         *
         * [classes] is consumed once and nothing is retained, so a large jar
         * costs one extra read rather than being held in memory.
         */
        fun scan(classes: Sequence<ByteArray>): NestRegistry {
            val declarations = mutableMapOf<String, Declaration>()
            val references = mutableListOf<Reference>()

            classes.forEach { classBytes ->
                val scanner = Scanner(references)
                ClassReader(classBytes).accept(scanner, ClassReader.SKIP_FRAMES)
                declarations[scanner.className] = scanner.toDeclaration()
            }

            return NestRegistry(resolve(declarations, references))
        }

        /**
         * Keeps the references that name a private member of a nestmate, and
         * gives each one a bridge.
         *
         * Anything else is left alone: a member that is not private needs no
         * help, and an owner outside the jar cannot be a nestmate.
         */
        private fun resolve(
            declarations: Map<String, Declaration>,
            references: List<Reference>
        ): Map<Pair<BridgeKind, MemberRef>, Bridge> {
            fun nestHostOf(className: String) = declarations[className]?.nestHost ?: className

            val needed = references.mapNotNull { reference ->
                if (reference.from == reference.member.owner) return@mapNotNull null
                val owner = declarations[reference.member.owner] ?: return@mapNotNull null
                if (nestHostOf(reference.from) != nestHostOf(reference.member.owner)) return@mapNotNull null

                val member = owner.privateMember(reference.kind, reference.member) ?: return@mapNotNull null
                reference.kind to member
            }.distinct()

            // Named per owner in a fixed order, because the call site and the
            // bridge are emitted while visiting different classes and have to
            // agree without talking to each other.
            return needed
                .groupBy { (_, member) -> member.declaration.owner }
                .flatMap { (owner, group) ->
                    val isInterface = declarations[owner]?.isInterface == true
                    group
                        .sortedWith(
                            compareBy(
                                { (kind, _) -> kind.ordinal },
                                { (_, member) -> member.declaration.name },
                                { (_, member) -> member.declaration.descriptor }
                            )
                        )
                        .mapIndexed { index, (kind, member) ->
                            (kind to member.declaration) to bridgeFor(kind, member, index, isInterface)
                        }
                }
                .toMap()
        }

        private fun bridgeFor(kind: BridgeKind, member: FoundMember, index: Int, isInterface: Boolean): Bridge {
            val declaration = member.declaration
            val owner = Type.getObjectType(declaration.owner)
            val name = "$BRIDGE_PREFIX$index"

            return when (kind) {
                BridgeKind.FIELD_GET -> {
                    val fieldType = Type.getType(declaration.descriptor)
                    val arguments = if (member.isStatic) emptyArray() else arrayOf(owner)
                    Bridge(kind, declaration, member.isStatic, name, Type.getMethodDescriptor(fieldType, *arguments))
                }

                BridgeKind.FIELD_SET -> {
                    val fieldType = Type.getType(declaration.descriptor)
                    val arguments =
                        if (member.isStatic) arrayOf(fieldType) else arrayOf(owner, fieldType)
                    Bridge(
                        kind, declaration, member.isStatic, name,
                        Type.getMethodDescriptor(Type.VOID_TYPE, *arguments)
                    )
                }

                BridgeKind.METHOD -> {
                    val returnType = Type.getReturnType(declaration.descriptor)
                    val arguments = Type.getArgumentTypes(declaration.descriptor)
                    val all = if (member.isStatic) arguments else arrayOf(owner) + arguments
                    Bridge(
                        kind, declaration, member.isStatic, name,
                        Type.getMethodDescriptor(returnType, *all)
                    )
                }

                BridgeKind.CONSTRUCTOR -> {
                    val marker = "${declaration.owner}$MARKER_SUFFIX"
                    val arguments = Type.getArgumentTypes(declaration.descriptor) +
                        Type.getObjectType(marker)
                    Bridge(
                        kind, declaration, isStatic = false, name = "<init>",
                        descriptor = Type.getMethodDescriptor(Type.VOID_TYPE, *arguments),
                        markerClass = marker
                    )
                }
            }.also { require(!isInterface || kind != BridgeKind.CONSTRUCTOR) { "an interface has no constructor" } }
        }

        /**
         * The empty class that makes a bridged constructor's signature unique.
         *
         * Never instantiated: the call site passes null. javac 8 left its
         * equivalent with no members at all, and so do we.
         */
        fun markerClassBytes(internalName: String, targetMajor: Int): ByteArray {
            val cw = ClassWriter(0)
            cw.visit(
                targetMajor,
                Opcodes.ACC_SUPER or Opcodes.ACC_SYNTHETIC,
                internalName,
                null,
                "java/lang/Object",
                null
            )
            cw.visitEnd()
            return cw.toByteArray()
        }
    }

    /** A reference from one class to a member of another. */
    private class Reference(val from: String, val kind: BridgeKind, val member: MemberRef)

    private class FoundMember(val declaration: MemberRef, val isStatic: Boolean)

    /** What one class file says about itself. */
    private class Declaration(
        val nestHost: String,
        val isInterface: Boolean,
        private val privateFields: Map<MemberRef, Boolean>,
        private val privateMethods: Map<MemberRef, Boolean>
    ) {

        /** The private member this reference names, or null if there isn't one. */
        fun privateMember(kind: BridgeKind, reference: MemberRef): FoundMember? {
            val table = if (kind == BridgeKind.FIELD_GET || kind == BridgeKind.FIELD_SET) {
                privateFields
            } else {
                privateMethods
            }
            val isStatic = table[reference] ?: return null
            return FoundMember(reference, isStatic)
        }
    }

    private class Scanner(private val references: MutableList<Reference>) : ClassVisitor(Opcodes.ASM9) {

        var className = ""
            private set

        private var nestHost: String? = null
        private var isInterface = false
        private val privateFields = mutableMapOf<MemberRef, Boolean>()
        private val privateMethods = mutableMapOf<MemberRef, Boolean>()

        fun toDeclaration() = Declaration(nestHost ?: className, isInterface, privateFields, privateMethods)

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
        }

        override fun visitNestHost(nestHost: String?) {
            this.nestHost = nestHost
        }

        override fun visitField(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            value: Any?
        ): FieldVisitor? {
            if ((access and Opcodes.ACC_PRIVATE) != 0) {
                privateFields[MemberRef(className, name.orEmpty(), descriptor.orEmpty())] =
                    (access and Opcodes.ACC_STATIC) != 0
            }
            return null
        }

        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            if ((access and Opcodes.ACC_PRIVATE) != 0) {
                privateMethods[MemberRef(className, name.orEmpty(), descriptor.orEmpty())] =
                    (access and Opcodes.ACC_STATIC) != 0
            }
            return ReferenceCollector()
        }

        private inner class ReferenceCollector : MethodVisitor(Opcodes.ASM9) {

            override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) {
                val kind = if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) {
                    BridgeKind.FIELD_GET
                } else {
                    BridgeKind.FIELD_SET
                }
                record(kind, owner, name, descriptor)
            }

            override fun visitMethodInsn(
                opcode: Int,
                owner: String?,
                name: String?,
                descriptor: String?,
                isInterface: Boolean
            ) {
                record(methodKind(name), owner, name, descriptor)
            }

            override fun visitInvokeDynamicInsn(
                name: String?,
                descriptor: String?,
                bootstrapMethodHandle: Handle?,
                vararg bootstrapMethodArguments: Any?
            ) {
                // A handle reaches a member just as an instruction does.
                bootstrapMethodArguments.filterIsInstance<Handle>().forEach { handle ->
                    record(handleKind(handle) ?: return@forEach, handle.owner, handle.name, handle.desc)
                }
            }

            private fun record(kind: BridgeKind, owner: String?, name: String?, descriptor: String?) {
                if (owner == null || owner == className) return
                references += Reference(
                    className, kind, MemberRef(owner, name.orEmpty(), descriptor.orEmpty())
                )
            }
        }
    }
}

private fun methodKind(name: String?): BridgeKind =
    if (name == "<init>") BridgeKind.CONSTRUCTOR else BridgeKind.METHOD

private fun handleKind(handle: Handle): BridgeKind? = when (handle.tag) {
    Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC -> BridgeKind.FIELD_GET
    Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC -> BridgeKind.FIELD_SET
    Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKESTATIC, Opcodes.H_INVOKESPECIAL,
    Opcodes.H_INVOKEINTERFACE -> methodKind(handle.name)
    // A constructor reference cannot take the marker argument, so it is left
    // for the transform to report rather than silently mis-bridged.
    else -> null
}
