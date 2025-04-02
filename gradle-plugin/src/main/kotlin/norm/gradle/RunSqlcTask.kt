package norm.gradle

import com.pkware.norm.gradle.NormPlugin
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Runs sqlc to resolve the Postgres schema.
 *
 * See [GenerateYamlTask] for the Gradle task that generates the YAML.
 * See [GenerateSchemasTask] for the Gradle task that generates Kotlin code.
 */
@CacheableTask
internal abstract class RunSqlcTask @Inject constructor(
  database: Database,
  private val execOperations: ExecOperations,
) : DefaultTask() {

  /**
   * Path to the `sqlc` command line tool.
   */
  @get:Input
  val sqlc: Property<String> = project.objects.property<String>().convention("/opt/homebrew/bin/sqlc")

  /**
   * YAML configuration file used by `sqlc`.
   */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sqlcConfiguration: RegularFileProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val schemas: ConfigurableFileCollection

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val queries: ConfigurableFileCollection

  /**
   * JSON file that will contain the schema.
   */
  @get:OutputFile
  abstract val schemaJsonFile: RegularFileProperty

  init {
    group = NormPlugin.NORM_GROUP
    description = "Runs sqlc to resolve the Postgres schema."
    schemaJsonFile.set(database.schemaJsonFile(project))
    schemas.from(database.schemas.map { it.map(project.projectDir::resolve) })
    queries.from(database.queries.map { it.map(project.projectDir::resolve) })
  }

  @TaskAction
  fun invokeSqlc() {
    execOperations.exec {
      commandLine(sqlc.get(), "generate", "--file", sqlcConfiguration.get().asFile.absolutePath)
      standardOutput = System.out
    }.assertNormalExitValue()
  }
}
