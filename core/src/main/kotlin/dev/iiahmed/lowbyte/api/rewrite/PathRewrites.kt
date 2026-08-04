package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.ApiBytecode.PATH
import dev.iiahmed.lowbyte.api.ApiBytecode.PATHS
import dev.iiahmed.lowbyte.api.InlineRewrite
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * `Path.of`, which is `Paths.get` under a newer name.
 *
 * ```
 * Path.of(first, more)  ->  Paths.get(first, more)
 * Path.of(uri)          ->  Paths.get(uri)
 * ```
 *
 * Java 11 moved the factory onto the interface and left `Paths.get`, from Java
 * 7, delegating to it. So this is a straight forward, not a rebuild.
 */
sealed class PathOfRewrite(private val descriptor: String) : InlineRewrite() {

    override val name = "Path.of"

    override val introducedIn = 11

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == PATH && name == "of" && descriptor == this.descriptor

    override fun write(mv: MethodVisitor, descriptor: String) {
        org.objectweb.asm.Type.getArgumentTypes(descriptor).forEachIndexed { slot, _ ->
            mv.visitVarInsn(Opcodes.ALOAD, slot)
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PATHS, "get", descriptor, false)
        mv.visitInsn(Opcodes.ARETURN)
    }

    object OfStrings : PathOfRewrite("(Ljava/lang/String;[Ljava/lang/String;)L$PATH;")

    object OfUri : PathOfRewrite("(Ljava/net/URI;)L$PATH;")
}
