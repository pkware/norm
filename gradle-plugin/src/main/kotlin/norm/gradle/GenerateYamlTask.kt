package norm.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.intellij.lang.annotations.Language
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.relativeTo

/**
 * Generates a YAML configuration file for use with `sqlc`.
 *
 * When [useDatabase] is enabled, the YAML is generated without database connection info,
 * which will be added later by [RunSqlcTask] after the container starts.
 *
 * See [RunSqlcTask] for the task that invokes sqlc.
 */
@CacheableTask
internal abstract class GenerateYamlTask @Inject constructor(@get:Nested val database: Database) : DefaultTask() {

  /**
   * The absolute path of the project directory.
   */
  @get:Internal
  abstract val projectDirectory: Property<String>

  @get:OutputFile
  abstract val sqlcConfiguration: RegularFileProperty

  /**
   * Whether database-backed analysis is enabled.
   * Tracked as input to ensure task re-runs when toggling database on/off.
   */
  @get:Input
  abstract val useDatabase: Property<Boolean>

  init {
    group = NormPlugin.NORM_GROUP
    description = "Generates a sqlc YAML configuration file."
    sqlcConfiguration.set(project.layout.buildDirectory.file("tmp/norm/${database.name}/sqlc.yaml"))
    projectDirectory.set(project.projectDir.absolutePath)
  }

  @TaskAction
  fun generateYaml() {
    // sqlc insists on resolving the SQL file locations relative to the directory containing the configuration file.
    val projectDirectory = Path(projectDirectory.get())
    val sqlcConfigurationDirectory = sqlcConfiguration.get().asFile.toPath().parent
    val schemaPaths = database.schemas.get().map {
      val schemaFile = projectDirectory.resolve(it)
      schemaFile.relativeTo(sqlcConfigurationDirectory).toString()
    }
    val queryPaths = database.queries.get().map {
      val queryFile = projectDirectory.resolve(it)
      queryFile.relativeTo(sqlcConfigurationDirectory).toString()
    }

    // Note: database section will be added by RunSqlcTask if useDatabase is true
    @Language("yaml")
    val template = """
      |version: '2'
      |plugins:
      |  - name: norm
      |    wasm:
      |      url: https://github.com/pkware/norm/releases/download/0.0.2/sqlc-exporter.wasm
      |      sha256: f6d1d7bc9b8659e3c77dd0434ae4f8a25599116951950089b8d46c2275a886ea
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
