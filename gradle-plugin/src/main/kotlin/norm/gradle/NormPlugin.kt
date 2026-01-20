package norm.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.support.uppercaseFirstChar
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetContainer

/**
 * Gradle build plugin for generating Kotlin code from SQL modes using Norm.
 */
public class NormPlugin : Plugin<Project> {
  override fun apply(target: Project): Unit = target.run {
    configurations.create("norm") {
      isCanBeConsumed = false
      isCanBeResolved = true
    }

    val norm = extensions.create(
      NormExtension::class.java,
      "norm",
      NormExtensionImplementation::class.java,
      this,
    )
    // TODO: Gradle task to download sqlc if not available, or throw if it's the wrong version

    val kotlinSourceSet = project.extensions.getByName<KotlinProjectExtension>("kotlin")
      .sourceSets.getByName("main").kotlin

    norm.databases.all {
      // Set defaults for database properties
      useDatabase.convention(true)
      postgresVersion.convention("18")

      // Register YAML generation task
      val yamlTask = tasks.register<GenerateYamlTask>(
        "normGenerateYaml${name.uppercaseFirstChar()}",
        this,
      )

      yamlTask.configure {
        useDatabase.set(this@all.useDatabase)
      }

      // Register sqlc execution task
      val sqlcTask = tasks.register<RunSqlcTask>(
        "normRunSqlc${name.uppercaseFirstChar()}",
        this,
      )

      sqlcTask.configure {
        sqlcConfiguration.set(yamlTask.flatMap { task -> task.sqlcConfiguration })
        useDatabase.set(this@all.useDatabase)
        postgresVersion.set(this@all.postgresVersion)
        databaseName.set(name)
      }

      // Register code generation task
      val generateCodeTask = tasks.register<GenerateSchemasTask>(
        "normGenerateCode${name.uppercaseFirstChar()}",
        this,
      )

      generateCodeTask.configure {
        schemaJsonFile.set(sqlcTask.flatMap { task -> task.schemaJsonFile })
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

  /**
   * Constants you might find useful.
   */
  public companion object {
    /**
     * Directory in which Norm code is generated.
     */
    public const val NORM_GENERATED_CODE: String = "generated/norm"
    internal const val NORM_GROUP: String = "norm"
    internal const val NORM_VERSION: String = "1.0.0-SNAPSHOT"
  }
}
