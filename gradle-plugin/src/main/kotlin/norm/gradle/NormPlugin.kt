package norm.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.support.uppercaseFirstChar
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetContainer

/**
 * Gradle build plugin for generating Kotlin code from SQL using Norm.
 *
 * For each configured database, registers a `normGenerate<Name>` task that:
 * 1. Starts a PostgreSQL container via Testcontainers
 * 2. Applies schema files to create the database structure
 * 3. Uses JDBC metadata to analyze queries and introspect the schema
 * 4. Generates type-safe Kotlin code
 *
 * Docker must be installed and running.
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

    val mainSourceSet: KotlinSourceSet = project.extensions.getByName<KotlinProjectExtension>("kotlin")
      .sourceSets.getByName("main")

    norm.databases.all {
      postgresVersion.convention("18-alpine")
      generateCrud.convention(true)
      typeMappings.convention(emptyList())

      val generateTask = tasks.register<NormGenerateTask>(
        "normGenerate${name.uppercaseFirstChar()}",
        this,
      )

      generateTask.configure {
        postgresVersion.set(this@all.postgresVersion)
      }

      IdeIntegration.registerGeneratedSources(mainSourceSet, generateTask)
      IdeIntegration.configureIdeaExtAfterSync(project, generateTask)
    }

    // Add the runtime dependency to the project
    val sourceSetApiConfigName =
      extensions.getByType<KotlinSourceSetContainer>().sourceSets.getByName("main").apiConfigurationName
    configurations.getByName(
      sourceSetApiConfigName,
    ).dependencies.add(project.dependencies.create("com.pkware.norm:runtime:$BUILD_VERSION"))
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
  }
}
