package com.pkware.norm.gradle

import norm.gradle.GenerateSchemasTask
import norm.gradle.RunSqlcTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.support.uppercaseFirstChar
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetContainer

public class NormPlugin : Plugin<Project> {
  override fun apply(target: Project): Unit = target.run {
    val normConfiguration = configurations.create("norm") {
      isCanBeConsumed = false
      isCanBeResolved = true
    }

    val norm = extensions.create(
      NormExtension::class.java,
      "norm",
      NormExtensionImplementation::class.java,
      this,
    )
    // TODO Gradle task to download sqlc if not available, or throw if it's the wrong version of sqlc or use docker task from PKWARE

    // TODO We need to start a DB and pass that to the yaml and generate task so they can link to it.

    val kotlinSourceSet = project.extensions.getByName<KotlinProjectExtension>("kotlin")
      .sourceSets.getByName("main").kotlin
    norm.databases.all {
      val yamlTask =
        tasks.register<GenerateYamlTask>("generateNormYaml${name.uppercaseFirstChar()}", this)
      val sqlcTask = tasks.register<RunSqlcTask>("runSqlc${name.uppercaseFirstChar()}", this)
      sqlcTask.configure {
        sqlcConfiguration.set(yamlTask.flatMap { it.sqlcConfiguration })
        queries.setFrom(yamlTask.map { it.queries })
        schemas.setFrom(yamlTask.map { it.schemas })
      }
      val generateCodeTask = tasks.register<GenerateSchemasTask>("generateNormCode${name.uppercaseFirstChar()}", this)
      generateCodeTask.configure {
        schemaJsonFile.set(sqlcTask.flatMap { it.schemaJsonFile })
      }

      kotlinSourceSet.srcDir(generateCodeTask)
    }

    // Add the runtime dependency to the project
    val sourceSetApiConfigName =
      extensions.getByType<KotlinSourceSetContainer>().sourceSets.getByName("main").apiConfigurationName
    configurations.getByName(
      sourceSetApiConfigName,
    ).dependencies.add(project.dependencies.create("com.pkware.norm:runtime:$NORM_VERSION"))
  }

  public companion object {
    /**
     * Directory in which NORM code is generated.
     */
    public const val NORM_GENERATED_CODE: String = "generated/norm"
    internal const val NORM_GROUP: String = "norm"
    internal const val NORM_VERSION: String = "1.0.0-SNAPSHOT"
  }
}
