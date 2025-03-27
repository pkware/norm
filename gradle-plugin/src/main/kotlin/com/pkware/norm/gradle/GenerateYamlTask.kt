package com.pkware.norm.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.intellij.lang.annotations.Language
import java.io.File
import javax.inject.Inject

/**
 * Generates a YAML configuration file for use with `sqlc`.
 *
 * See [GenerateCodeTask] for the task that generates Kotlin code.
 */
internal abstract class GenerateYamlTask @Inject constructor(
  @get:Nested val sqlSource: SqlSource,
  private val classPathFiles: Provider<Set<File>>,
  javaToolchainService: JavaToolchainService,
) : DefaultTask() {

  @get:Nested
  abstract val launcher: Property<JavaLauncher>

  @get:OutputFile
  internal val configurationFile = sqlSource.configurationFile(project)

  init {
    group = "norm"
    description = "Generate a sqlc YAML configuration file."

    val toolchain = project.extensions.getByType<JavaPluginExtension>().toolchain
    val defaultLauncher = javaToolchainService.launcherFor(toolchain)
    launcher.convention(defaultLauncher)
  }

  @TaskAction
  fun generateYaml() {
    val packageName = sqlSource.packageName.get()
    val generatedCodeDirectory = sqlSource.generatedPackageDirectory(project).get()

    val classPath = classPathFiles.get().joinToString(":")

    // sqlc insists on resolving the SQL file locations relative to the configuration file.
    val relativizer = project.projectDir.toRelativeString(configurationFile.get().asFile.parentFile)
    val schemaPath = "$relativizer/${sqlSource.schemaPath.get()}"
    val queriesPath = "$relativizer/${sqlSource.queriesPath.get()}"

    @Language("yaml")
    val template = """
			|version: '2'
			|plugins:
			|  - name: kotlin
			|    process:
			|      cmd: ${launcher.get().executablePath} -cp $classPath com.pkware.norm.generator.MainKt
			|sql:
			|  - schema: $schemaPath
			|    queries: $queriesPath
			|    engine: postgresql
			|    codegen:
			|      - out: ${generatedCodeDirectory.asFile.absolutePath}
			|        plugin: kotlin
			|        options:
			|          packageName: $packageName
    """.trimMargin()

    configurationFile.get().asFile.writeText(template)
  }
}
