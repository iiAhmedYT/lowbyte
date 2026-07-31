package dev.iiahmed.lowbyte.api.rewrite

import dev.iiahmed.lowbyte.api.ApiBytecode
import dev.iiahmed.lowbyte.api.ApiBytecode.COLLECTION
import dev.iiahmed.lowbyte.api.ApiBytecode.LIST
import dev.iiahmed.lowbyte.api.ApiRewrite
import dev.iiahmed.lowbyte.api.ApiSlots
import org.objectweb.asm.MethodVisitor

/** `List.copyOf`, a snapshot refusing a null collection and null elements. */
object ListCopyOfRewrite : ApiRewrite {

    override val name = "List.copyOf"

    override val introducedIn = 10

    override fun matches(owner: String, name: String, descriptor: String) =
        owner == LIST && name == "copyOf" && descriptor == "(L$COLLECTION;)Ljava/util/List;"

    override fun write(mv: MethodVisitor, descriptor: String) {
        val slots = ApiSlots(descriptor)
        ApiBytecode.copyArgumentIntoList(mv, slots)
        ApiBytecode.returnUnmodifiable(mv, slots, "unmodifiableList", LIST)
    }
}
