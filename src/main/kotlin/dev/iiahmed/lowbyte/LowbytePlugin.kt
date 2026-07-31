package dev.iiahmed.lowbyte

import dev.iiahmed.lowbyte.tasks.DowngradeBytecode
import dev.iiahmed.lowbyte.classfile.ClassFileVersion
import org.gradle.api.Plugin
import org.gradle.api.Project

abstract class LowbytePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("lowbyte", LowbyteExtension::class.java)

        val downgradeBytecode = project.tasks.register("downgradeBytecode", DowngradeBytecode::class.java) {
            dependsOn("jar")

            targetJavaVersion.set(extension.targetJavaVersion)
            excludedClasses.set(extension.excludedClasses)
            failOnUnsupported.set(extension.failOnUnsupported)
            api.set(extension.api)
            runtimeClass.set(extension.runtimeClass)

            val inputFileLocation = extension.jarFilePattern.getOrElse("libs/${project.name}.jar")
            inputJar.set(project.layout.buildDirectory.file(inputFileLocation))
            outputJar.set(project.layout.buildDirectory.file("libs/${project.name}-downgraded.jar"))
        }

        project.afterEvaluate {
            if (!extension.targetJavaVersion.isPresent) {
                project.logger.warn("Lowbyte: 'targetJavaVersion' is not set.")
                return@afterEvaluate
            }

            ClassFileVersion.requireSupportedTarget(extension.targetJavaVersion.get())

            project.configurations.create("lowbyte") {
                isCanBeConsumed = true
                isCanBeResolved = false
            }

            project.artifacts.add("lowbyte", downgradeBytecode.flatMap { it.outputJar }) {
                type = "jar"
                builtBy(downgradeBytecode)
            }

            project.tasks.named("build") {
                dependsOn(downgradeBytecode)
            }
        }
    }

}
