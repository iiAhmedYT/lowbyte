package dev.iiahmed.lowbyte

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The published jar, exercised the way a stranger would get it.
 *
 * Shading is build configuration that looks right until somebody runs it: core
 * left out, the `.classdata` runtime left behind, or relocation applied to some
 * references and not others. None of those fail the build.
 *
 * Loading it under the platform classloader is what makes this a real test.
 * Nothing resolves from the test's own classpath, so a class the jar failed to
 * carry throws rather than falling back to the copy next door.
 */
class ShadedJarTest {

    @TempDir
    lateinit var tempDir: File

    private val shadedJar: File
        get() = File(
            requireNotNull(System.getProperty("lowbyte.shadedJar")) {
                "lowbyte.shadedJar is not set, so the test task is not passing the built jar in"
            }
        )

    /** The jar and its one declared dependency, nothing of ours. */
    private fun isolated(): URLClassLoader {
        val runtime = System.getProperty("lowbyte.shadedRuntime").orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it) }

        val urls = (listOf(shadedJar) + runtime).map { it.toURI().toURL() }.toTypedArray()
        return URLClassLoader(urls, ClassLoader.getPlatformClassLoader())
    }

    @Test
    fun theShadedJarDowngradesOnItsOwn() {
        val input = jarOf(mapOf("Sample.class" to repeatCaller()))
        val output = File(tempDir, "out.jar")

        val injected = isolated().use { loader ->
            val lowbyte = loader.loadClass("dev.iiahmed.lowbyte.Lowbyte")

            var builder = lowbyte.getMethod("targeting", Int::class.javaPrimitiveType).invoke(null, 8)
            builder = builder.javaClass.getMethod("api", Boolean::class.javaPrimitiveType)
                .invoke(builder, true)
            val instance = builder.javaClass.getMethod("build").invoke(builder)

            val result = lowbyte.getMethod("downgrade", File::class.java, File::class.java)
                .invoke(instance, input, output)

            result.javaClass.getMethod("getInjectedClass").invoke(result) as String?
        }

        // The utility name comes from a resource inside the jar, so getting one
        // back at all proves the resource travelled with the classes.
        assertTrue(
            injected != null && injected.startsWith("dev/iiahmed/lowbyte/runtime/"),
            "nothing was injected, so the runtime template did not survive shading: $injected"
        )
        assertTrue(
            readJar(output).containsKey("$injected.class"),
            "the injected class never reached the jar: ${readJar(output).keys}"
        )
        assertEquals(52, majorVersionOf(readJar(output).getValue("Sample.class")), "not Java 8 bytecode")
    }

    @Test
    fun asmIsRelocatedRatherThanShippedWhereItCanClash() {
        // A build script classpath is shared by every plugin on it, so an
        // unrelocated ASM here is a version fight with whatever else is there.
        JarFile(shadedJar).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toList()

            assertTrue(
                entries.none { it.startsWith("org/objectweb/asm/") },
                "ASM is still under its own package: " +
                    entries.filter { it.startsWith("org/objectweb/asm/") }.take(3)
            )
            assertTrue(
                entries.any { it.startsWith("dev/iiahmed/lowbyte/shaded/asm/") },
                "ASM was not bundled at all, so the plugin has nothing to rewrite bytecode with"
            )
            // Bundling Kotlin's runtime would put a second copy beside Gradle's.
            assertTrue(
                entries.none { it.startsWith("kotlin/") },
                "Kotlin's runtime was bundled, which is the collision this avoids"
            )
            assertTrue(
                entries.contains("META-INF/gradle-plugins/dev.iiahmed.lowbyte.properties"),
                "the plugin descriptor did not survive shading, so the id resolves to nothing"
            )
        }
    }

    // helpers

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

    private fun majorVersionOf(classBytes: ByteArray): Int =
        ((classBytes[6].toInt() and 0xFF) shl 8) or (classBytes[7].toInt() and 0xFF)

    /** A Java 21 class calling String.repeat, which needs the injected utility. */
    private fun repeatCaller(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        // 65 is Java 21. Written out rather than taken from ClassFileVersion,
        // since this test is about the jar and not about core's API.
        cw.visit(65, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "Sample", null, "java/lang/Object", null)
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "go", "(Ljava/lang/String;)Ljava/lang/String;", null, null
        )
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitInsn(Opcodes.ICONST_3)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, "java/lang/String", "repeat", "(I)Ljava/lang/String;", false
        )
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }
}
