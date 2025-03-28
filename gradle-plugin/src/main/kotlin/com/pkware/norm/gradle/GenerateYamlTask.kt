package com.pkware.norm.gradle

import norm.gradle.Database
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.intellij.lang.annotations.Language
import javax.inject.Inject

/**
 * Generates a YAML configuration file for use with `sqlc`.
 *
 * See [RunSqlcTask] for the task that generates Kotlin code.
 */
@CacheableTask
internal abstract class GenerateYamlTask @Inject constructor(
  @get:Nested val database: Database,
) : DefaultTask() {

  /**
   * sqlc insists on resolving the SQL file locations relative to the configuration file.
   */
  @get:Internal
  abstract val relativizer: Property<String>

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val schemas: ConfigurableFileCollection

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val queries: ConfigurableFileCollection

  @get:OutputFile
  val sqlcConfiguration = project.layout.buildDirectory.file("tmp/norm/${database.name}/sqlc.yaml")

  init {
    group = NormPlugin.NORM_GROUP
    description = "Generate a sqlc YAML configuration file."
    // TODO Can we just assign this?
//    sqlcConfiguration.set(project.layout.buildDirectory.file("tmp/norm/${sqlSource.name}/sqlc.yaml"))
    val sqlcConfigurationFileParent = sqlcConfiguration.get().asFile.parentFile
    relativizer.set(project.projectDir.toRelativeString(sqlcConfigurationFileParent))
    schemas.from(database.schemas.map { it.map(project.projectDir::resolve) })
    queries.from(database.queries.map { it.map(project.projectDir::resolve) })
  }

  @TaskAction
  fun generateYaml() {
    val packageName = database.packageName.get()

    val relativizer = relativizer.get()
    val schemaPaths = database.schemas.get().map { "$relativizer/$it" }
    val queryPaths = database.queries.get().map { "$relativizer/$it" }

    // FIXME Remove the package name from the options
    // FIXME upload the WASM plugin to Github and use a URL
    @Language("yaml")
    val template = """
			|version: '2'
			|plugins:
			|  - name: norm
			|    wasm:
			|      url: file:///Volumes/Code/pkware/norm/sqlc-plugin/target/wasm32-wasip1/release/sqlc-exporter.wasm
			|      sha256: e796dde73d2aee9a5870ee28174feb651a6d9baaae28d03ad5f45cc95f212e69
			|sql:
			|  - schema: [${schemaPaths.joinToString()}]
			|    queries: [${queryPaths.joinToString()}]
			|    engine: postgresql
			|    codegen:
			|      - out: .
			|        plugin: norm
			|        options:
			|          packageName: $packageName
    """.trimMargin()

    sqlcConfiguration.get().asFile.writeText(template)
  }
}
