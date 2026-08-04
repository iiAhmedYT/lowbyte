package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import dev.iiahmed.lowbyte.tasks.DowngradeBytecode
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The task, which is a mapping and nothing else.
 *
 * What a downgrade does to a jar belongs to [Lowbyte] and is tested against it
 * directly over in core. What is left here is the part that exists only because
 * Gradle does: task properties reaching the settings they stand for, and a
 * [LowbyteException] arriving as a [GradleException] so the build reports a
 * failure rather than a crash.
 *
 * Nothing here reads a fixture. Two behaviours need one broken class and one
 * ordinary one, and those are cheaper to emit than to compile.
 */
class DowngradeBytecodeTaskTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun theTaskPassesItsPropertiesThrough() {
        // Target and exclusions are both task properties, so a jar with one
        // excluded class and one ordinary one shows each of them arriving.
        val entries = mapOf(
            "com/foo/Kept.class" to plainClass("com/foo/Kept"),
            "com/foo/Lowered.class" to plainClass("com/foo/Lowered")
        )
        val output = File(tempDir, "mapped.jar")

        task(jarOf(entries), output, target = 11, excluded = listOf("com/foo/Kept")).downgrade()

        val result = readJar(output)
        assertTrue(
            entries.getValue("com/foo/Kept.class").contentEquals(result.getValue("com/foo/Kept.class")),
            "the exclusion did not reach Lowbyte"
        )
        assertEquals(
            11,
            ClassFileVersion.toJavaVersion(majorVersionOf(result.getValue("com/foo/Lowered.class"))),
            "the target did not reach Lowbyte"
        )
    }

    @Test
    fun anUnsupportedConstructBecomesAGradleException() {
        // Core throws its own exception. Gradle prints one of its own without a
        // stack trace, which is what a build failure should look like, so the
        // translation is the task's job and worth holding it to.
        val output = File(tempDir, "out.jar")

        val failure = assertFailsWith<GradleException> {
            task(jarOf(mapOf("Condy.class" to condyClass())), output, target = 9).downgrade()
        }

        assertTrue(failure.message!!.contains("CONSTANT_Dynamic"), failure.message!!)
        assertFalse(output.exists(), "a jar that cannot run must not be left behind")
    }

    @Test
    fun failOnUnsupportedOffReachesLowbyteToo() {
        val output = File(tempDir, "kept.jar")

        task(
            jarOf(mapOf("Condy.class" to condyClass())),
            output,
            target = 9,
            failOnUnsupported = false
        ).downgrade()

        assertTrue(output.exists(), "with the check off the jar should survive")
    }

    // helpers

    private fun task(
        input: File,
        output: File,
        target: Int,
        excluded: List<String> = emptyList(),
        failOnUnsupported: Boolean = true,
        api: Boolean = false
    ): DowngradeBytecode {
        val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        return project.tasks.create("downgrade$target${output.name.hashCode()}", DowngradeBytecode::class.java)
            .apply {
                targetJavaVersion.set(target)
                excludedClasses.set(excluded)
                this.failOnUnsupported.set(failOnUnsupported)
                this.api.set(api)
                inputJar.set(input)
                outputJar.set(output)
            }
    }

    private fun jarOf(entries: Map<String, ByteArray>): File {
        val file = File(tempDir, "in-${entries.keys.hashCode()}.jar")
        JarOutputStream(file.outputStream()).use { jos ->
            entries.forEach { (name, bytes) ->
                jos.putNextEntry(ZipEntry(name))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
        return file
    }

    private fun readJar(file: File): Map<String, ByteArray> = JarFile(file).use { jar ->
        jar.entries().asSequence().associate { it.name to jar.getInputStream(it).readAllBytes() }
    }

    private fun majorVersionOf(classBytes: ByteArray): Int = ClassReader(classBytes).readShort(6).toInt()

    /** An empty Java 21 class, which every target can express. */
    private fun plainClass(internalName: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            ClassFileVersion.fromJavaVersion(21),
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            internalName, null, "java/lang/Object", null
        )
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** A class holding something no target below 11 can express. */
    private fun condyClass(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(
            ClassFileVersion.fromJavaVersion(21),
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            "Condy", null, "java/lang/Object", null
        )
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "get", "()Ljava/lang/Object;", null, null
        )
        mv.visitCode()
        mv.visitLdcInsn(
            ConstantDynamic(
                "constant",
                "Ljava/lang/Object;",
                Handle(
                    Opcodes.H_INVOKESTATIC,
                    "java/lang/invoke/ConstantBootstraps",
                    "nullConstant",
                    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/Class;)" +
                        "Ljava/lang/Object;",
                    false
                )
            )
        )
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }
}
