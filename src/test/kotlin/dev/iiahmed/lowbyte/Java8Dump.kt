package dev.iiahmed.lowbyte

import java.io.File

/**
 * Writes every sample downgraded to Java 8, beside what it printed on Java 21.
 *
 * The `verifyOnJava8` Gradle task runs this and then runs each sample on a real
 * JDK 8. That last step is the only one that proves anything about a Java 8
 * target: the test JVM links `StringConcatFactory` and accepts `invokeinterface`
 * on a private method, so a downgrade that would die on 8 passes there quietly.
 */
fun main(arguments: Array<String>) {
    val root = File(arguments.single())
    root.deleteRecursively()

    val all = Fixtures.SAMPLES.map { it to false } + Fixtures.API_SAMPLES.map { it to true }

    all.forEach { (sample, api) ->
        val directory = File(root, sample).apply { mkdirs() }
        Fixtures.downgrade(sample, targetJava = 8, api = api).forEach { (name, classBytes) ->
            File(directory, "$name.class").writeBytes(classBytes)
        }
        File(directory, "expected.txt").writeText(Fixtures.baseline(sample))
    }

    println("Wrote ${all.size} samples to $root")
}
