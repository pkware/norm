package com.pkware.norm.gradle

import norm.gradle.Database
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
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

  @get:OutputFile
  abstract val sqlcConfiguration: RegularFileProperty

  init {
    group = NormPlugin.NORM_GROUP
    description = "Generates a sqlc YAML configuration file."
    sqlcConfiguration.set(project.layout.buildDirectory.file("tmp/norm/${database.name}/sqlc.yaml"))
    val sqlcConfigurationFile = sqlcConfiguration.get().asFile
    relativizer.set(project.projectDir.toRelativeString(sqlcConfigurationFile))
  }

  @TaskAction
  fun generateYaml() {
    val relativizer = relativizer.get()
    val schemaPaths = database.schemas.get().map { "$relativizer/$it" }
    val queryPaths = database.queries.get().map { "$relativizer/$it" }

    @Language("yaml")
    val template = """
			|version: '2'
			|plugins:
			|  - name: norm
			|    wasm:
			|      url: https://github.com/pkware/norm/releases/download/0.0.1/sqlc-exporter.wasm
			|      sha256: e796dde73d2aee9a5870ee28174feb651a6d9baaae28d03ad5f45cc95f212e69
			|sql:
			|  - schema: [${schemaPaths.joinToString()}]
			|    queries: [${queryPaths.joinToString()}]
			|    engine: postgresql
			|    codegen:
			|      - out: .
			|        plugin: norm
    """.trimMargin()

    sqlcConfiguration.get().asFile.writeText(template)
  }
}
