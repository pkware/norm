package com.pkware.norm.gradle

import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

/**
 * Generates Kotlin code from SQL sources and a YAML configuration file.
 *
 * See [GenerateYamlTask] for the Gradle task that generates the YAML.
 */
internal open class GenerateCodeTask @Inject constructor(sqlSource: SqlSource) : Exec() {

  /**
   * Path to the `sqlc` command line tool.
   */
  @get:Input
  val sqlc: Property<String> = project.objects.property<String>().convention("/opt/homebrew/bin/sqlc")

  /**
   * YAML configuration file used by `sqlc`.
   */
  @get:InputFile
  internal val sqlcConfiguration = sqlSource.configurationFile(project)

  /**
   * Directory into which to generate Kotlin source code.
   */
  @get:OutputDirectory
  internal val outputDirectory = sqlSource.generatedPackageDirectory(project)

  init {
    group = "build"
    description = "Generate Kotlin code from SQL."
    commandLine(sqlc.get(), "generate", "--file", sqlcConfiguration.get().asFile.absolutePath)
  }
}
